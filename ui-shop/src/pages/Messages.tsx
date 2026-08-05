import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import Avatar from '../components/Avatar';
import { ApiError } from '../api/client';
import type { MessageResponse, RecipientResponse } from '../types';

type Box = 'inbox' | 'sent';

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
  const [composing, setComposing] = useState(false);

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
    if (!message.read && !message.outgoing) {
      setMessages(current => current.map(m => (m.id === message.id ? { ...m, read: true } : m)));
    }
  };

  const remove = async (id: number) => {
    await api.messages.remove(id);
    setSelected(current => (current?.id === id ? null : current));
    load(box);
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ margin: 0 }}>Messages</h1>
        <button className="btn btn-primary" onClick={() => { setComposing(true); setSelected(null); }}>
          New Message
        </button>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        {(['inbox', 'sent'] as Box[]).map(which => (
          <button
            key={which}
            className={box === which ? 'btn btn-primary' : 'btn'}
            onClick={() => { setBox(which); setComposing(false); }}
          >
            {which === 'inbox' ? 'Inbox' : 'Sent'}
          </button>
        ))}
      </div>

      {composing && (
        <Compose
          onCancel={() => setComposing(false)}
          onSent={() => { setComposing(false); setBox('sent'); load('sent'); }}
        />
      )}

      {selected && !composing && (
        <MessageDetail
          message={selected}
          onClose={() => setSelected(null)}
          onDelete={() => remove(selected.id)}
        />
      )}

      {loading ? (
        <div className="spinner" style={{ margin: '0 auto' }} />
      ) : messages.length === 0 ? (
        <p style={{ color: 'var(--text-secondary)' }}>
          {box === 'inbox' ? 'No messages yet.' : 'You have not sent any messages.'}
        </p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {messages.map(message => (
            <button
              key={message.id}
              onClick={() => open(message)}
              style={{
                display: 'flex',
                gap: 12,
                alignItems: 'center',
                textAlign: 'left',
                border: '1px solid var(--border)',
                borderRadius: 8,
                padding: 12,
                background: message.read || message.outgoing ? 'transparent' : 'var(--bg-secondary)',
                cursor: 'pointer',
                width: '100%',
              }}
            >
              <Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={36} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                  <strong style={{ fontWeight: message.read || message.outgoing ? 500 : 700 }}>
                    {box === 'sent' ? 'To: ' : ''}{message.counterpartyDisplayName}
                  </strong>
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', flexShrink: 0 }}>
                    {new Date(message.createdAt).toLocaleString()}
                  </span>
                </div>
                {/* No subject falls back to a body preview, the way a mail client
                    does — never the literal string "(no subject)". */}
                <div style={{
                  color: message.subject ? 'var(--text)' : 'var(--text-secondary)',
                  fontStyle: message.subject ? 'normal' : 'italic',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}>
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

function MessageDetail({ message, onClose, onDelete }: {
  message: MessageResponse;
  onClose: () => void;
  onDelete: () => void;
}) {
  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 16, marginBottom: 16 }}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12 }}>
        <Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={40} />
        <div style={{ flex: 1 }}>
          <strong>{message.outgoing ? 'To: ' : 'From: '}{message.counterpartyDisplayName}</strong>
          <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
            @{message.counterpartyUsername} · {new Date(message.createdAt).toLocaleString()}
          </div>
        </div>
        <button className="btn" onClick={onClose}>Close</button>
        <button className="btn" style={{ color: 'var(--danger)' }} onClick={onDelete}>Delete</button>
      </div>
      {message.subject && <h3 style={{ marginBottom: 8 }}>{message.subject}</h3>}
      {/* whiteSpace preserves the sender's line breaks without any markup. */}
      <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{message.body}</p>
    </div>
  );
}

function Compose({ onCancel, onSent }: { onCancel: () => void; onSent: () => void }) {
  const [to, setTo] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [results, setResults] = useState<RecipientResponse[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const debounce = useRef<number | undefined>(undefined);

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
    <div style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 16, marginBottom: 16 }}>
      <h3 style={{ marginBottom: 12 }}>New Message</h3>

      <div style={{ position: 'relative', marginBottom: 8 }}>
        <input
          placeholder="To — username or email"
          value={to}
          onChange={e => onToChange(e.target.value)}
          style={{ width: '100%' }}
        />
        {results.length > 0 && (
          <div style={{
            position: 'absolute',
            zIndex: 10,
            width: '100%',
            background: 'var(--bg)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            maxHeight: 220,
            overflowY: 'auto',
          }}>
            {results.map(recipient => (
              <button
                key={recipient.username}
                onClick={() => { setTo(recipient.username); setResults([]); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                  padding: 8, background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left',
                }}
              >
                <Avatar src={recipient.avatarUrl} name={recipient.displayName} size={28} />
                <span>{recipient.displayName}</span>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>@{recipient.username}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <input
        placeholder="Subject (optional)"
        value={subject}
        onChange={e => setSubject(e.target.value)}
        maxLength={200}
        style={{ width: '100%', marginBottom: 8 }}
      />
      <textarea
        placeholder="Message"
        value={body}
        onChange={e => setBody(e.target.value)}
        maxLength={4000}
        rows={6}
        style={{ width: '100%', marginBottom: 8 }}
      />

      {error && <p style={{ color: 'var(--danger)', marginBottom: 8 }}>{error}</p>}

      <div style={{ display: 'flex', gap: 8 }}>
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
