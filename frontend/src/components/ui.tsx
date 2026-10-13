import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
  type ButtonHTMLAttributes, type ReactNode,
} from 'react';
import { AlertTriangle, ArrowLeft, CheckCircle2, ChevronLeft, ChevronRight, Inbox, Search, X, XCircle, type LucideIcon } from 'lucide-react';
import { ApiError } from '../core/api';
import { humanize, statusTone, type Tone } from '../core/format';
import { t, tx } from '../core/i18n';

/* The small set of building blocks every screen is made from. */

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'primary' | 'danger' | 'ghost';
  small?: boolean;
  icon?: LucideIcon;
  busy?: boolean;
}

export function Button({ variant = 'default', small, icon: Icon, busy, children, className = '', disabled, ...rest }: ButtonProps) {
  const classes = ['btn', variant !== 'default' ? variant : '', small ? 'sm' : '', className].filter(Boolean).join(' ');
  return (
    <button type="button" className={classes} disabled={disabled || busy} {...rest}>
      {busy ? <span className="spinner" style={{ width: 14, height: 14 }} /> : Icon && <Icon size={small ? 14 : 16} />}
      {tx(children)}
    </button>
  );
}

export function IconButton({ icon: Icon, label, ...rest }: { icon: LucideIcon; label: string } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" className="icon-btn" aria-label={t(label)} title={t(label)} {...rest}>
      <Icon size={18} />
    </button>
  );
}

export function Card({ title, actions, children, className = '', flush }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; flush?: boolean }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-head" style={flush ? { paddingBottom: 14 } : undefined}>
          <h3 className="card-title">{tx(title)}</h3>
          {actions}
        </div>
      )}
      {flush ? children : <div className="card-body">{children}</div>}
    </section>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1 className="page-title">{t(title)}</h1>
        {subtitle && <p className="page-sub">{t(subtitle)}</p>}
      </div>
      {actions && <div className="row wrap">{actions}</div>}
    </div>
  );
}

export function Badge({ tone = 'neutral', children, plain }: { tone?: Tone | 'primary'; children: ReactNode; plain?: boolean }) {
  return <span className={`badge tone-${tone} ${plain ? 'plain' : ''}`}>{children}</span>;
}

/** A status code shown as a coloured pill; labels can be overridden per screen. */
export function StatusBadge({ status, labels }: { status: string; labels?: Record<string, string> }) {
  return <Badge tone={statusTone(status)}>{labels?.[status] ? t(labels[status]) : humanize(status)}</Badge>;
}

export function Stat({ icon: Icon, label, value, foot, tone = 'primary' }: { icon: LucideIcon; label: string; value: ReactNode; foot?: ReactNode; tone?: Tone | 'primary' }) {
  return (
    <div className="card stat">
      <div className={`stat-icon tone-${tone}`}><Icon size={22} /></div>
      <div className="grow">
        <div className="stat-label">{t(label)}</div>
        <div className="stat-value">{value}</div>
        {foot && <div className="stat-foot">{tx(foot)}</div>}
      </div>
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, options }: { value: T; onChange: (value: T) => void; options: { value: T; label: string }[] }) {
  return (
    <div className="tabs" role="tablist">
      {options.map((o) => (
        <button key={o.value} type="button" role="tab" aria-selected={o.value === value} className={`tab ${o.value === value ? 'active' : ''}`} onClick={() => onChange(o.value)}>
          {t(o.label)}
        </button>
      ))}
    </div>
  );
}

export function SearchBox({ value, onChange, placeholder = 'Search' }: { value: string; onChange: (value: string) => void; placeholder?: string }) {
  return (
    <div className="search">
      <Search size={16} />
      <input className="input" value={value} placeholder={t(placeholder)} onChange={(e) => onChange(e.target.value)} aria-label={t(placeholder)} />
    </div>
  );
}

export function Spinner() {
  return <div className="loading"><span className="spinner" /></div>;
}

export function Empty({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon"><Inbox size={24} /></div>
      <strong style={{ color: 'var(--text)' }}>{t(title)}</strong>
      {hint && <span>{t(hint)}</span>}
      {action}
    </div>
  );
}

export function Banner({ tone, children }: { tone: Tone; children: ReactNode }) {
  const Icon = tone === 'success' ? CheckCircle2 : tone === 'danger' ? XCircle : AlertTriangle;
  return (
    <div className={`banner tone-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <Icon size={18} style={{ flex: 'none', marginTop: 1 }} />
      <div>{tx(children)}</div>
    </div>
  );
}

export function Progress({ value, max }: { value: number; max: number }) {
  const pct = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0;
  return <div className="progress" title={`${pct}%`}><span style={{ width: `${pct}%` }} /></div>;
}

/* ---- overlays ---- */

function useEscape(onClose: () => void) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);
}

interface SheetProps {
  title: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}

export function Modal({ title, onClose, children, footer, wide }: SheetProps) {
  useEscape(onClose);
  return (
    <div className="overlay center" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true">
        <div className="sheet-head"><h2 className="sheet-title">{tx(title)}</h2><IconButton icon={X} label="Close" onClick={onClose} /></div>
        <div className="sheet-body">{children}</div>
        {footer && <div className="sheet-foot">{footer}</div>}
      </div>
    </div>
  );
}

/** A record opened as its own page: back arrow and title on top, actions pinned at the bottom. */
export function FullPage({ title, onClose, children, footer, wide }: SheetProps) {
  // The content area keeps its scroll position between routes, so start each record at the top.
  useEffect(() => { document.querySelector('.content')?.scrollTo(0, 0); }, []);
  return (
    <div className={`page full-page ${wide ? 'wide' : ''}`}>
      <section className="card full-sheet">
        <div className="sheet-head" style={{ justifyContent: 'flex-start' }}>
          <IconButton icon={ArrowLeft} label="Back to the list" onClick={onClose} /><h2 className="sheet-title">{tx(title)}</h2>
        </div>
        <div className="sheet-body">{children}</div>
        {footer && <div className="sheet-foot">{footer}</div>}
      </section>
    </div>
  );
}

/* ---- toasts and confirmation, available anywhere through useFeedback() ---- */

interface ConfirmOptions {
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
}

interface Feedback {
  success: (message: string) => void;
  error: (error: unknown) => void;
  confirm: (options: ConfirmOptions) => Promise<boolean>;
}

const FeedbackContext = createContext<Feedback | null>(null);
const TOAST_MS = 4500;

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<{ id: number; message: string; error: boolean }[]>([]);
  const [confirming, setConfirming] = useState<(ConfirmOptions & { resolve: (ok: boolean) => void }) | null>(null);
  const nextId = useRef(1);

  const push = useCallback((message: string, error: boolean) => {
    const id = nextId.current++;
    setToasts((list) => [...list, { id, message, error }]);
    setTimeout(() => setToasts((list) => list.filter((tpl) => tpl.id !== id)), TOAST_MS);
  }, []);

  const value = useMemo<Feedback>(() => ({
    success: (message) => push(t(message), false),
    error: (error) => push(t(error instanceof ApiError || error instanceof Error ? error.message : String(error)), true),
    confirm: (options) => new Promise<boolean>((resolve) => setConfirming({ ...options, resolve })),
  }), [push]);

  const settle = (ok: boolean) => {
    confirming?.resolve(ok);
    setConfirming(null);
  };

  return (
    <FeedbackContext.Provider value={value}>
      {children}
      {confirming && (
        <Modal title={t(confirming.title)} onClose={() => settle(false)} footer={
          <>
            <Button onClick={() => settle(false)}>Cancel</Button>
            <Button variant={confirming.danger ? 'danger' : 'primary'} onClick={() => settle(true)} autoFocus>{t(confirming.confirmLabel ?? 'Confirm')}</Button>
          </>
        }>
          {tx(confirming.message)}
        </Modal>
      )}
      <div className="toasts" aria-live="polite">
        {toasts.map((tpl) => (
          <div key={tpl.id} className={`toast ${tpl.error ? 'error' : ''}`}>
            {tpl.error ? <XCircle size={18} color="var(--danger)" /> : <CheckCircle2 size={18} color="var(--success)" />}
            <span>{tpl.message}</span>
          </div>
        ))}
      </div>
    </FeedbackContext.Provider>
  );
}

export function useFeedback(): Feedback {
  const context = useContext(FeedbackContext);
  if (!context) throw new Error('useFeedback must be used inside FeedbackProvider');
  return context;
}

/* ---- data table ---- */

export interface Column<T = any> {
  key: string;
  label: string;
  /** money, number and date are formatted and aligned for you; status renders a badge. */
  align?: 'right';
  /** Lets long text wrap instead of widening the table. */
  wrap?: boolean;
  sortable?: boolean;
  render: (row: T) => ReactNode;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[];
  loading?: boolean;
  rowKey?: (row: T) => string | number;
  onRowClick?: (row: T) => void;
  sort?: string;
  onSort?: (sort: string) => void;
  emptyTitle?: string;
  emptyHint?: string;
  footer?: ReactNode;
}

export function DataTable<T extends Record<string, any>>({ columns, rows, loading, rowKey, onRowClick, sort, onSort, emptyTitle = 'Nothing here yet', emptyHint, footer }: DataTableProps<T>) {
  if (loading && rows.length === 0) return <Spinner />;
  if (rows.length === 0) return <Empty title={emptyTitle} hint={emptyHint} />;
  const [sortKey, sortDir] = (sort ?? '').split(',');
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className={`${c.align === 'right' ? 'right' : ''} ${c.sortable && onSort ? 'sortable' : ''}`}
                onClick={c.sortable && onSort ? () => onSort(`${c.key},${sortKey === c.key && sortDir === 'asc' ? 'desc' : 'asc'}`) : undefined}>
                {t(c.label)}{sortKey === c.key ? (sortDir === 'asc' ? ' ↑' : ' ↓') : ''}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={rowKey ? rowKey(row) : row.id ?? index} className={onRowClick ? 'clickable' : ''} onClick={onRowClick ? () => onRowClick(row) : undefined}>
              {columns.map((c) => <td key={c.key} className={c.align === 'right' ? 'num' : c.wrap ? 'wrap' : ''}>{c.render(row)}</td>)}
            </tr>
          ))}
        </tbody>
        {footer && <tfoot>{footer}</tfoot>}
      </table>
    </div>
  );
}

export function Pager({ page, totalPages, totalElements, onPage }: { page: number; totalPages: number; totalElements: number; onPage: (page: number) => void }) {
  if (totalElements === 0) return null;
  return (
    <div className="pager">
      <span>{t(totalElements === 1 ? '{count} record' : '{count} records', { count: totalElements })}</span>
      <div className="row">
        <span>{t('Page {page} of {pages}', { page: page + 1, pages: Math.max(totalPages, 1) })}</span>
        <IconButton icon={ChevronLeft} label="Previous page" disabled={page === 0} onClick={() => onPage(page - 1)} />
        <IconButton icon={ChevronRight} label="Next page" disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)} />
      </div>
    </div>
  );
}
