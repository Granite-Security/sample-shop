import { useState, type FormEvent } from 'react';
import { Reveal } from './Reveal';

export function Newsletter() {
  const [email, setEmail] = useState('');
  const [subscribed, setSubscribed] = useState(false);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (email.trim()) setSubscribed(true);
  };

  return (
    <section id="newsletter" className="border-t border-cocoa/10 bg-ivory">
      <div className="mx-auto max-w-2xl px-5 py-24 text-center lg:py-28">
        <Reveal>
          <p className="text-xs uppercase tracking-[0.3em] text-terracotta">The Journal</p>
          <h2 className="mt-4 font-display text-[32px] leading-tight text-cocoa lg:text-[48px]">
            Join the Chocolate Journal
          </h2>
          <p className="mx-auto mt-4 max-w-md text-cocoa/60">
            Receive early access to seasonal releases and exclusive collections.
          </p>
        </Reveal>

        <Reveal delay={200}>
          {subscribed ? (
            <p className="mt-10 font-display text-xl italic text-sage" role="status">
              Welcome to the Journal — your first letter arrives with the next harvest.
            </p>
          ) : (
            <form onSubmit={onSubmit} className="mx-auto mt-10 flex max-w-md flex-col gap-3 sm:flex-row">
              <label htmlFor="newsletter-email" className="sr-only">
                Email address
              </label>
              <input
                id="newsletter-email"
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="Your email address"
                className="flex-1 border border-cocoa/20 bg-white/60 px-5 py-4 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none"
              />
              <button
                type="submit"
                className="bg-cocoa px-8 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-300 hover:bg-espresso"
              >
                Subscribe
              </button>
            </form>
          )}
        </Reveal>
      </div>
    </section>
  );
}
