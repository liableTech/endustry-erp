import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, setModuleDisabledHandler, setUnauthenticatedHandler, tokenStore } from './api';
import { STANDARD_LABELS, inSentence, term, type Labels, type Term } from './labels';
import { t } from './i18n';
import { useTheme } from './theme';

export type AccessLevel = 'NONE' | 'VIEW' | 'FULL';

/** The modules a product admin can switch on or off per client; the rest are always there. */
export const SWITCHABLE_MODULES = ['sales', 'purchase', 'inventory', 'production', 'quality', 'reports'];

export interface User {
  id: number;
  email: string;
  fullName: string;
  role: string;
}

/** The client the signed-in user belongs to, and what the product admin has switched on for it. */
export interface Client {
  slug: string;
  name: string;
  whiteLabel: boolean;
  modules: string[];
}

interface Session {
  token: string | null;
  user: User;
  permissions: Record<string, AccessLevel>;
  client: Client | null;
  impersonatedBy: string | null;
  labels?: Labels;
}

interface AuthState {
  user: User | null;
  client: Client | null;
  /** Set during a support session: the product admin who is acting as this user. */
  impersonatedBy: string | null;
  isProductAdmin: boolean;
  loading: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => void;
  /** Whether the signed-in role may see (VIEW) or change (FULL) a module. */
  can: (module: string, level?: AccessLevel) => boolean;
  /** Whether the product admin has switched a module on for this client. */
  enabled: (module: string) => boolean;
  /** Switches to a support session using a token issued by the product admin area. */
  startSupport: (token: string) => Promise<void>;
  /** Leaves the support session and returns to the product admin's own sign-in. */
  endSupport: () => Promise<void>;
  /** The client's words for customers, items and the rest (Settings > Labels). */
  labels: Labels;
  /** Re-reads the session, e.g. after the Owner renames a term. */
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);
const RANK: Record<AccessLevel, number> = { NONE: 0, VIEW: 1, FULL: 2 };
/** The product admin's own token is parked here while a support session is running. */
const ADMIN_TOKEN_KEY = 'endustry.adminToken';

export function AuthProvider({ children }: { children: ReactNode }) {
  const { reload: reloadTheme } = useTheme();
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(Boolean(tokenStore.get()));

  const signOut = useCallback(() => {
    tokenStore.clear();
    sessionStorage.removeItem(ADMIN_TOKEN_KEY);
    setSession(null);
  }, []);

  const load = useCallback(async () => {
    setSession(await api.get<Session>('/auth/me'));
  }, []);

  useEffect(() => {
    setUnauthenticatedHandler(signOut);
    if (!tokenStore.get()) return;
    load().catch(signOut).finally(() => setLoading(false));
  }, [signOut, load]);

  // Modules can be switched off by the product admin while the user is signed in: pick that up
  // when the server refuses a module, and whenever the user comes back to the tab.
  useEffect(() => {
    const refresh = () => {
      if (tokenStore.get()) load().catch(() => {});
    };
    const onVisible = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    setModuleDisabledHandler(refresh);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      setModuleDisabledHandler(() => {});
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [load]);

  // The look depends on who is signed in: a white-label client's own theme, or the product's.
  const identity = session ? `${session.user.email}|${session.client?.slug ?? 'product'}` : 'anonymous';
  useEffect(() => {
    reloadTheme();
  }, [identity, reloadTheme]);

  const signIn = useCallback(async (email: string, password: string) => {
    const next = await api.post<Session>('/auth/login', { email, password });
    tokenStore.set(next.token!);
    setSession(next);
  }, []);

  const startSupport = useCallback(async (token: string) => {
    sessionStorage.setItem(ADMIN_TOKEN_KEY, tokenStore.get() ?? '');
    tokenStore.set(token);
    await load();
  }, [load]);

  const endSupport = useCallback(async () => {
    const adminToken = sessionStorage.getItem(ADMIN_TOKEN_KEY);
    sessionStorage.removeItem(ADMIN_TOKEN_KEY);
    if (!adminToken) return signOut();
    tokenStore.set(adminToken);
    await load().catch(signOut);
  }, [load, signOut]);

  const value = useMemo<AuthState>(() => ({
    user: session?.user ?? null,
    client: session?.client ?? null,
    impersonatedBy: session?.impersonatedBy ?? null,
    isProductAdmin: session?.user.role === 'PLATFORM_ADMIN',
    loading,
    signIn,
    signOut,
    can: (module, level = 'VIEW') => RANK[session?.permissions[module] ?? 'NONE'] >= RANK[level],
    enabled: (module) => !session?.client || !SWITCHABLE_MODULES.includes(module) || session.client.modules.includes(module),
    startSupport,
    endSupport,
    labels: session?.labels ?? STANDARD_LABELS,
    refresh: load,
  }), [session, loading, signIn, signOut, startSupport, endSupport, load]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}

/** The client's word for a term: {@code word('customer').plural} is "Clients" for a firm that renamed it. */
export function useLabels() {
  const { labels } = useAuth();
  return {
    /** In the user's language: a word the client renamed shows as typed unless it has a translation. */
    word: (key: string): Term => {
      const w = term(labels, key);
      return { singular: t(w.singular), plural: t(w.plural) };
    },
    /** Mid-sentence form of the singular, e.g. "client". */
    one: (key: string): string => inSentence(t(term(labels, key).singular)),
  };
}
