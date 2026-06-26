import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';
import { userManager } from './oauth';
import { setAccessToken } from './api';
import type { User as OidcUser } from 'oidc-client-ts';

interface User {
  name: string;
  claims: Record<string, unknown>;
}

interface AuthContext {
  user: User | null;
  isAuthenticated: boolean;
  isAdmin: boolean;
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
    userManager.getUser().then(updateUser).finally(() => setLoading(false));

    const handleUserLoaded = (oidcUser: OidcUser) => updateUser(oidcUser);
    const handleUserUnloaded = () => updateUser(null);

    userManager.events.addUserLoaded(handleUserLoaded);
    userManager.events.addUserUnloaded(handleUserUnloaded);

    return () => {
      userManager.events.removeUserLoaded(handleUserLoaded);
      userManager.events.removeUserUnloaded(handleUserUnloaded);
    };
  }, [updateUser]);

  const logout = useCallback(() => {
    userManager.signoutRedirect({ post_logout_redirect_uri: window.location.origin });
  }, []);

  const isAuthenticated = user !== null;
  const roles = (user?.claims?.roles as string[]) ?? [];
  const isAdmin = roles.some(r => r === 'ROLE_ADMIN' || r === 'ADMIN');

  return (
    <AuthCtx.Provider value={{ user, isAuthenticated, isAdmin, logout, loading }}>
      {children}
    </AuthCtx.Provider>
  );
}

export function useAuth(): AuthContext {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
