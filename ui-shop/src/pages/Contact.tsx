import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { ApiError } from '../api/client';
import { useAuth } from '../auth';

/**
 * The public contact form (docs/users/messaging.md §11).
 *
 * Reachable signed out — that is the point — and it lands in the manager's ordinary
 * inbox. When there is a session the "From" field shows the username and is not
 * editable, because the server ignores it either way: the sender is the JWT subject
 * whenever a token is present.
 */
export default function Contact() {
  const { isAuthenticated, user } = useAuth();

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  // The honeypot. Bound to state only so React owns the input; a human never
  // sees it and never changes it.
  const [website, setWebsite] = useState('');

  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSending(true);
    setError(null);
    try {
      await api.contact.submit({
        // Omitted entirely when signed in, rather than sent and discarded — the
        // request should say what it means.
        ...(isAuthenticated ? {} : { name: name.trim(), email: email.trim() }),
        subject: subject.trim() || undefined,
        body,
        website,
      });
      setSent(true);
    } catch (err) {
      // The server's message is the useful one: which field was missing, or that
      // nobody is available to receive messages.
      setError(err instanceof ApiError
        ? String(err.message).replace(/^\[\d+\]\s*/, '')
        : 'Could not send your message. Please try again.');
    } finally {
      setSending(false);
    }
  };

  if (sent) {
    return (
      <div className="page" style={{ maxWidth: 560 }}>
        <h1>Thanks — we got it</h1>
        <p>
          Your message is with our team. We'll reply
          {isAuthenticated ? ' to your inbox here on the site' : ' to the email address you gave us'}.
        </p>
        <p style={{ marginTop: 16 }}>
          <Link to="/">Back to the shop</Link>
          {isAuthenticated && <> · <Link to="/messages">Your messages</Link></>}
        </p>
      </div>
    );
  }

  const canSend = body.trim().length > 0
    && (isAuthenticated || (name.trim().length > 0 && email.trim().length > 0));

  return (
    <div className="page" style={{ maxWidth: 560 }}>
      <h1>Contact us</h1>
      <p style={{ color: 'var(--text-secondary)' }}>
        Questions about an order, a product, or anything else — send us a note and
        we'll get back to you.
      </p>

      <form onSubmit={submit} style={{ marginTop: 16 }}>
        {isAuthenticated ? (
          <>
            <label htmlFor="contact-from" style={{ display: 'block', marginBottom: 4 }}>From</label>
            {/* Read-only rather than hidden: the sender is worth showing, and the
                server takes it from the token regardless of what is typed here. */}
            <input
              id="contact-from"
              className="compose-field"
              value={user?.name ?? ''}
              readOnly
              aria-describedby="contact-from-hint"
            />
            <p id="contact-from-hint" style={{ color: 'var(--text-secondary)', marginTop: -6, marginBottom: 12 }}>
              Sent as your account. We'll reply to your <Link to="/messages">messages</Link>.
            </p>
          </>
        ) : (
          <>
            <input
              className="compose-field"
              placeholder="Your name"
              value={name}
              onChange={e => setName(e.target.value)}
              maxLength={120}
              autoComplete="name"
              required
            />
            <input
              className="compose-field"
              type="email"
              placeholder="Your email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              maxLength={255}
              autoComplete="email"
              required
            />
          </>
        )}

        <input
          className="compose-field"
          placeholder="Subject (optional)"
          value={subject}
          onChange={e => setSubject(e.target.value)}
          maxLength={200}
        />
        <textarea
          className="compose-field"
          placeholder="How can we help?"
          value={body}
          onChange={e => setBody(e.target.value)}
          maxLength={4000}
          rows={7}
          required
        />

        {/* Honeypot. Hidden from sight, from layout and from assistive tech, and
            skipped by tabbing — so nothing that fills it in is a person. */}
        <div aria-hidden="true" style={{ position: 'absolute', left: '-9999px' }}>
          <label htmlFor="contact-website">Website</label>
          <input
            id="contact-website"
            name="website"
            type="text"
            tabIndex={-1}
            autoComplete="off"
            value={website}
            onChange={e => setWebsite(e.target.value)}
          />
        </div>

        {error && <p style={{ color: 'var(--danger)', marginBottom: '0.5rem' }}>{error}</p>}

        {/* Disabled while in flight: there is no idempotency key, so a double
            click would send twice (docs/users/messaging.md §7.2). */}
        <button className="btn btn-primary" type="submit" disabled={sending || !canSend}>
          {sending ? 'Sending…' : 'Send message'}
        </button>
      </form>

      {!isAuthenticated && (
        <p style={{ marginTop: 16, color: 'var(--text-secondary)' }}>
          Have an account? <Link to="/login">Sign in</Link> and we can reply in your
          inbox here instead of by email.
        </p>
      )}
    </div>
  );
}
