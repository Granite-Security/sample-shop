import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../api';
import { Avatar } from '../components/Avatar';
import { useMessages } from '../messages';
import type { MessageResponse, RecipientResponse } from '../types';

type Box = 'inbox' | 'sent';

/** What the composer opens with — empty for a new message, prefilled for a reply. */
type Draft = { to: string; subject: string };

const BLANK: Draft = { to: '', subject: '' };

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

const buttonStyle =
  'px-6 py-3 text-xs uppercase tracking-[0.14em] transition-colors duration-300 disabled:opacity-50';

// Reply/Close/Delete sit on one row. At px-6 the three of them are wider than a
// 390px card and Delete falls off the edge, so they tighten up on small screens
// and only take the roomier padding once there is room for it.
const detailButtonStyle =
  'min-w-0 flex-1 px-3 py-3 text-[11px] uppercase tracking-[0.12em] transition-colors duration-300 sm:flex-none sm:px-6 sm:text-xs sm:tracking-[0.14em]';

/**
 * The inbox (docs/users/messaging.md). Same API as ui-shop's Messages page,
 * dressed in this storefront's vocabulary.
 *
 * Message bodies and subjects are other users' text. They are rendered as JSX
 * children so React escapes them — never dangerouslySetInnerHTML here.
 */
export function MessagesPage() {
  const [box, setBox] = useState<Box>('inbox');
  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [selected, setSelected] = useState<MessageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { refresh: refreshUnread, markOneRead } = useMessages();

  const load = (which: Box) => {
    setLoading(true);
    api.getMessages(which)
      .then(setMessages)
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load(box);
    setSelected(null);
  }, [box]);

  const open = async (message: MessageResponse) => {
    // Fetching the detail is what marks it read, so the list has to be told —
    // otherwise the row stays highlighted until a manual refresh.
    const full = await api.getMessage(message.id);
    setSelected(full);
    setDraft(null);
    if (!message.read && !message.outgoing) {
      setMessages((current) => current.map((m) => (m.id === message.id ? { ...m, read: true } : m)));
      markOneRead();
    }
  };

  const remove = async (id: number) => {
    await api.deleteMessage(id);
    setSelected((current) => (current?.id === id ? null : current));
    load(box);
    // Deleting an unread message removes it from the unread set too, and only
    // the server knows how many that leaves.
    refreshUnread();
  };

  /**
   * Reply prefills the counterparty and an "Re:" subject — but only when there
   * was one. Replying to a subjectless message stays subjectless rather than
   * becoming a bare "Re: " (docs/users/messaging.md §5.1).
   */
  const reply = (message: MessageResponse) => {
    // A contact-form note from someone who was not signed in has no account to
    // reply into (docs/users/messaging.md §11). MessageDetail offers a mailto
    // for those instead of this, so there is nothing to do here.
    if (!message.counterpartyUsername) return;
    const subject = message.subject
      ? /^re:/i.test(message.subject)
        ? message.subject
        : `Re: ${message.subject}`
      : '';
    setDraft({ to: message.counterpartyUsername, subject });
    setSelected(null);
  };

  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-4">
        <h1 className="font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">Messages</h1>
        <button
          className={`${buttonStyle} bg-cocoa text-ivory hover:bg-espresso`}
          onClick={() => {
            setDraft(BLANK);
            setSelected(null);
          }}
        >
          New Message
        </button>
      </div>

      <div className="mt-8 flex gap-2">
        {(['inbox', 'sent'] as Box[]).map((which) => (
          <button
            key={which}
            onClick={() => {
              setBox(which);
              setDraft(null);
            }}
            className={`px-5 py-2.5 text-xs uppercase tracking-[0.14em] transition-colors duration-300 ${
              box === which ? 'bg-cocoa text-ivory' : 'text-cocoa hover:bg-cocoa/10'
            }`}
          >
            {which === 'inbox' ? 'Inbox' : 'Sent'}
          </button>
        ))}
      </div>

      {error && (
        <p role="status" className="mt-6 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
          {error}
        </p>
      )}

      {draft && (
        <Compose
          draft={draft}
          onCancel={() => setDraft(null)}
          onSent={() => {
            setDraft(null);
            setBox('sent');
            load('sent');
          }}
        />
      )}

      {selected && !draft && (
        <MessageDetail
          message={selected}
          onClose={() => setSelected(null)}
          onDelete={() => remove(selected.id)}
          onReply={() => reply(selected)}
        />
      )}

      {loading ? (
        <p className="mt-10 text-sm text-cocoa/60">Loading…</p>
      ) : messages.length === 0 ? (
        <p className="mt-10 text-sm text-cocoa/60">
          {box === 'inbox' ? 'No messages yet.' : 'You have not sent any messages.'}
        </p>
      ) : (
        <ul className="mt-8 divide-y divide-cocoa/10 border-y border-cocoa/10">
          {messages.map((message) => (
            <li key={message.id}>
              <button
                onClick={() => open(message)}
                className={`flex w-full items-start gap-4 px-1 py-5 text-left transition-colors duration-300 hover:bg-cocoa/5 ${
                  !message.read && !message.outgoing ? 'bg-gold/10' : ''
                }`}
              >
                <Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={38} />
                <span className="min-w-0 flex-1">
                  <span className="flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:justify-between sm:gap-3">
                    <span
                      className={`block max-w-full truncate text-sm text-cocoa ${
                        !message.read && !message.outgoing ? 'font-semibold' : ''
                      }`}
                    >
                      {box === 'sent' ? 'To: ' : ''}
                      {message.counterpartyDisplayName}
                    </span>
                    <span className="shrink-0 text-xs uppercase tracking-[0.14em] text-cocoa/50">
                      {formatWhen(message.createdAt)}
                    </span>
                  </span>
                  {/* No subject falls back to a body preview, the way a mail
                      client does — never the literal string "(no subject)". */}
                  <span
                    className={`mt-1 block truncate text-sm ${
                      message.subject ? 'text-cocoa/70' : 'italic text-cocoa/50'
                    }`}
                  >
                    {message.subject ?? message.preview}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function MessageDetail({
  message,
  onClose,
  onDelete,
  onReply,
}: {
  message: MessageResponse;
  onClose: () => void;
  onDelete: () => void;
  onReply: () => void;
}) {
  // Sent from the contact form by someone who was not signed in: no profile, no
  // inbox, no @handle. Their email is the only way back to them (§11).
  const guest = !message.counterpartyUsername;

  return (
    <article className="mt-8 border border-cocoa/15 bg-white/60 p-6">
      <div className="flex flex-wrap items-center gap-4">
        <Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={42} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm text-cocoa">
            {message.outgoing ? 'To: ' : 'From: '}
            <span className="font-semibold">{message.counterpartyDisplayName}</span>
          </p>
          <p className="text-xs text-cocoa/50">
            {guest
              ? `${message.senderEmail ?? 'no address given'} · via the contact form`
              : `@${message.counterpartyUsername}`}
            {' · '}
            {new Date(message.createdAt).toLocaleString()}
          </p>
        </div>
        <div className="flex w-full gap-2 sm:w-auto">
          {guest ? (
            // An email client, not a compose box — replying in-app would write a
            // row into an inbox that does not exist. Nothing to reply to at all
            // when they left no address, so the button goes away rather than
            // opening a blank one.
            message.senderEmail && (
              <a
                className={`${detailButtonStyle} bg-cocoa text-center text-ivory hover:bg-espresso`}
                href={`mailto:${encodeURIComponent(message.senderEmail)}?subject=${encodeURIComponent(
                  message.subject ? `Re: ${message.subject}` : 'Your message',
                )}`}
              >
                Reply by email
              </a>
            )
          ) : (
            <button className={`${detailButtonStyle} bg-cocoa text-ivory hover:bg-espresso`} onClick={onReply}>
              Reply
            </button>
          )}
          <button
            className={`${detailButtonStyle} border border-cocoa/20 text-cocoa hover:bg-cocoa/10`}
            onClick={onClose}
          >
            Close
          </button>
          <button
            className={`${detailButtonStyle} border border-terracotta/40 text-terracotta hover:bg-terracotta/10`}
            onClick={onDelete}
          >
            Delete
          </button>
        </div>
      </div>
      {message.subject && <h2 className="mt-6 font-display text-[24px] text-cocoa">{message.subject}</h2>}
      {/* whitespace-pre-wrap keeps the sender's line breaks without any markup;
          break-words stops a pasted URL forcing a sideways scroll. */}
      <p className="mt-4 whitespace-pre-wrap break-words text-sm leading-relaxed text-cocoa/80">{message.body}</p>
    </article>
  );
}

function Compose({ draft, onCancel, onSent }: { draft: Draft; onCancel: () => void; onSent: () => void }) {
  const [to, setTo] = useState(draft.to);
  const [subject, setSubject] = useState(draft.subject);
  const [body, setBody] = useState('');
  const [results, setResults] = useState<RecipientResponse[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const debounce = useRef<number | undefined>(undefined);
  const bodyRef = useRef<HTMLTextAreaElement>(null);

  // A reply already knows its recipient, so put the cursor where the user
  // actually has something to type.
  useEffect(() => {
    if (draft.to) bodyRef.current?.focus();
  }, [draft.to]);

  const onToChange = (value: string) => {
    setTo(value);
    setError(null);
    window.clearTimeout(debounce.current);
    // The server ignores queries under two characters; not firing them at all
    // keeps the picker from flashing empty on the first keystroke.
    if (value.trim().length < 2) {
      setResults([]);
      return;
    }
    debounce.current = window.setTimeout(() => {
      api.searchRecipients(value).then(setResults).catch(() => setResults([]));
    }, 200);
  };

  const send = async () => {
    setSending(true);
    setError(null);
    try {
      await api.sendMessage({ to: to.trim(), subject: subject.trim() || undefined, body });
      onSent();
    } catch (e) {
      // The server's message is the useful one here: "No such user", "You
      // cannot send a message to yourself", "not accepting messages".
      setError(
        e instanceof ApiError
          ? String(e.message).replace(/^\[\d+\]\s*/, '')
          : 'Could not send the message.',
      );
    } finally {
      setSending(false);
    }
  };

  return (
    <section className="mt-8 border border-cocoa/15 bg-white/60 p-6">
      <h2 className="font-display text-[24px] text-cocoa">{draft.to ? 'Reply' : 'New Message'}</h2>

      <div className="relative mt-6">
        <input
          aria-label="Recipient"
          placeholder="To — username or email"
          value={to}
          onChange={(e) => onToChange(e.target.value)}
          className={inputStyle}
        />
        {results.length > 0 && (
          <ul className="absolute z-20 max-h-56 w-full overflow-y-auto border border-cocoa/20 bg-ivory shadow-xl shadow-espresso/10">
            {results.map((recipient) => (
              <li key={recipient.username}>
                <button
                  onClick={() => {
                    setTo(recipient.username);
                    setResults([]);
                  }}
                  className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-cocoa/10"
                >
                  <Avatar src={recipient.avatarUrl} name={recipient.displayName} size={28} />
                  <span className="text-sm text-cocoa">{recipient.displayName}</span>
                  <span className="text-xs text-cocoa/50">@{recipient.username}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <input
        aria-label="Subject"
        placeholder="Subject (optional)"
        value={subject}
        maxLength={200}
        onChange={(e) => setSubject(e.target.value)}
        className={`${inputStyle} mt-3`}
      />
      <textarea
        ref={bodyRef}
        aria-label="Message"
        placeholder="Message"
        value={body}
        maxLength={4000}
        rows={6}
        onChange={(e) => setBody(e.target.value)}
        className={`${inputStyle} mt-3 resize-y`}
      />

      {error && (
        <p role="status" className="mt-4 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
          {error}
        </p>
      )}

      <div className="mt-5 flex flex-wrap gap-3">
        {/* Disabled while in flight: there is no idempotency key, so a double
            click would send twice (docs/users/messaging.md §7.2). */}
        <button
          className={`${buttonStyle} bg-cocoa text-ivory hover:bg-espresso`}
          disabled={sending || !to.trim() || !body.trim()}
          onClick={send}
        >
          {sending ? 'Sending…' : 'Send'}
        </button>
        <button className={`${buttonStyle} border border-cocoa/20 text-cocoa hover:bg-cocoa/10`} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </section>
  );
}

/** Today shows a time, this year a date, older a date with the year. */
function formatWhen(iso: string): string {
  const when = new Date(iso);
  const now = new Date();
  if (when.toDateString() === now.toDateString()) {
    return when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  return when.getFullYear() === now.getFullYear()
    ? when.toLocaleDateString([], { day: 'numeric', month: 'short' })
    : when.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
}
