import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import Avatar from '../components/Avatar';
import { useMessages } from '../contexts/MessagesContext';
import { ApiError } from '../api/client';
import type { MessageResponse, RecipientResponse } from '../types';

type Box = 'inbox' | 'sent';

/** What Compose opens with — empty for a new message, prefilled for a reply. */
type Draft = { to: string; subject: string };

const BLANK: Draft = { to: '', subject: '' };

/**
 * The inbox (docs/users/messaging.md).
 *
 * Message bodies and subjects are other users' text. They are rendered as JSX
 * children so React escapes them — never dangerouslySetInnerHTML here.
 */
export default function Messages() {
  const [box, setBox] = useState<Box>('inbox');
  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [selected, setSelected] = useState<MessageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState<Draft | null>(null);
  const { refresh: refreshUnread, markOneRead } = useMessages();

  const load = (which: Box) => {
    setLoading(true);
    api.messages.list(which)
      .then(setMessages)
      .catch(() => setMessages([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(box); setSelected(null); }, [box]);

  const open = async (message: MessageResponse) => {
    // Fetching the detail is what marks it read, so the list has to be told —
    // otherwise the row stays bold until a manual refresh.
    const full = await api.messages.get(message.id);
    setSelected(full);
    setDraft(null);
    if (!message.read && !message.outgoing) {
      setMessages(current => current.map(m => (m.id === message.id ? { ...m, read: true } : m)));
      // The GET above already marked it read server-side; tell the header now
      // rather than leaving the bell wrong until the next poll.
      markOneRead();
    }
  };

  const remove = async (id: number) => {
    await api.messages.remove(id);
    setSelected(current => (current?.id === id ? null : current));
    load(box);
    // Deleting an unread message removes it from the unread set too, and only
    // the server knows how many that leaves.
    refreshUnread();
  };

  /**
   * Reply prefills the counterparty and an "Re:" subject — but only when there
   * was a subject. Replying to a subjectless message stays subjectless rather
   * than becoming a bare "Re: " (docs/users/messaging.md §5.1).
   */
  const reply = (message: MessageResponse) => {
    const subject = message.subject
      ? (/^re:/i.test(message.subject) ? message.subject : `Re: ${message.subject}`)
      : '';
    setDraft({ to: message.counterpartyUsername, subject });
    setSelected(null);
  };

  return (
    <div>
      <div className="messages-head">
        <h1>Messages</h1>
        <button className="btn btn-primary" onClick={() => { setDraft(BLANK); setSelected(null); }}>
          New Message
        </button>
      </div>

      <div className="messages-tabs">
        {(['inbox', 'sent'] as Box[]).map(which => (
          <button
            key={which}
            className={box === which ? 'btn btn-primary' : 'btn'}
            onClick={() => { setBox(which); setDraft(null); }}
          >
            {which === 'inbox' ? 'Inbox' : 'Sent'}
          </button>
        ))}
      </div>

      {draft && (
        <Compose
          draft={draft}
          onCancel={() => setDraft(null)}
          onSent={() => { setDraft(null); setBox('sent'); load('sent'); }}
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
        <div className="spinner" style={{ margin: '0 auto' }} />
      ) : messages.length === 0 ? (
        <p style={{ color: 'var(--text-secondary)' }}>
          {box === 'inbox' ? 'No messages yet.' : 'You have not sent any messages.'}
        </p>
      ) : (
        <div className="message-list">
          {messages.map(message => (
            <button
              key={message.id}
              className={`message-row${!message.read && !message.outgoing ? ' unread' : ''}`}
              onClick={() => open(message)}
            >
              <Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={36} />
              <div className="message-row-main">
                <div className="message-row-top">
                  <span className="message-from">
                    {box === 'sent' ? 'To: ' : ''}{message.counterpartyDisplayName}
                  </span>
                  <span className="message-time">{formatWhen(message.createdAt)}</span>
                </div>
                {/* No subject falls back to a body preview, the way a mail client
                    does — never the literal string "(no subject)". */}
                <div className={`message-snippet${message.subject ? '' : ' no-subject'}`}>
                  {message.subject ?? message.preview}
                </div>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function MessageDetail({ message, onClose, onDelete, onReply }: {
  message: MessageResponse;
  onClose: () => void;
  onDelete: () => void;
  onReply: () => void;
}) {
  return (
    <div className="message-panel">
      <div className="message-detail-head">
        <Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={40} />
        <div className="message-detail-who">
          <strong>{message.outgoing ? 'To: ' : 'From: '}{message.counterpartyDisplayName}</strong>
          <span className="message-time">
            @{message.counterpartyUsername} · {new Date(message.createdAt).toLocaleString()}
          </span>
        </div>
        <div className="message-detail-actions">
          <button className="btn btn-primary" onClick={onReply}>Reply</button>
          <button className="btn" onClick={onClose}>Close</button>
          <button className="btn" style={{ color: 'var(--danger)' }} onClick={onDelete}>Delete</button>
        </div>
      </div>
      {message.subject && <h3 style={{ marginBottom: '0.5rem' }}>{message.subject}</h3>}
      <p className="message-body">{message.body}</p>
    </div>
  );
}

function Compose({ draft, onCancel, onSent }: {
  draft: Draft;
  onCancel: () => void;
  onSent: () => void;
}) {
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
      api.messages.searchRecipients(value).then(setResults).catch(() => setResults([]));
    }, 200);
  };

  const send = async () => {
    setSending(true);
    setError(null);
    try {
      await api.messages.send({ to: to.trim(), subject: subject.trim() || undefined, body });
      onSent();
    } catch (e) {
      // The server's message is the useful one here: "No such user", "You cannot
      // send a message to yourself", "not accepting messages".
      setError(e instanceof ApiError ? String(e.message).replace(/^\[\d+\]\s*/, '') : 'Could not send the message.');
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="message-panel">
      <h3 style={{ marginBottom: '0.75rem' }}>{draft.to ? 'Reply' : 'New Message'}</h3>

      <div className="recipient-box">
        <input
          className="compose-field"
          style={{ marginBottom: 0 }}
          placeholder="To — username or email"
          value={to}
          onChange={e => onToChange(e.target.value)}
        />
        {results.length > 0 && (
          <div className="recipient-menu">
            {results.map(recipient => (
              <button
                key={recipient.username}
                className="recipient-option"
                onClick={() => { setTo(recipient.username); setResults([]); }}
              >
                <Avatar src={recipient.avatarUrl} name={recipient.displayName} size={28} />
                <span>{recipient.displayName}</span>
                <span className="username">@{recipient.username}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <input
        className="compose-field"
        placeholder="Subject (optional)"
        value={subject}
        onChange={e => setSubject(e.target.value)}
        maxLength={200}
      />
      <textarea
        ref={bodyRef}
        className="compose-field"
        placeholder="Message"
        value={body}
        onChange={e => setBody(e.target.value)}
        maxLength={4000}
        rows={6}
      />

      {error && <p style={{ color: 'var(--danger)', marginBottom: '0.5rem' }}>{error}</p>}

      <div className="compose-actions">
        {/* Disabled while in flight: there is no idempotency key, so a double
            click would send twice (docs/users/messaging.md §7.2). */}
        <button
          className="btn btn-primary"
          disabled={sending || !to.trim() || !body.trim()}
          onClick={send}
        >
          {sending ? 'Sending…' : 'Send'}
        </button>
        <button className="btn" onClick={onCancel}>Cancel</button>
      </div>
    </div>
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
