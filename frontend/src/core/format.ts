import { t } from './i18n';
/** Indian formats throughout (USB-05): lakh / crore grouping, rupee symbol, DD-MM-YYYY. */

const money = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const moneyWhole = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const quantity = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 });

const LAKH = 100_000;
const CRORE = 10_000_000;

export function formatMoney(value: unknown, whole = false): string {
  const n = Number(value ?? 0);
  if (!Number.isFinite(n)) return '-';
  return (whole ? moneyWhole : money).format(n);
}

/** Short form for dashboard tiles: ₹4.25 L, ₹1.2 Cr. */
export function formatCompact(value: unknown): string {
  const n = Number(value ?? 0);
  if (!Number.isFinite(n)) return '-';
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  if (abs >= CRORE) return `${sign}₹${trim(abs / CRORE)} Cr`;
  if (abs >= LAKH) return `${sign}₹${trim(abs / LAKH)} L`;
  if (abs >= 1000) return `${sign}₹${trim(abs / 1000)} K`;
  return `${sign}₹${trim(abs)}`;
}

function trim(n: number): string {
  return n.toFixed(2).replace(/\.?0+$/, '');
}

export function formatQty(value: unknown): string {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? quantity.format(n) : '-';
}

/** ISO yyyy-mm-dd (or a timestamp) to DD-MM-YYYY. */
export function formatDate(value: unknown): string {
  if (!value) return '-';
  const text = String(value);
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(text);
  return match ? `${match[3]}-${match[2]}-${match[1]}` : text;
}

export function formatDateTime(value: unknown): string {
  if (!value) return '-';
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getDate())}-${pad(date.getMonth() + 1)}-${date.getFullYear()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function today(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** "PARTLY_PAID" to "Partly paid". */
export function humanize(value: unknown): string {
  const text = String(value ?? '').replace(/[_-]+/g, ' ').toLowerCase();
  return t(text.charAt(0).toUpperCase() + text.slice(1));
}

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

const TONES: Record<string, Tone> = {
  DRAFT: 'neutral', PLANNED: 'neutral', PENDING: 'warning', OBSOLETE: 'neutral', NONE: 'neutral',
  SENT: 'info', CONFIRMED: 'info', APPROVED: 'info', RELEASED: 'info', RUNNING: 'info', VIEW: 'info', ON_TRACK: 'success',
  PARTLY_FULFILLED: 'warning', PARTLY_PAID: 'warning', IN_PROGRESS: 'warning', PAUSED: 'warning', QC_PENDING: 'warning',
  POSTED: 'info', DUE_SOON: 'warning',
  ACCEPTED: 'success', FULFILLED: 'success', PAID: 'success', COMPLETED: 'success', DONE: 'success', AVAILABLE: 'success', FULL: 'success',
  REJECTED: 'danger', CANCELLED: 'danger', OVERDUE: 'danger', SUSPENDED: 'danger', ACTIVE: 'success', INACTIVE: 'neutral',
};

export function statusTone(status: unknown): Tone {
  return TONES[String(status)] ?? 'neutral';
}

/** Turns rows into a CSV file and hands it to the browser as a download. */
export function downloadCsv(filename: string, headers: string[], rows: unknown[][]) {
  const escape = (cell: unknown) => {
    const text = cell === null || cell === undefined ? '' : String(cell);
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const csv = [headers, ...rows].map((row) => row.map(escape).join(',')).join('\r\n');
  const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
