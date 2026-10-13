import { Fragment, createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

/*
 * Screen text in the user's language. Every text the application shows is written in English in
 * the code and looked up with t('Sign in'): the English wording is the key. The server keeps each
 * language as rows in the database and sends it as one JSON object with a version number
 * (GET /api/public/i18n/hi). The browser keeps that copy until the version changes, so switching
 * or reloading needs no new download. Text with no translation shows in English.
 */

export interface Language {
  code: string;
  name: string;
  nativeName: string;
  version: number;
}

interface Bundle {
  code: string;
  version: number;
  texts: Record<string, string>;
}

const LANG_KEY = 'endustry.lang';
const CACHE_PREFIX = 'endustry.i18n.';

function readCache(code: string): Bundle | null {
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + code);
    return raw ? (JSON.parse(raw) as Bundle) : null;
  } catch {
    return null;
  }
}

function savedLanguage(): string {
  try {
    return localStorage.getItem(LANG_KEY) ?? 'en';
  } catch {
    return 'en';
  }
}

// The current language's text, readable from anywhere (config files, helpers) without a hook.
// Filled from the browser's copy at start-up so a Hindi user never sees an English flash.
let current: Bundle = readCache(savedLanguage()) ?? { code: 'en', version: 0, texts: {} };

/**
 * The text in the current language. {name}-style placeholders are filled from {@code vars}:
 * t('New {thing}', { thing: 'item' }).
 */
export function t(text: string, vars?: Record<string, string | number | null | undefined>): string {
  let result = current.texts[text] ?? text;
  if (vars) {
    for (const [name, value] of Object.entries(vars)) result = result.split(`{${name}}`).join(value == null ? "" : String(value));
  }
  return result;
}

/** Translates a node only when it is plain text; anything else is shown as it is. */
export function tx(node: ReactNode): ReactNode {
  return typeof node === 'string' ? t(node) : node;
}

export function currentLanguage(): string {
  return current.code;
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`/api${path}`, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`Request failed (${response.status})`);
  return response.json() as Promise<T>;
}

interface I18nState {
  language: string;
  languages: Language[];
  setLanguage: (code: string) => void;
}

const I18nContext = createContext<I18nState>({ language: 'en', languages: [], setLanguage: () => {} });

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState(current.code);
  const [languages, setLanguages] = useState<Language[]>([]);
  // Changes whenever the text in use changes; the tree below is redrawn with the new text.
  const [revision, setRevision] = useState(`${current.code}:${current.version}`);
  // With no copy of the text in the browser yet (a first visit), wait for it before drawing the app:
  // the redraw when it arrives would otherwise clear anything already typed, such as the sign-in email.
  const [ready, setReady] = useState(current.version !== 0);

  const load = useCallback(async (code: string) => {
    const list = await getJson<Language[]>('/public/i18n/languages');
    setLanguages(list);
    const wanted = list.find((l) => l.code === code) ?? list.find((l) => l.code === 'en');
    if (!wanted) return;
    let bundle = readCache(wanted.code);
    if (!bundle || bundle.version !== wanted.version) {
      bundle = await getJson<Bundle>(`/public/i18n/${wanted.code}?v=${wanted.version}`);
      try {
        localStorage.setItem(CACHE_PREFIX + wanted.code, JSON.stringify(bundle));
      } catch {
        // Storage full or blocked: the text still works for this visit.
      }
    }
    current = bundle;
    try {
      localStorage.setItem(LANG_KEY, bundle.code);
    } catch {
      // Not remembered across visits, but shown now.
    }
    document.documentElement.lang = bundle.code;
    setLanguageState(bundle.code);
    setRevision(`${bundle.code}:${bundle.version}`);
  }, []);

  useEffect(() => {
    // The saved choice, not the text in use: a first visit in Hindi has no copy of the text yet.
    load(savedLanguage()).catch(() => {}).finally(() => setReady(true));
    // Translations corrected on the server reach open tabs when the user comes back to them.
    const onVisible = () => {
      if (document.visibilityState === 'visible') load(current.code).catch(() => {});
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [load]);

  const setLanguage = useCallback((code: string) => {
    load(code).catch(() => {});
  }, [load]);

  return (
    <I18nContext.Provider value={{ language, languages, setLanguage }}>
      <Fragment key={revision}>{ready ? children : null}</Fragment>
    </I18nContext.Provider>
  );
}

export function useI18n(): I18nState {
  return useContext(I18nContext);
}

/** A compact language picker, shown in the top bar, on the sign-in page and on the landing page. */
export function LanguageSwitcher({ className = '' }: { className?: string }) {
  const { language, languages, setLanguage } = useI18n();
  if (languages.length < 2) return null;
  return (
    <select className={`input lang-switch ${className}`} value={language} aria-label={t('Language')} title={t('Language')}
      onChange={(e) => setLanguage(e.target.value)}>
      {languages.map((l) => <option key={l.code} value={l.code}>{l.nativeName}</option>)}
    </select>
  );
}
