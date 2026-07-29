import { useState, type FormEvent } from 'react';
import { Reveal } from '../components/Reveal';

const SUBJECTS = ['A question about an order', 'Gifting & large orders', 'Wholesale & stockists', 'Something else'];

const fieldStyle =
  'w-full border border-cocoa/20 bg-white/60 px-5 py-3.5 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none transition-colors';

export function ContactPage() {
  const [sent, setSent] = useState(false);

  // No backend yet: the contact endpoint lands in a later stage. Rather than
  // pretend the note was delivered, the confirmation says plainly that it
  // wasn't — a fake "message sent" is the one thing this page must not do.
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSent(true);
  };

  return (
    <>
      <section className="relative overflow-hidden bg-espresso pt-32 pb-20 lg:pt-40 lg:pb-24">
        <div className="absolute inset-0">
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_30%_25%,#46281A_0%,#1E1612_65%,#120D0A_100%)]" />
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
                    One honest note: our messaging service isn't switched on yet, so this message
                    hasn't reached us. It's the next thing we're building — until then, please reach
                    us through the atelier directly.
                  </p>
                  <button
                    onClick={() => setSent(false)}
                    className="mt-8 text-xs uppercase tracking-[0.18em] text-terracotta underline decoration-gold underline-offset-8"
                  >
                    Write another message
                  </button>
                </div>
              ) : (
                <form onSubmit={onSubmit} className="space-y-5">
                  <div className="grid gap-5 sm:grid-cols-2">
                    <div>
                      <label htmlFor="contact-name" className="text-xs uppercase tracking-[0.18em] text-cocoa/60">
                        Your name
                      </label>
                      <input id="contact-name" name="name" type="text" required className={`mt-2 ${fieldStyle}`} />
                    </div>
                    <div>
                      <label htmlFor="contact-email" className="text-xs uppercase tracking-[0.18em] text-cocoa/60">
                        Email
                      </label>
                      <input id="contact-email" name="email" type="email" required className={`mt-2 ${fieldStyle}`} />
                    </div>
                  </div>

                  <div>
                    <label htmlFor="contact-subject" className="text-xs uppercase tracking-[0.18em] text-cocoa/60">
                      Subject
                    </label>
                    <select id="contact-subject" name="subject" className={`mt-2 ${fieldStyle}`}>
                      {SUBJECTS.map((subject) => (
                        <option key={subject}>{subject}</option>
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
                      placeholder="Tell us what you need."
                      className={`mt-2 resize-y ${fieldStyle}`}
                    />
                  </div>

                  <p className="text-xs text-cocoa/45">
                    Messaging isn't connected yet — sending is coming in a later release.
                  </p>

                  <button
                    type="submit"
                    className="bg-cocoa px-9 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-500 ease-luxe hover:bg-espresso"
                  >
                    Send Message
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
