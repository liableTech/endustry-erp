import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, type Page } from './api';
import type { Option } from './validation';

interface FetchState<T> {
  data: T | null;
  loading: boolean;
  error: ApiError | null;
  reload: () => void;
}

/** Loads a GET endpoint and reloads whenever the path changes. Pass null to skip. */
export function useFetch<T = any>(path: string | null): FetchState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(Boolean(path));
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const latest = useRef(0);

  useEffect(() => {
    if (!path) {
      setData(null);
      setLoading(false);
      return;
    }
    const call = ++latest.current;
    setLoading(true);
    api.get<T>(path)
      .then((result) => {
        if (call === latest.current) {
          setData(result);
          setError(null);
        }
      })
      .catch((e: ApiError) => {
        if (call === latest.current) setError(e);
      })
      .finally(() => {
        if (call === latest.current) setLoading(false);
      });
  }, [path, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, loading, error, reload };
}

const MAX_OPTIONS = 500;

/** Loads a master list for a dropdown. */
export function useRecords<T = any>(path: string | null): T[] {
  const separator = path?.includes('?') ? '&' : '?';
  const { data } = useFetch<Page<T>>(path ? `${path}${separator}size=${MAX_OPTIONS}&sort=id,asc` : null);
  return data?.content ?? [];
}

/** Choices from one list of the Lists master, e.g. UOM or REJECTION_REASON. */
export function useLookup(type: string | null): Option[] {
  const rows = useRecords<any>(type ? `/masters/lookups?lookupType=${type}&active=true` : null);
  return [...rows].sort((a, b) => a.sortOrder - b.sortOrder).map((r) => ({ value: r.code, label: `${r.name}` }));
}

/** Delays a fast-changing value (search box) so the server is not called on every keystroke. */
export function useDebounced<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/**
 * A record opened as its own page under a list route declared as `list/:id?/:mode?`,
 * e.g. /sales/invoices, /sales/invoices/new, /sales/invoices/12 and /sales/invoices/12/edit.
 * The list stays mounted underneath, so its search, tab and page survive going back.
 */
export function useRecordRoute() {
  const { id, mode } = useParams();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const depth = (id ? 1 : 0) + (mode ? 1 : 0);
  const segments = pathname.replace(/\/+$/, '').split('/');
  const base = segments.slice(0, segments.length - depth).join('/');
  return {
    id: id ?? null,
    mode: mode ?? null,
    open: (to: string | number, options?: { mode?: string; replace?: boolean }) =>
      navigate(`${base}/${to}${options?.mode ? `/${options.mode}` : ''}`, { replace: options?.replace }),
    close: () => navigate(base),
  };
}
