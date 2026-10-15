package com.hubsabai.changelog.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.ai.AiMessage;
import com.hubsabai.changelog.ai.AiProviderRegistry;
import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.ai.AiStreamException;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.storage.ChangelogCacheService;
import com.hubsabai.changelog.storage.RawReleaseService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Shared machinery behind {@code POST .../changelog-chat/stream} for both provider resources
 * (Azure rides the root, GitHub under {@code /github} — same chat, same grounding, same SSE
 * contract). Keeping it here means a fix to either one lands for both.
 *
 * <p>Two properties this class exists to guarantee:
 * <ul>
 *   <li><b>A terminal event always arrives within {@link #TIMEOUT_MS}.</b> The provider call runs
 *       on its own virtual thread and this thread only waits for it with a deadline
 *       ({@code future.get(timeout)}). Previously the 60s budget was checked only when a delta
 *       arrived, so a provider that accepted the connection and never answered (or a model that
 *       hung — then the silent multi-model fallback chain walked every candidate) left the browser
 *       on "Thinking…" for up to {@code chain × read-timeout}, i.e. minutes.</li>
 *   <li><b>The caller's chosen model is honoured.</b> {@code model} is passed through as the
 *       provider's override; the fallback chain behind it is the provider's own business.</li>
 * </ul>
 */
@ApplicationScoped
public class ChangelogChatService {

    private static final Logger LOG = Logger.getLogger(ChangelogChatService.class.getName());

    private static final ObjectMapper SSE_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Cap on concurrent chat streams — each occupies a worker thread for its whole duration.
    private static final Semaphore CONCURRENCY = new Semaphore(20);
    // Only the most recent turns are replayed — bounds prompt size/cost on a long-running chat.
    private static final int HISTORY_LIMIT = 10;
    // Hard wall-clock budget for one answer, from request receipt to the terminal SSE event.
    private static final long TIMEOUT_MS = 60_000;
    private static final String TIMEOUT_MESSAGE =
            "The model didn't answer in time. Try a different model or provider.";

    /** Chat work runs here rather than on the caller's thread so the deadline below can fire even
     * when the provider sends nothing at all. Virtual threads are daemon, so an idle executor
     * never keeps the JVM up. */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    AiProviderRegistry aiProviderRegistry;

    @Inject
    ChangelogCacheService cacheService;

    @Inject
    RawReleaseService rawReleaseService;

    /** Validates the request, loads its grounding from the DB on the request thread (Panache
     * needs the request-scoped context), then streams the answer. {@code model} is the user's
     * picker choice — null means "the provider's configured default". */
    public Response stream(String project, String repo, String audience, String provider, String model,
            String version, ChangelogChatRequest request) {
        if (!"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'qa' or 'business'.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new AiException("A question is required.");
        }
        String summaryText = cacheService.getCurrentText(project, repo, version, audience).orElse(null);
        if (summaryText == null) {
            throw new AiException("No " + audience + " changelog has been generated for this version yet.");
        }

        if (!CONCURRENCY.tryAcquire()) {
            return Response.status(429)
                    .entity(Map.of("error", "Chat is busy right now — try again shortly."))
                    .build();
        }
        // The streaming body releases the permit when it finishes; anything that fails between
        // here and handing the body to the container has to release it itself, or a single bad
        // version id would leak a slot from the concurrency cap forever.
        boolean handedOff = false;
        try {
            List<ChangeItem> items = rawReleaseService.findItems(project, repo, version);
            String systemPrompt = ChangelogChatPromptBuilder.build(repo, version, audience, summaryText, items);
            List<AiMessage> messages = buildChatMessages(systemPrompt, request);
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;

            StreamingOutput stream = output -> {
                var writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
                // Set once a delta has been forwarded to the client, so a timeout/interrupt can
                // report the answer as partial instead of pretending nothing was shown.
                AtomicBoolean emitted = new AtomicBoolean();
                try {
                    Future<AiResult> work = EXECUTOR.submit(() -> aiProviderRegistry.resolve(provider)
                            .chatStream(messages, model, delta -> onChatDelta(writer, deadline, delta, emitted)));
                    try {
                        AiResult result = work.get(
                                Math.max(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                        Map<String, Object> done = new LinkedHashMap<>();
                        done.put("model", result.getModel());
                        writeEvent(writer, "done", done);
                    } catch (TimeoutException e) {
                        work.cancel(true);
                        LOG.warning("Chat timed out after " + TIMEOUT_MS + "ms for " + project + "/" + repo
                                + " (provider " + provider + ", model " + model + ")");
                        writeChatError(writer, new AiStreamException(TIMEOUT_MESSAGE, null, emitted.get()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        work.cancel(true);
                        writeChatError(writer, new AiStreamException("The response was interrupted.", null, emitted.get()));
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        writeChatError(writer, cause instanceof Exception ex ? ex : e);
                    }
                } catch (Exception e) {
                    writeChatError(writer, e);
                } finally {
                    CONCURRENCY.release();
                }
            };
            Response response = Response.ok(stream).type(MediaType.SERVER_SENT_EVENTS)
                    .header("Cache-Control", "no-cache").build();
            handedOff = true;
            return response;
        } finally {
            if (!handedOff) {
                CONCURRENCY.release();
            }
        }
    }

    private List<AiMessage> buildChatMessages(String systemPrompt, ChangelogChatRequest request) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage("system", systemPrompt));
        List<ChatTurn> history = request.getHistory();
        if (history != null && !history.isEmpty()) {
            int from = Math.max(0, history.size() - HISTORY_LIMIT);
            for (ChatTurn turn : history.subList(from, history.size())) {
                messages.add(new AiMessage(turn.getRole(), turn.getContent()));
            }
        }
        messages.add(new AiMessage("user", request.getQuestion()));
        return messages;
    }

    /** Writes one streamed chunk, then checks {@link PrintWriter#checkError()} — a disconnected
     * client (Stop button, widget closed, tab closed, network drop) never throws from write()/
     * flush() itself (PrintWriter swallows the underlying IOException), so checkError() is the
     * only way this actually surfaces. Throwing here unwinds out of chatStream's
     * try-with-resources around the NIM connection, closing it too — no separate cleanup path.
     *
     * <p>Synchronized on the writer: the watchdog may already have moved on (and written a
     * terminal error) while a late chunk from the abandoned worker is still in flight, and two
     * threads interleaving partial SSE frames would corrupt the stream. */
    private void onChatDelta(PrintWriter writer, long deadline, String delta, AtomicBoolean emitted) {
        if (System.currentTimeMillis() > deadline) {
            throw new AiStreamException("This response took too long and was stopped.", null, true);
        }
        synchronized (writer) {
            try {
                writer.write("event: delta\ndata: " + SSE_MAPPER.writeValueAsString(delta) + "\n\n");
            } catch (Exception e) {
                throw new AiStreamException("Failed to encode response chunk.", e, true);
            }
            writer.flush();
            if (writer.checkError()) {
                throw new java.io.UncheckedIOException(new java.io.IOException("client disconnected"));
            }
            // Inside the lock, so a timeout racing this chunk can't read "nothing emitted yet"
            // and report a partial answer as if nothing had been shown.
            emitted.set(true);
        }
    }

    private void writeEvent(PrintWriter writer, String event, Map<String, Object> payload) {
        try {
            String json = SSE_MAPPER.writeValueAsString(payload);
            synchronized (writer) {
                writer.write("event: " + event + "\ndata: " + json + "\n\n");
                writer.flush();
            }
        } catch (Exception e) {
            LOG.warning("Failed to write chat " + event + " event: " + e.getMessage());
        }
    }

    /** Reports a chat failure as {@code event: error} if the connection is still alive; a client
     * that's already gone (see {@link #onChatDelta}) has nothing left to write to. */
    private void writeChatError(PrintWriter writer, Exception e) {
        if (e instanceof java.io.UncheckedIOException) {
            return;
        }
        boolean partial = e instanceof AiStreamException ase && ase.anyOutputEmitted();
        // AiException messages are already operator/user-facing ("Chat call failed (model X):
        // HTTP 429 — …", "all N models in the chain failed. Last error: …") — hiding them behind
        // a generic sentence left users staring at "Something went wrong" with no clue whether
        // the key was rejected, the model hung, or the provider rate-limited. Only truly
        // unknown exception types get the generic fallback.
        String message = e instanceof AiStreamException ase ? ase.getMessage()
                : e instanceof AiException ai && ai.getMessage() != null && !ai.getMessage().isBlank()
                        ? ai.getMessage()
                        : "Something went wrong generating a response.";
        writeEvent(writer, "error", Map.of("message", message, "partial", partial));
    }
}
