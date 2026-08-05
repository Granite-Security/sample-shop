import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api } from '../api';
import { useAuth } from '../auth';

interface MessagesContext {
  unreadCount: number;
  /** Re-read the count from the server. */
  refresh: () => void;
  /**
   * Drop the badge locally when a message is opened, without waiting for the
   * next poll. Skipping this is what makes the bell keep claiming "1 unread"
   * while you are looking at the message.
   */
  markOneRead: () => void;
}

const MessagesCtx = createContext<MessagesContext | null>(null);

const POLL_MS = 30_000;

/**
 * Unread-message count for the header bell (docs/users/messaging.md Phase 2).
 *
 * Polls rather than streaming — D7. One indexed COUNT per signed-in user every
 * 30s; an SSE stream would need a gateway route for a long-lived connection and
 * per-user fan-out in profile, for an inbox nobody expects to be sub-second.
 */
export function MessagesProvider({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  const [unreadCount, setUnreadCount] = useState(0);

  const refresh = useCallback(() => {
    if (!isAuthenticated) return;
    api.messages.unreadCount()
      .then(r => setUnreadCount(r.count))
      // A failed poll is not worth surfacing: the next one is 30s away and the
      // badge simply keeps its previous value.
      .catch(() => {});
  }, [isAuthenticated]);

  const markOneRead = useCallback(() => {
    setUnreadCount(current => Math.max(0, current - 1));
  }, []);

  useEffect(() => {
    // Signed out there is nothing to count, and polling would just generate
    // 401s on a timer.
    if (!isAuthenticated) {
      setUnreadCount(0);
      return;
    }

    refresh();
    const timer = window.setInterval(() => {
      // A backgrounded tab would otherwise burn ~120 requests an hour counting
      // messages nobody is looking at.
      if (document.visibilityState === 'visible') refresh();
    }, POLL_MS);

    // Coming back to the tab is exactly when a stale badge is most obvious, so
    // refresh then rather than waiting out the rest of the interval.
    const onVisible = () => { if (document.visibilityState === 'visible') refresh(); };
    document.addEventListener('visibilitychange', onVisible);

    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [isAuthenticated, refresh]);

  return (
    <MessagesCtx.Provider value={{ unreadCount, refresh, markOneRead }}>
      {children}
    </MessagesCtx.Provider>
  );
}

export function useMessages(): MessagesContext {
  const ctx = useContext(MessagesCtx);
  if (!ctx) throw new Error('useMessages must be used within MessagesProvider');
  return ctx;
}
