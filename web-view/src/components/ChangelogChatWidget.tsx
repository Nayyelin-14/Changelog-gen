import { useEffect, useRef, useState } from 'react';
import { ArrowDown, Loader2, MessageCircle, Send, Square, X } from 'lucide-react';

import { sendChangelogChatMessageStream } from '@/api/client';
import type { ChatTurn, PreviewAudience } from '@/api/types';
import { clearChatHistory, loadChatHistory, saveChatHistory, sweepExpiredChatHistory } from '@/lib/chatStorage';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Textarea } from '@/components/ui/textarea';

interface ChangelogChatWidgetProps {
  project: string;
  repo: string;
  audience: PreviewAudience;
  version: string;
}

/** Floating "ask about this changelog" chat, for QA/Business only (see CHATBOT-PLAN.md). The
 * backend re-fetches its own grounding text from cache, so this widget only ever needs to send
 * the question + prior turns — no changelog text is passed in as a prop. */
export function ChangelogChatWidget({ project, repo, audience, version }: ChangelogChatWidgetProps) {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatTurn[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [streamingText, setStreamingText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [modelUsed, setModelUsed] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [isAtBottom, setIsAtBottom] = useState(true);

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
    setMessages(loadChatHistory(project, repo, version, audience));
    setInput('');
    setSending(false);
    setStreamingText('');
    setError(null);
    setIsAtBottom(true);
    setModelUsed(null);
  }, [project, repo, version, audience]);

  // Abort on unmount (e.g. navigating away mid-answer) — one of the four disconnect triggers,
  // same mechanism as Stop/Close (see CHATBOT-PLAN.md's disconnect-handling table).
  useEffect(() => () => abortRef.current?.abort(), []);

  // Once per mount — deletes any stored conversation (for any version/audience, not just this
  // one) whose last activity is more than a day old. localStorage can't expire things on its
  // own, so this is what actually enforces "a chat sits for a day, then it's gone."
  useEffect(() => {
    sweepExpiredChatHistory();
  }, []);

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
          accumulated += text;
          setStreamingText(accumulated);
        },
        onDone: (model) => {
          finalizeAssistantTurn(accumulated, false);
          setSending(false);
          if (model) setModelUsed(model);
        },
        onError: (message, partial) => {
          if (partial && accumulated) {
            finalizeAssistantTurn(accumulated, true);
          } else {
            setError(message);
          }
          setSending(false);
        },
      },
      controller.signal,
    );
  }

  function handleStop() {
    abortRef.current?.abort();
    if (streamingText) finalizeAssistantTurn(streamingText, true);
    setSending(false);
  }

  function handleClose() {
    abortRef.current?.abort();
    if (streamingText) finalizeAssistantTurn(streamingText, true);
    setSending(false);
    setOpen(false);
  }

  // Explicit reset, so someone doesn't have to wait out the 6-hour expiry if they just want a
  // clean slate right now (e.g. the summary this conversation was grounded in has since changed).
  function handleNewConversation() {
    abortRef.current?.abort();
    clearChatHistory(project, repo, version, audience);
    setMessages([]);
    setInput('');
    setSending(false);
    setStreamingText('');
    setError(null);
    setIsAtBottom(true);
    setModelUsed(null);
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
    <Card className="fixed bottom-5 right-5 z-40 flex h-[34rem] w-80 max-h-[calc(100vh-3rem)] flex-col overflow-hidden py-0 shadow-xl sm:w-96">
      <CardHeader className="flex-row items-center justify-between gap-2 border-b border-border/60 py-3.5">
        <div className="min-w-0">
          <CardTitle className="text-base">Ask about this changelog</CardTitle>
          <CardDescription className="truncate text-xs">
            Scoped to <span className="font-semibold text-foreground">v{version}</span> — {audience}
            {modelUsed && <> · {modelUsed}</>}
          </CardDescription>
        </div>
        <Button onClick={handleClose} size="icon" variant="ghost" className="size-7 shrink-0" aria-label="Close">
          <X className="size-4" />
        </Button>
      </CardHeader>

      <div className="flex items-center justify-between gap-2 border-b border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-900/50 dark:bg-amber-950/30 dark:text-amber-300">
        <span>Conversations auto-clear after 24h of inactivity.</span>
        <button
          onClick={handleNewConversation}
          className="shrink-0 cursor-pointer font-semibold underline-offset-2 hover:underline"
        >
          New conversation
        </button>
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
          {error && <p className="text-sm text-destructive">{error}</p>}
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
