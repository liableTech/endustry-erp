import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { ChevronDown, ListFilter, Search, X } from 'lucide-react';
import { query, type Page } from '../core/api';
import { formatDate, formatQty, today } from '../core/format';
import { useFetch, useLookup } from '../core/hooks';
import { t } from '../core/i18n';
import type { Option } from '../core/validation';
import { Button } from './ui';

/*
 * Filters for any table. A screen lists the columns that can be filtered and what kind each is;
 * the Filters button, the panel and the chips come from here. Several filters work together.
 * Filters are either sent to the server (params) or applied to rows already loaded (apply).
 */

export type FilterKind = 'text' | 'choice' | 'number' | 'date' | 'bool';

export interface FilterDef {
  /** The row field, and the server parameter when the filter is sent to the server. */
  key: string;
  label: string;
  kind: FilterKind;
  /** For choice: the values to tick. */
  options?: Option[];
  /** For choice: take the values from a list of the Lists master instead. */
  lookupType?: string;
  /** For choice: always a dropdown to search and tick in, however short the list (customers, vendors). */
  dropdown?: boolean;
  /** For filters applied in the browser: how to read the value from a row (the field named by key otherwise). */
  value?: (row: any) => unknown;
}

interface Range {
  from?: string;
  to?: string;
}
export type FilterValue = string | string[] | Range;
export type FilterValues = Record<string, FilterValue>;

const STORE_PREFIX = 'endustry.filters.';
/** Lists paged by the server are filtered in the browser over this many of their newest rows. */
export const FILTER_ROW_LIMIT = 500;

function isSet(value: FilterValue | undefined): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === 'string') return value.trim() !== '';
  if (Array.isArray(value)) return value.length > 0;
  return Boolean(value.from) || Boolean(value.to);
}

function clean(defs: FilterDef[], values: FilterValues): FilterValues {
  const kept: FilterValues = {};
  defs.forEach((d) => { if (isSet(values[d.key])) kept[d.key] = values[d.key]; });
  return kept;
}

function rowMatches(row: any, def: FilterDef, value: FilterValue): boolean {
  const raw = def.value ? def.value(row) : row[def.key];
  switch (def.kind) {
    case 'text':
      return String(raw ?? '').toLowerCase().includes(String(value).trim().toLowerCase());
    case 'choice':
      return (value as string[]).includes(String(raw ?? ''));
    case 'bool':
      return String(Boolean(raw)) === value;
    case 'number': {
      const { from, to } = value as Range;
      const n = Number(raw ?? 0);
      return (!from || n >= Number(from)) && (!to || n <= Number(to));
    }
    case 'date': {
      const { from, to } = value as Range;
      // Dates and timestamps both start with yyyy-mm-dd, which compares correctly as text.
      const day = String(raw ?? '').slice(0, 10);
      return day !== '' && (!from || day >= from) && (!to || day <= to);
    }
  }
}

/** The rows that pass every filter that is set. */
export function applyFilters<T>(rows: T[], defs: FilterDef[], values: FilterValues): T[] {
  const active = defs.filter((d) => isSet(values[d.key]));
  return active.length === 0 ? rows : rows.filter((row) => active.every((d) => rowMatches(row, d, values[d.key])));
}

/** The filters as server parameters: field=a,b for choices, field.like for text, field.from and field.to for ranges. */
export function filterParams(defs: FilterDef[], values: FilterValues): Record<string, string> {
  const params: Record<string, string> = {};
  defs.forEach((d) => {
    const value = values[d.key];
    if (!isSet(value)) return;
    if (d.kind === 'text') params[`${d.key}.like`] = String(value).trim();
    else if (d.kind === 'choice') params[d.key] = (value as string[]).join(',');
    else if (d.kind === 'bool') params[d.key] = value as string;
    else {
      const { from, to } = value as Range;
      if (from) params[`${d.key}.from`] = from;
      if (to) params[`${d.key}.to`] = to;
    }
  });
  return params;
}

/** Choices made of the values the loaded rows actually have, for columns with no fixed list. */
export function optionsFrom(rows: any[], value: (row: any) => unknown, label: (value: string) => string = (v) => v): Option[] {
  const seen = new Set<string>();
  rows.forEach((row) => {
    const v = value(row);
    if (v !== null && v !== undefined && v !== '') seen.add(String(v));
  });
  return [...seen].sort().map((v) => ({ value: v, label: label(v) }));
}

export interface TableFilters {
  defs: FilterDef[];
  values: FilterValues;
  /** How many filters are set. */
  count: number;
  open: boolean;
  setOpen: (open: boolean) => void;
  setValues: (values: FilterValues) => void;
  apply: <T>(rows: T[]) => T[];
  params: Record<string, string>;
}

/**
 * The filter state of one table. It is remembered for the browser tab under the page's address,
 * so opening a record and coming back, or reloading, keeps the filters.
 */
export function useFilters(defs: FilterDef[], options: { id?: string; onChange?: () => void } = {}): TableFilters {
  const { pathname } = useLocation();
  const storeKey = `${STORE_PREFIX}${pathname}${options.id ? `#${options.id}` : ''}`;
  const read = (): FilterValues => {
    try {
      return JSON.parse(sessionStorage.getItem(storeKey) ?? '{}') as FilterValues;
    } catch {
      return {};
    }
  };
  const [values, setState] = useState<FilterValues>(read);
  const [open, setOpen] = useState(false);
  const loadedKey = useRef(storeKey);

  // Another list reusing the same screen (a different master or document kind) has its own filters.
  useEffect(() => {
    if (loadedKey.current !== storeKey) {
      loadedKey.current = storeKey;
      setState(read());
      setOpen(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeKey]);

  const active = useMemo(() => clean(defs, values), [defs, values]);

  function setValues(next: FilterValues) {
    const kept = clean(defs, next);
    setState(kept);
    try {
      if (Object.keys(kept).length) sessionStorage.setItem(storeKey, JSON.stringify(kept));
      else sessionStorage.removeItem(storeKey);
    } catch {
      // Not remembered, but applied.
    }
    options.onChange?.();
  }

  return {
    defs,
    values: active,
    count: Object.keys(active).length,
    open,
    setOpen,
    setValues,
    apply: (rows) => applyFilters(rows, defs, active),
    params: filterParams(defs, active),
  };
}

/**
 * A list the server pages, with filters applied in the browser. With no filter set the server
 * pages as usual. With filters, its newest rows are loaded once and filtered and paged here.
 */
export function useFilteredPage<T = any>(filters: TableFilters, basePath: string, params: Record<string, unknown>, page: number, size: number) {
  const filtering = filters.count > 0;
  const list = useFetch<Page<T>>(`${basePath}${query({ ...params, page: filtering ? 0 : page, size: filtering ? FILTER_ROW_LIMIT : size })}`);
  const content = list.data?.content ?? [];
  if (!filtering) {
    return { rows: content, loading: list.loading, error: list.error, reload: list.reload, totalPages: list.data?.totalPages ?? 0, totalElements: list.data?.totalElements ?? 0, capped: false };
  }
  const matching = filters.apply(content);
  return {
    rows: matching.slice(page * size, (page + 1) * size),
    loading: list.loading,
    error: list.error,
    reload: list.reload,
    totalPages: Math.ceil(matching.length / size),
    totalElements: matching.length,
    // More rows exist than were looked at: said on screen, so nobody takes the result for complete.
    capped: (list.data?.totalElements ?? 0) > content.length,
  };
}

/** The Filters button of a table's toolbar, with the number of filters that are set. */
export function FilterButton({ filters }: { filters: TableFilters }) {
  if (filters.defs.length === 0) return null;
  return (
    <button type="button" className={`btn filter-btn ${filters.open ? 'primary' : ''}`} aria-expanded={filters.open} onClick={() => filters.setOpen(!filters.open)}>
      <ListFilter size={16} />{t('Filters')}{filters.count > 0 && <span className="filter-count">{filters.count}</span>}
    </button>
  );
}

function rangeText(def: FilterDef, { from, to }: Range): string {
  const show = (v: string) => (def.kind === 'date' ? formatDate(v) : formatQty(v));
  if (from && to) return t('{0} to {1}', { 0: show(from), 1: show(to) });
  return from ? t('from {0}', { 0: show(from) }) : t('up to {0}', { 0: show(to!) });
}

function chipText(def: FilterDef, value: FilterValue): string {
  switch (def.kind) {
    case 'text': return `"${String(value).trim()}"`;
    case 'bool': return value === 'true' ? t('Yes') : t('No');
    case 'choice': return (value as string[]).map((v) => t(def.options?.find((o) => o.value === v)?.label ?? v)).join(', ');
    default: return rangeText(def, value as Range);
  }
}

/**
 * Goes under the toolbar: the panel where filters are chosen (while open) and a chip for each
 * filter that is set. {@code note} adds a line such as how many rows match.
 */
export function FilterPanel({ filters, note }: { filters: TableFilters; note?: string }) {
  const [draft, setDraft] = useState<FilterValues>(filters.values);
  useEffect(() => {
    if (filters.open) setDraft(filters.values);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters.open]);

  if (filters.defs.length === 0 || (!filters.open && filters.count === 0)) return null;
  const set = (key: string, value: FilterValue) => setDraft((prev) => ({ ...prev, [key]: value }));
  const apply = () => { filters.setValues(draft); filters.setOpen(false); };
  const remove = (key: string) => {
    const next = { ...filters.values };
    delete next[key];
    filters.setValues(next);
  };
  const clear = () => { filters.setValues({}); setDraft({}); };

  return (
    <>
      {filters.open && (
        <form className="filter-panel" aria-label={t('Filters')} onSubmit={(e) => { e.preventDefault(); apply(); }}>
          <div className="filter-grid">
            {filters.defs.map((def) => (
              <div className="field" key={def.key}>
                <span className="field-label">{t(def.label)}</span>
                <FilterInput def={def} value={draft[def.key]} onChange={(value) => set(def.key, value)} />
              </div>
            ))}
          </div>
          <div className="filter-foot">
            <button type="button" className="link-btn" onClick={clear}>{t('Clear all')}</button>
            <div className="row">
              <Button onClick={() => filters.setOpen(false)}>{t('Cancel')}</Button>
              <Button variant="primary" type="submit">{t('Apply filters')}</Button>
            </div>
          </div>
        </form>
      )}
      {filters.count > 0 && (
        <div className="filter-chips">
          {filters.defs.filter((d) => isSet(filters.values[d.key])).map((def) => (
            <span className="filter-chip" key={def.key}>
              <i>{t(def.label)}</i>{chipText(def, filters.values[def.key])}
              <button type="button" aria-label={t('Remove filter {0}', { 0: t(def.label) })} onClick={() => remove(def.key)}><X size={12} /></button>
            </span>
          ))}
          <button type="button" className="link-btn" onClick={clear}>{t('Clear all')}</button>
          {note && <span className="filter-note">{note}</span>}
        </div>
      )}
    </>
  );
}

const firstOfMonth = (monthsAgo: number) => {
  const d = new Date();
  d.setMonth(d.getMonth() - monthsAgo, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
};
const lastOfLastMonth = () => {
  const d = new Date();
  d.setDate(0);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/** The control for one filter: a search box, ticks, a from-to pair or a yes/no choice. */
function FilterInput({ def, value, onChange }: { def: FilterDef; value: FilterValue | undefined; onChange: (value: FilterValue) => void }) {
  const label = t(def.label);
  if (def.kind === 'text') {
    return <input className="input" aria-label={label} placeholder={t('Contains')} value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value)} />;
  }
  if (def.kind === 'bool') {
    return <Choice label={label} options={[{ value: 'true', label: 'Yes' }, { value: 'false', label: 'No' }]} single
      value={value ? [value as string] : []} onChange={(v) => onChange(v[0] ?? '')} />;
  }
  if (def.kind === 'choice') {
    return def.lookupType
      ? <LookupChoice label={label} type={def.lookupType} value={(value as string[]) ?? []} onChange={onChange} />
      : <Choice label={label} options={def.options ?? []} dropdown={def.dropdown} value={(value as string[]) ?? []} onChange={onChange} />;
  }
  const range = (value as Range) ?? {};
  const type = def.kind === 'date' ? 'date' : 'number';
  return (
    <>
      <div className="filter-range">
        <input className={`input ${type === 'number' ? 'num' : ''}`} type={type} step="any" aria-label={t('{0} from', { 0: label })} placeholder={t('From')}
          value={range.from ?? ''} onChange={(e) => onChange({ ...range, from: e.target.value })} />
        <input className={`input ${type === 'number' ? 'num' : ''}`} type={type} step="any" aria-label={t('{0} to', { 0: label })} placeholder={t('To')}
          value={range.to ?? ''} onChange={(e) => onChange({ ...range, to: e.target.value })} />
      </div>
      {def.kind === 'date' && (
        <div className="filter-opts">
          <button type="button" className="filter-opt" onClick={() => onChange({ from: today(), to: today() })}>{t('Today')}</button>
          <button type="button" className="filter-opt" onClick={() => onChange({ from: firstOfMonth(0), to: today() })}>{t('This month')}</button>
          <button type="button" className="filter-opt" onClick={() => onChange({ from: firstOfMonth(1), to: lastOfLastMonth() })}>{t('Last month')}</button>
        </div>
      )}
    </>
  );
}

function LookupChoice({ type, ...rest }: { label: string; type: string; value: string[]; onChange: (value: string[]) => void }) {
  const options = useLookup(type);
  return <Choice options={options} dropdown {...rest} />;
}

const MAX_PILLS = 6;

/** Ticks for a short fixed list; otherwise a dropdown to search and tick several values in. */
function Choice({ label, options, value, onChange, single, dropdown }: { label: string; options: Option[]; value: string[]; onChange: (value: string[]) => void; single?: boolean; dropdown?: boolean }) {
  const toggle = (v: string) => onChange(value.includes(v) ? value.filter((x) => x !== v) : single ? [v] : [...value, v]);
  if (dropdown || options.length > MAX_PILLS) return <MultiSelect label={label} options={options} value={value} onToggle={toggle} onClear={() => onChange([])} />;
  return (
    <div className="filter-opts" role="group" aria-label={label}>
      {options.map((o) => (
        <button type="button" key={o.value} className={`filter-opt ${value.includes(o.value) ? 'on' : ''}`} aria-pressed={value.includes(o.value)} onClick={() => toggle(o.value)}>{t(o.label)}</button>
      ))}
      {options.length === 0 && <span className="muted">-</span>}
    </div>
  );
}

/**
 * A dropdown for picking several values from a long list: type to search, tick as many as needed.
 * What is picked shows on the button, and as removable tags underneath.
 */
function MultiSelect({ label, options, value, onToggle, onClear }: { label: string; options: Option[]; value: string[]; onToggle: (value: string) => void; onClear: () => void }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const box = useRef<HTMLDivElement>(null);
  const text = (o: Option) => t(o.label);
  const needle = search.trim().toLowerCase();
  const shown = needle ? options.filter((o) => text(o).toLowerCase().includes(needle) || o.value.toLowerCase().includes(needle)) : options;
  const picked = options.filter((o) => value.includes(o.value));

  // Clicking anywhere else, or pressing Escape, closes the list.
  useEffect(() => {
    if (!open) return;
    const outside = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); setOpen(false); } };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape, true);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape, true);
    };
  }, [open]);

  const summary = picked.length === 0 ? t('Choose') : picked.length <= 2 ? picked.map(text).join(', ') : t('{0} selected', { 0: picked.length });
  return (
    <div className="multi" ref={box}>
      <button type="button" className={`input multi-button ${picked.length ? '' : 'empty'}`} role="combobox" aria-haspopup="listbox" aria-expanded={open} aria-label={label}
        onClick={() => { setOpen(!open); setSearch(''); }}>
        <span>{summary}</span><ChevronDown size={16} />
      </button>
      {open && (
        <div className="multi-menu">
          <div className="multi-search">
            <Search size={15} />
            <input className="input" type="search" autoFocus aria-label={t('Search {0}', { 0: label })} placeholder={t('Search')} value={search} onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (shown.length === 1) onToggle(shown[0].value); } }} />
          </div>
          <div className="multi-list" role="listbox" aria-multiselectable="true" aria-label={label}>
            {shown.map((o) => (
              <label key={o.value} className="multi-option" role="option" aria-selected={value.includes(o.value)}>
                <input type="checkbox" checked={value.includes(o.value)} onChange={() => onToggle(o.value)} />{text(o)}
              </label>
            ))}
            {shown.length === 0 && <div className="multi-empty">{t('No matches')}</div>}
          </div>
          <div className="multi-foot">
            <span className="muted">{t('{0} selected', { 0: picked.length })}</span>
            <span className="row">
              {picked.length > 0 && <button type="button" className="link-btn" onClick={onClear}>{t('Clear')}</button>}
              <button type="button" className="link-btn" onClick={() => setOpen(false)}>{t('Done')}</button>
            </span>
          </div>
        </div>
      )}
      {picked.length > 0 && (
        <div className="filter-opts">
          {picked.map((o) => (
            <button type="button" key={o.value} className="filter-opt on" aria-label={t('Remove {0}', { 0: text(o) })} onClick={() => onToggle(o.value)}>{text(o)} <X size={11} /></button>
          ))}
        </div>
      )}
    </div>
  );
}
