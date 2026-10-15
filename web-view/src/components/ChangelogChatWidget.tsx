import { useEffect, useRef, useState } from 'react';
import { ArrowDown, Loader2, MessageCircle, Send, Square, Trash2, X } from 'lucide-react';

import { sendChangelogChatMessageStream } from '@/api/client';
import type { ChatTurn, PreviewAudience } from '@/api/types';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { clearChatHistory, loadChatHistory, saveChatHistory, sweepExpiredChatHistory } from '@/lib/chatStorage';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';

interface ChangelogChatWidgetProps {
  project: string;
  repo: string;
  audience: PreviewAudience;
  version: string;
}

/** Curated chat models, fastest first — latency-measured from the production VM itself
 * (Azure egress; full round-trip on a tiny prompt). Groq is deliberately absent: Groq/Cloudflare
 * blocks Azure datacenter IPs entirely (403 on every model, any key), so every Groq entry would
 * 403 in prod no matter how fast it is from a residential IP. ZAI glm is also absent: 50s to
 * first token on a reasoning model. Provider+model travel together as one choice. */
const CHAT_MODEL_OPTIONS = [
  { provider: 'openrouter', model: 'nvidia/nemotron-3.5-lightning:free', label: 'Nemotron 3.5 Lightning', tag: 'Fastest' },
  { provider: 'nvidia', model: 'nvidia/nemotron-3.5-lightning-30b-a3b', label: 'Nemotron 3.5 Lightning (NVIDIA)', tag: 'Fast' },
  { provider: 'nvidia', model: 'openai/gpt-oss-20b', label: 'GPT-OSS 20B', tag: 'Smart' },
  { provider: 'openrouter', model: 'nvidia/nemotron-3-super-120b-a12b:free', label: 'Nemotron 3 Super 120B', tag: null },
  { provider: 'nvidia', model: 'nvidia/nemotron-3-super-120b-a12b', label: 'Nemotron 3 Super 120B (NVIDIA)', tag: null },
  { provider: 'openrouter', model: 'dots-studio/dots-3-note-preview:free', label: 'DOTS-3 Note', tag: null },
  { provider: 'nvidia', model: 'meta/llama-3.2-11b-vision-instruct', label: 'Llama 3.2 11B Vision', tag: null },
] as const;

const DEFAULT_CHAT_MODEL = 'openrouter:nvidia/nemotron-3.5-lightning:free';
const CHAT_MODEL_STORAGE_KEY = 'changelog-chat-model';

function chatModelChoice(stored: string): { provider: string; model: string; key: string } {
  const fromList = CHAT_MODEL_OPTIONS.find((o) => `${o.provider}:${o.model}` === stored);
  if (fromList) return { provider: fromList.provider, model: fromList.model, key: stored };
  return { provider: 'openrouter', model: 'nvidia/nemotron-3.5-lightning:free', key: DEFAULT_CHAT_MODEL };
}

/** Backstop for a connection that went dead without telling us — the server enforces its own
 * 60s budget and reports timeouts as SSE errors, so this only has to catch what that budget
 * can't see: a connection that died without a FIN, a proxy that swallowed the stream, a
 * server that never got the request. The timer resets on every delta, so a slow-but-alive
 * stream is never killed here — only 75s of complete silence ends the wait. */
const CHAT_STALL_MS = 75_000;

/** Floating "ask about this changelog" chat, for QA/Business only (see CHATBOT-PLAN.md). The
 * backend re-fetches its own grounding text from cache, so this widget only ever needs to send
 * the question + prior turns — no changelog text is passed in as a prop.
 *
 * Model selection is a single curated list of the fastest working models ({@link CHAT_MODEL_OPTIONS})
 * — chat is latency-sensitive, so there's no provider picker and no full catalog; just the
 * vetted-fast set, each carrying its provider with it. The choice travels with the request. */
export function ChangelogChatWidget({ project, repo, audience, version }: ChangelogChatWidgetProps) {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatTurn[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [streamingText, setStreamingText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [modelUsed, setModelUsed] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const stallRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const streamedRef = useRef('');
  // False once this send is over (done, error, stop, or stall) — a chunk still in flight from an
  // abandoned stream must not resurrect the streaming bubble after the answer was finalized.
  const activeRef = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [isAtBottom, setIsAtBottom] = useState(true);
  // Single curated fast-model choice — persists across sessions, defaults to the balanced
  // Groq Qwen if unset or if a stored entry no longer exists in the curated list.
  const [chatModel, setChatModel] = useState<string>(() => {
    if (typeof window === 'undefined') return DEFAULT_CHAT_MODEL;
    return localStorage.getItem(CHAT_MODEL_STORAGE_KEY) ?? DEFAULT_CHAT_MODEL;
  });
  const choice = chatModelChoice(chatModel);
  const [confirmDelete, setConfirmDelete] = useState(false);

  function clearStall() {
    if (stallRef.current) {
      clearTimeout(stallRef.current);
      stallRef.current = null;
    }
  }

  /** (Re)arms the silence watchdog. Called on send and on every delta — a stream that keeps
   * talking is alive, no matter how slow; only complete silence for CHAT_STALL_MS is a hang. */
  function armStall() {
    clearStall();
    stallRef.current = setTimeout(() => {
      stallRef.current = null;
      // The backend's own 60s budget has long expired by now, so this is a connection nothing
      // is going to answer — give up rather than leave "Thinking…" on screen forever. Anything
      // already streamed is kept as a cut-off answer, not thrown away.
      activeRef.current = false;
      if (streamedRef.current) {
        finalizeAssistantTurn(streamedRef.current, true);
      } else {
        setError('The model did not answer in time. Try a different model or provider.');
      }
      abortRef.current?.abort();
      setSending(false);
      setStreamingText('');
    }, CHAT_STALL_MS);
  }

  function scrollToBottom() {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    setIsAtBottom(true);
  }

  function handleScroll() {
    const el = scrollRef.current;
    if (!el) return;
    // Small threshold, not an exact 0 — "close enough to the bottom" should still count as
    // following along, not require pixel-perfect scroll position.
    setIsAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 40);
  }

  // Only auto-follows new content while already at the bottom — someone who scrolled up to
  // reread something earlier shouldn't get yanked back down every time a chunk streams in. If
  // they're not at the bottom, the jump-to-latest button (below) is how they get back instead.
  useEffect(() => {
    if (isAtBottom) scrollToBottom();
  }, [messages, streamingText, isAtBottom]);

  // Swap threads on version/audience change — this is a different conversation, not a reset of
  // the one being left (it's still sitting in storage under its own key).
  useEffect(() => {
    abortRef.current?.abort();
    clearStall();
    activeRef.current = false;
    setMessages(loadChatHistory(project, repo, version, audience));
    setInput('');
    setSending(false);
    setStreamingText('');
    setError(null);
    setIsAtBottom(true);
    setModelUsed(null);
    streamedRef.current = '';
  }, [project, repo, version, audience]);

  // Abort on unmount (e.g. navigating away mid-answer) — one of the four disconnect triggers,
  // same mechanism as Stop/Close (see CHATBOT-PLAN.md's disconnect-handling table).
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
      clearStall();
    };
  }, []);

  // Once per mount — deletes any stored conversation (for any version/audience, not just this
  // one) whose last activity is more than a day old. localStorage can't expire things on its
  // own, so this is what actually enforces "a chat sits for a day, then it's gone."
  useEffect(() => {
    sweepExpiredChatHistory();
  }, []);

  // Persist the curated model choice across sessions.
  useEffect(() => {
    localStorage.setItem(CHAT_MODEL_STORAGE_KEY, chatModel);
  }, [chatModel]);

  // Always appends onto the LATEST state via the updater form — onDone/onError fire
  // asynchronously after the user's question was already appended, so building the next array
  // from the `messages` variable captured back when handleSend ran would silently drop that
  // question (the exact "my message disappeared after the answer came back" bug).
  function appendTurn(role: ChatTurn['role'], content: string) {
    setMessages((prev) => {
      const next = [...prev, { role, content, at: new Date().toISOString() }];
      saveChatHistory(project, repo, version, audience, next);
      return next;
    });
  }

  function finalizeAssistantTurn(text: string, cutOff: boolean) {
    const content = cutOff ? `${text}\n\n_(response was cut off)_` : text;
    appendTurn('assistant', content);
    setStreamingText('');
  }

  function handleSend() {
    const question = input.trim();
    if (!question || sending) return;

    // Unconditional — has to run every time send fires, not just when the UI shows a request as
    // active: a double-click or an Enter-press while the first request is still spinning up
    // would otherwise start a second reader racing the first over this same widget state.
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    // Snapshot of prior turns for the API call — read once, synchronously, from this render (no
    // updater form needed here, unlike appendTurn). Strip `at`: it's local display state only,
    // the backend only ever needs role/content to build AI context.
    const history = messages.map(({ role, content }) => ({ role, content }));
    appendTurn('user', question);
    setInput('');
    setError(null);
    setSending(true);
    setStreamingText('');
    streamedRef.current = '';
    activeRef.current = true;
    armStall();

    let accumulated = '';
    sendChangelogChatMessageStream(
      project,
      repo,
      audience,
      version,
      question,
      history,
      {
        onDelta: (text) => {
          if (!activeRef.current) return;
          accumulated += text;
          streamedRef.current = accumulated;
          setStreamingText(accumulated);
          // Still alive — restart the silence clock, a slow model isn't a hung one.
          armStall();
        },
        onDone: (model) => {
          activeRef.current = false;
          clearStall();
          finalizeAssistantTurn(accumulated, false);
          setSending(false);
          if (model) setModelUsed(model);
        },
        onError: (message, partial) => {
          activeRef.current = false;
          clearStall();
          if (partial && accumulated) {
            finalizeAssistantTurn(accumulated, true);
          } else {
            setError(message);
          }
          setSending(false);
        },
      },
      controller.signal,
      choice.provider,
      choice.model,
    );
  }

  function handleStop() {
    activeRef.current = false;
    clearStall();
    abortRef.current?.abort();
    if (streamingText) finalizeAssistantTurn(streamingText, true);
    setSending(false);
  }

  function handleClose() {
    activeRef.current = false;
    clearStall();
    abortRef.current?.abort();
    if (streamingText) finalizeAssistantTurn(streamingText, true);
    setSending(false);
    setOpen(false);
  }

  // Explicit wipe of this conversation (in-memory + localStorage), gated behind a confirm
  // modal so a mis-click can't destroy the thread. The 24h auto-sweep still runs regardless.
  function handleDeleteConversation() {
    activeRef.current = false;
    clearStall();
    abortRef.current?.abort();
    clearChatHistory(project, repo, version, audience);
    setMessages([]);
    setInput('');
    setSending(false);
    setStreamingText('');
    setError(null);
    setIsAtBottom(true);
    setModelUsed(null);
    streamedRef.current = '';
    setConfirmDelete(false);
  }

  if (!open) {
    return (
      <Button
        onClick={() => setOpen(true)}
        size="icon"
        className="fixed bottom-5 right-5 z-40 size-14 rounded-full shadow-lg"
        aria-label="Ask about this changelog"
      >
        <MessageCircle className="size-6" />
      </Button>
    );
  }

  return (
    <>
      <Card className="fixed bottom-5 right-5 z-40 flex h-[42rem] max-h-[calc(100vh-3rem)] w-[24rem] flex-col overflow-hidden py-0 shadow-xl sm:w-[28rem]">
        <CardHeader className="flex-row items-center justify-between gap-2 border-b border-border/60 py-3.5">
          <div className="min-w-0">
            <CardTitle className="text-base">Ask about this changelog</CardTitle>
            <CardDescription className="truncate text-xs">
              Scoped to <span className="font-semibold text-foreground">v{version}</span> — {audience}
              {modelUsed && <> · {modelUsed}</>}
            </CardDescription>
          </div>
          <div className="flex shrink-0 items-center gap-0.5">
            <Button
              onClick={() => setConfirmDelete(true)}
              size="icon"
              variant="ghost"
              className="size-7 text-destructive hover:text-destructive"
              aria-label="Delete conversation"
              title="Delete this conversation"
            >
              <Trash2 className="size-4" />
            </Button>
            <Button onClick={handleClose} size="icon" variant="ghost" className="size-7" aria-label="Close">
              <X className="size-4" />
            </Button>
          </div>
        </CardHeader>

        {/* Curated fast-model picker — one flat list of vetted models (fastest first), no
            provider selector. Chat is latency-sensitive; the full catalog lives on the
            generate page, not here. */}
        <div className="flex min-w-0 items-center gap-1.5 border-b border-border/60 px-3 py-2">
          <Select value={chatModel} onValueChange={setChatModel} disabled={sending}>
            <SelectTrigger className="h-7 w-full min-w-0 gap-1.5 overflow-hidden text-xs font-medium sm:w-full [&_span]:min-w-0 [&_span]:truncate">
              <SelectValue placeholder="Model" />
            </SelectTrigger>
            <SelectContent side="top" align="start" className="max-h-72">
              {CHAT_MODEL_OPTIONS.map((o) => (
                <SelectItem key={`${o.provider}:${o.model}`} value={`${o.provider}:${o.model}`} className="pr-8 text-xs">
                  <span className="flex min-w-0 items-center gap-2">
                    <span className="min-w-0 truncate">{o.label}</span>
                    {o.tag && (
                      <span className="shrink-0 rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                        {o.tag}
                      </span>
                    )}
                  </span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

      <div className="relative flex-1 overflow-hidden">
        <CardContent ref={scrollRef} onScroll={handleScroll} className="flex h-full flex-col gap-3 overflow-y-auto p-4">
          {messages.length === 0 && !streamingText && (
            <p className="text-sm text-muted-foreground">
              Ask a question about this version — e.g. "How did login change, and how do I check it?"
            </p>
          )}
          {messages.map((turn, i) => (
            <ChatBubble key={i} turn={turn} />
          ))}
          {streamingText && <ChatBubble turn={{ role: 'assistant', content: streamingText }} />}
          {sending && !streamingText && (
            <div className="flex justify-start">
              <div className="flex items-center gap-1.5 rounded-lg bg-muted px-3.5 py-2.5 text-sm text-muted-foreground">
                <Loader2 className="size-3.5 animate-spin" />
                Thinking…
              </div>
            </div>
          )}
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
        </CardContent>

        {!isAtBottom && (
          <Button
            onClick={scrollToBottom}
            size="icon"
            className="absolute bottom-3 right-3 size-10 rounded-full border-2 border-background shadow-lg"
            aria-label="Scroll to latest message"
          >
            <ArrowDown className="size-4.5" />
          </Button>
        )}
      </div>

      <div className="flex items-end gap-2 border-t border-border/60 p-3.5">
        <Textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            // Plain Enter sends; Shift+Enter falls through to the textarea's own default
            // behavior and inserts a newline instead.
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="Ask a question… (Shift+Enter for a new line)"
          rows={3}
          disabled={sending}
          className="min-h-0 flex-1 resize-none text-sm"
        />
        {sending ? (
          <Button onClick={handleStop} size="icon" variant="outline" className="size-10 shrink-0" aria-label="Stop">
            <Square className="size-4" />
          </Button>
        ) : (
          <Button onClick={handleSend} size="icon" disabled={!input.trim()} className="size-10 shrink-0" aria-label="Send">
            <Send className="size-4" />
          </Button>
        )}
      </div>
      </Card>

      {/* Destructive-action gate — the wipe is instant and irreversible (in-memory + stored
          history), so it gets a confirm step like any other delete. */}
      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Delete this conversation?</DialogTitle>
            <DialogDescription>
              This removes all messages in this chat with <span className="font-semibold text-foreground">v{version}</span> ({audience}) — it can't be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmDelete(false)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={handleDeleteConversation}>
              <Trash2 className="size-4" />
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function ChatBubble({ turn }: { turn: ChatTurn }) {
  const isUser = turn.role === 'user';
  return (
    <div className={`flex flex-col ${isUser ? 'items-end' : 'items-start'}`}>
      <div
        className={`max-w-[85%] whitespace-pre-wrap rounded-lg px-3.5 py-2.5 text-sm ${
          isUser ? 'bg-primary text-primary-foreground' : 'bg-muted text-foreground'
        }`}
      >
        {turn.content}
      </div>
      {turn.at && <span className="mt-0.5 px-0.5 text-[11px] text-muted-foreground/70">{formatTime(turn.at)}</span>}
    </div>
  );
}

function formatTime(at: string): string {
  try {
    return new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '';
  }
}
