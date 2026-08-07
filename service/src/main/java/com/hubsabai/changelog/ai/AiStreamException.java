package com.hubsabai.changelog.ai;

/**
 * A streaming chat failure, carrying whether any real output had already reached the caller
 * before it happened. A failure with nothing emitted yet is safe to retry with the next
 * fallback model (see {@code NimAiProvider.callWithFallback}); one that happened after content
 * was already streamed out is not — the caller has to keep what was shown and report the cutoff
 * instead of silently starting over with a different model.
 */
public class AiStreamException extends AiException {
    private final boolean anyOutputEmitted;

    public AiStreamException(String message, Throwable cause, boolean anyOutputEmitted) {
        super(message, cause);
        this.anyOutputEmitted = anyOutputEmitted;
    }

    public boolean anyOutputEmitted() {
        return anyOutputEmitted;
    }
}
