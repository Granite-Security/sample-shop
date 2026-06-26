import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';
import { api } from './api';

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

  useEffect(() => {
    api.me()
      .then(data => {
        if (data.authenticated && data.name) {
          setUser({ name: data.name!, claims: data.claims ?? {} });
        } else {
          setUser(null);
        }
      })
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  const logout = useCallback(() => {
    window.location.href = '/auth/logout';
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
