import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, tokenStore } from './api';

/** Mirrors ThemeSettings on the server. Everything visual in the app derives from these values. */
export interface Theme {
  appName: string;
  mode: 'light' | 'dark' | 'system';
  primaryColor: string;
  accentColor: string;
  successColor: string;
  warningColor: string;
  dangerColor: string;
  sidebarStyle: 'dark' | 'light' | 'brand';
  radius: number;
  density: 'comfortable' | 'compact';
  fontFamily: 'Inter' | 'Poppins' | 'Roboto' | 'System';
}

export const DEFAULT_THEME: Theme = {
  appName: 'Endustry ERP',
  mode: 'light',
  primaryColor: '#6D3FE0',
  accentColor: '#A855F7',
  successColor: '#16A34A',
  warningColor: '#D97706',
  dangerColor: '#DC2626',
  sidebarStyle: 'light',
  radius: 14,
  density: 'comfortable',
  fontFamily: 'Inter',
};

export const THEME_PRESETS: { name: string; theme: Partial<Theme> }[] = [
  { name: 'Lavender', theme: { primaryColor: '#6D3FE0', accentColor: '#A855F7', sidebarStyle: 'light' } },
  { name: 'Indigo', theme: { primaryColor: '#4F46E5', accentColor: '#0EA5E9', sidebarStyle: 'dark' } },
  { name: 'Teal', theme: { primaryColor: '#0F766E', accentColor: '#F59E0B', sidebarStyle: 'dark' } },
  { name: 'Royal blue', theme: { primaryColor: '#1D4ED8', accentColor: '#7C3AED', sidebarStyle: 'brand' } },
  { name: 'Saffron', theme: { primaryColor: '#EA580C', accentColor: '#0D9488', sidebarStyle: 'light' } },
  { name: 'Crimson', theme: { primaryColor: '#BE123C', accentColor: '#2563EB', sidebarStyle: 'dark' } },
  { name: 'Forest', theme: { primaryColor: '#15803D', accentColor: '#CA8A04', sidebarStyle: 'brand' } },
];

/** Remembers which client's sign-in page this browser uses. */
const CLIENT_KEY = 'endustry.client';

const FONT_STACKS: Record<Theme['fontFamily'], string> = {
  Inter: "'Inter', system-ui, sans-serif",
  Poppins: "'Poppins', system-ui, sans-serif",
  Roboto: "'Roboto', system-ui, sans-serif",
  System: "system-ui, -apple-system, 'Segoe UI', sans-serif",
};

/** Black or white, whichever reads better on the given background. */
export function contrastOn(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.45 ? '#111827' : '#FFFFFF';
}

/** Writes the theme onto the document as CSS variables and data attributes. */
export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  const dark = theme.mode === 'dark' || (theme.mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  root.dataset.theme = dark ? 'dark' : 'light';
  root.dataset.sidebar = theme.sidebarStyle;
  root.dataset.density = theme.density;
  const set = (name: string, value: string) => root.style.setProperty(name, value);
  set('--primary', theme.primaryColor);
  set('--primary-contrast', contrastOn(theme.primaryColor));
  set('--accent', theme.accentColor);
  set('--success', theme.successColor);
  set('--warning', theme.warningColor);
  set('--danger', theme.dangerColor);
  set('--radius', `${theme.radius}px`);
  set('--font', FONT_STACKS[theme.fontFamily]);
  document.title = theme.appName;
}

interface ThemeState {
  theme: Theme;
  companyName: string;
  companyState: string;
  /** Applies a theme to the screen without saving it (live preview). */
  preview: (theme: Theme) => void;
  /** Reloads the saved theme and company name from the server. */
  reload: () => Promise<void>;
}

const ThemeContext = createContext<ThemeState | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(DEFAULT_THEME);
  const [company, setCompany] = useState({ name: '', state: '' });

  const reload = useCallback(async () => {
    try {
      // Signed in: the look that belongs to this user. Otherwise the sign-in page's look, which a
      // white-label client selects with its own link (?client=their-short-name).
      const client = new URLSearchParams(window.location.search).get('client') ?? localStorage.getItem(CLIENT_KEY);
      if (client) localStorage.setItem(CLIENT_KEY, client);
      const branding = await api.get(tokenStore.get() ? '/auth/branding' : `/public/branding${client ? `?client=${encodeURIComponent(client)}` : ''}`);
      setTheme({ ...DEFAULT_THEME, ...branding.theme });
      setCompany({ name: branding.companyName, state: branding.stateCode });
    } catch {
      // The sign-in page still works with the default look if branding cannot be loaded.
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const value = useMemo<ThemeState>(() => ({
    theme, companyName: company.name, companyState: company.state, preview: setTheme, reload,
  }), [theme, company, reload]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeState {
  const context = useContext(ThemeContext);
  if (!context) throw new Error('useTheme must be used inside ThemeProvider');
  return context;
}
