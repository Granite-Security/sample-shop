import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';
import { userManager } from './oauth';
import { setAccessToken, setTokenRefresher } from './api';
import type { User as OidcUser } from 'oidc-client-ts';

// Same auth model as ui-shop/src/auth.tsx: oidc-client-ts against the
// auth-server's spa-client, roles read from the custom `roles` JWT claim.
interface User {
  name: string;
  claims: Record<string, unknown>;
}

interface AuthContext {
  user: User | null;
  isAuthenticated: boolean;
  isAdmin: boolean;
  login: () => void;
  logout: () => void;
  loading: boolean;
}

const AuthCtx = createContext<AuthContext | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const updateUser = useCallback((oidcUser: OidcUser | null) => {
    if (oidcUser && !oidcUser.expired) {
      const claims = oidcUser.profile as Record<string, unknown>;
      const name = (claims.preferred_username as string) ?? (claims.sub as string);
      setUser({ name, claims });
      setAccessToken(oidcUser.access_token);
    } else {
      setUser(null);
      setAccessToken(null);
    }
  }, []);

  useEffect(() => {
    // Lets the api client recover from an expired access token on its own.
    // signinSilent updates the stored user, which fires addUserLoaded below, so
    // setAccessToken is refreshed as a side effect of this too.
    setTokenRefresher(async () => {
      const renewed = await userManager.signinSilent();
      return renewed?.access_token ?? null;
    });

    userManager.getUser().then(updateUser).finally(() => setLoading(false));

    const handleUserLoaded = (oidcUser: OidcUser) => updateUser(oidcUser);
    const handleUserUnloaded = () => updateUser(null);

    userManager.events.addUserLoaded(handleUserLoaded);
    userManager.events.addUserUnloaded(handleUserUnloaded);

    return () => {
      setTokenRefresher(null);
      userManager.events.removeUserLoaded(handleUserLoaded);
      userManager.events.removeUserUnloaded(handleUserUnloaded);
    };
  }, [updateUser]);

  const login = useCallback(() => {
    userManager.signinRedirect();
  }, []);

  const logout = useCallback(() => {
    userManager.signoutRedirect({ post_logout_redirect_uri: window.location.origin });
  }, []);

  const isAuthenticated = user !== null;
  const roles = (user?.claims?.roles as string[]) ?? [];
  const isAdmin = roles.some((r) => r === 'ROLE_ADMIN' || r === 'ADMIN');

  return (
    <AuthCtx.Provider value={{ user, isAuthenticated, isAdmin, login, logout, loading }}>
      {children}
    </AuthCtx.Provider>
  );
}

export function useAuth(): AuthContext {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
