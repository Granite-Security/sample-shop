import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Reveal } from '../components/Reveal';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';

const SUBJECTS = ['A question about an order', 'Gifting & large orders', 'Wholesale & stockists', 'Something else'];

const fieldStyle =
  'w-full border border-cocoa/20 bg-white/60 px-5 py-3.5 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none transition-colors';

/**
 * The contact form (docs/users/messaging.md §11), wired to
 * POST /api/profiles/contact — the one profile endpoint that takes no token, so
 * this page works signed out. Notes land in the manager's ordinary inbox.
 *
 * Signed in, the "From" line shows the account and is not editable: the server
 * takes the sender from the JWT and ignores anything the body claims.
 */
export function ContactPage() {
  const { isAuthenticated, user } = useAuth();

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [subject, setSubject] = useState(SUBJECTS[0]);
  const [body, setBody] = useState('');
  // The honeypot. Bound to state only so React owns the input; a person never
  // sees it and never changes it.
  const [website, setWebsite] = useState('');

  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  const reset = () => {
    setName('');
    setEmail('');
    setSubject(SUBJECTS[0]);
    setBody('');
    setWebsite('');
    setError(null);
    setSent(false);
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSending(true);
    setError(null);
    try {
      await api.submitContact({
        // Omitted entirely when signed in, rather than sent and discarded —
        // the request should say what it means.
        ...(isAuthenticated ? {} : { name: name.trim(), email: email.trim() }),
        subject,
        body,
        website,
      });
      setSent(true);
    } catch (err) {
      // The server's message is the useful one: which field was missing, or
      // that nobody is available to receive messages just now.
      setError(
        err instanceof ApiError
          ? String(err.message).replace(/^\[\d+\]\s*/, '')
          : 'We could not send your message just now. Please try again.',
      );
    } finally {
      setSending(false);
    }
  };

  const canSend =
    body.trim().length > 0 && (isAuthenticated || (name.trim().length > 0 && email.trim().length > 0));

  return (
    <>
      <section className="relative overflow-hidden bg-espresso pt-32 pb-20 lg:pt-40 lg:pb-24">
        <div className="absolute inset-0">
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_30%_25%,#3A2130_0%,#150E13_65%,#120D0A_100%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_78%_88%,rgba(199,165,107,0.12)_0%,transparent_55%)]" />
        </div>
        <div className="relative mx-auto max-w-3xl px-5 text-center lg:px-8">
          <Reveal>
            <p className="text-xs uppercase tracking-[0.3em] text-gold">Contact</p>
            <h1 className="mt-4 font-display text-[36px] leading-tight text-ivory lg:text-[56px]">
              Write to Us
            </h1>
            <p className="mx-auto mt-6 max-w-xl text-lg text-ivory/75">
              Questions, gifting, wholesale — or simply to tell us how the last box was.
            </p>
          </Reveal>
        </div>
      </section>

      <section className="bg-ivory">
        <div className="mx-auto max-w-6xl px-5 py-24 lg:px-8 lg:py-28">
          <div className="grid gap-16 lg:grid-cols-[3fr_2fr] lg:gap-24">
            <Reveal>
              {sent ? (
                <div className="border border-sage/40 bg-sage/5 p-10 text-center" role="status">
                  <h2 className="font-display text-[26px] text-cocoa">Thank you for writing.</h2>
                  <p className="mx-auto mt-4 max-w-md text-cocoa/65">
                    Your note is with us. We answer within two working days
                    {isAuthenticated ? (
                      <>
                        {' '}— you'll find our reply in{' '}
                        <Link to="/profile/messages" className="underline decoration-gold underline-offset-4">
                          your messages
                        </Link>
                        .
                      </>
                    ) : (
                      ', by email.'
                    )}
                  </p>
                  <button
                    onClick={reset}
                    className="mt-8 text-xs uppercase tracking-[0.18em] text-terracotta underline decoration-gold underline-offset-8"
                  >
                    Write another message
                  </button>
                </div>
              ) : (
                <form onSubmit={onSubmit} className="space-y-5">
                  {isAuthenticated ? (
                    <div>
                      <p className="text-xs uppercase tracking-[0.18em] text-cocoa/60">From</p>
                      <p className="mt-2 border border-cocoa/15 bg-cocoa/5 px-5 py-3.5 text-sm text-cocoa">
                        {user?.name}
                      </p>
                      <p className="mt-2 text-xs text-cocoa/45">
                        Written as your account — our reply will arrive in your messages.
                      </p>
                    </div>
                  ) : (
                    <div className="grid gap-5 sm:grid-cols-2">
                      <div>
                        <label htmlFor="contact-name" className="text-xs uppercase tracking-[0.18em] text-cocoa/60">
                          Your name
                        </label>
                        <input
                          id="contact-name"
                          name="name"
                          type="text"
                          required
                          maxLength={120}
                          autoComplete="name"
                          value={name}
                          onChange={(e) => setName(e.target.value)}
                          className={`mt-2 ${fieldStyle}`}
                        />
                      </div>
                      <div>
                        <label htmlFor="contact-email" className="text-xs uppercase tracking-[0.18em] text-cocoa/60">
                          Email
                        </label>
                        <input
                          id="contact-email"
                          name="email"
                          type="email"
                          required
                          maxLength={255}
                          autoComplete="email"
                          value={email}
                          onChange={(e) => setEmail(e.target.value)}
                          className={`mt-2 ${fieldStyle}`}
                        />
                      </div>
                    </div>
                  )}

                  <div>
                    <label htmlFor="contact-subject" className="text-xs uppercase tracking-[0.18em] text-cocoa/60">
                      Subject
                    </label>
                    <select
                      id="contact-subject"
                      name="subject"
                      value={subject}
                      onChange={(e) => setSubject(e.target.value)}
                      className={`mt-2 ${fieldStyle}`}
                    >
                      {SUBJECTS.map((option) => (
                        <option key={option}>{option}</option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <label htmlFor="contact-message" className="text-xs uppercase tracking-[0.18em] text-cocoa/60">
                      Message
                    </label>
                    <textarea
                      id="contact-message"
                      name="message"
                      required
                      rows={7}
                      maxLength={4000}
                      placeholder="Tell us what you need."
                      value={body}
                      onChange={(e) => setBody(e.target.value)}
                      className={`mt-2 resize-y ${fieldStyle}`}
                    />
                  </div>

                  {/* Honeypot. Hidden from sight, from layout and from assistive
                      tech, and skipped by tabbing — so nothing that fills it in
                      is a person. */}
                  <div aria-hidden="true" className="absolute -left-[9999px]">
                    <label htmlFor="contact-website">Website</label>
                    <input
                      id="contact-website"
                      name="website"
                      type="text"
                      tabIndex={-1}
                      autoComplete="off"
                      value={website}
                      onChange={(e) => setWebsite(e.target.value)}
                    />
                  </div>

                  {error && (
                    <p className="text-sm text-terracotta" role="alert">
                      {error}
                    </p>
                  )}

                  {!isAuthenticated && (
                    <p className="text-xs text-cocoa/45">
                      Have an account?{' '}
                      <Link to="/register" className="underline decoration-gold underline-offset-4">
                        Sign in
                      </Link>{' '}
                      and we'll reply in your messages here instead of by email.
                    </p>
                  )}

                  {/* Disabled while in flight: there is no idempotency key, so a
                      double click would send twice (docs/users/messaging.md §7.2). */}
                  <button
                    type="submit"
                    disabled={sending || !canSend}
                    className="bg-cocoa px-9 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-500 ease-luxe hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {sending ? 'Sending…' : 'Send Message'}
                  </button>
                </form>
              )}
            </Reveal>

            <Reveal delay={200}>
              <div className="space-y-10">
                <div className="border-l-2 border-gold/40 pl-6">
                  <h2 className="font-display text-[22px] text-cocoa">The Atelier</h2>
                  <p className="mt-2 text-cocoa/65">
                    Cahul, Moldova — where every batch is still made.
                  </p>
                </div>
                <div className="border-l-2 border-gold/40 pl-6">
                  <h2 className="font-display text-[22px] text-cocoa">Romania</h2>
                  <p className="mt-2 text-cocoa/65">
                    Iași — our largest audience, and where you'll most often find us at tastings.
                  </p>
                </div>
                <div className="border-l-2 border-gold/40 pl-6">
                  <h2 className="font-display text-[22px] text-cocoa">Response Time</h2>
                  <p className="mt-2 text-cocoa/65">
                    We answer within two working days. Gifting enquiries usually the same day.
                  </p>
                </div>
              </div>
            </Reveal>
          </div>
        </div>
      </section>
    </>
  );
}
