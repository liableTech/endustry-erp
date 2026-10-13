/**
 * Field definitions drive both how a form is drawn and how it is checked, so a new master screen
 * is a list of fields rather than new code. The server validates again and is the authority.
 */

export type FieldType = 'text' | 'number' | 'select' | 'checkbox' | 'textarea' | 'date' | 'lookup' | 'password' | 'color';

export interface Option {
  value: string;
  label: string;
}

export interface FieldDef {
  name: string;
  label: string;
  type: FieldType;
  required?: boolean;
  /** For select. */
  options?: Option[];
  /** For lookup: the list in the Lists master that supplies the choices. */
  lookupType?: string;
  pattern?: RegExp;
  patternMessage?: string;
  min?: number;
  max?: number;
  maxLength?: number;
  help?: string;
  placeholder?: string;
  /** Columns the field spans in a 2-column form. */
  span?: 1 | 2;
  section?: string;
  default?: unknown;
  /** Show the field only when this returns true. */
  showIf?: (values: Record<string, any>) => boolean;
  /** Not editable once the record exists. */
  lockedOnEdit?: boolean;
  uppercase?: boolean;
}

const GSTIN_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const GSTIN_FORMAT = /^\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;

/** Same mod-36 check the server runs, so a typo is caught before the form is sent. */
export function isValidGstin(gstin: string): boolean {
  if (!GSTIN_FORMAT.test(gstin)) return false;
  let sum = 0;
  for (let i = 0; i < 14; i++) {
    const product = GSTIN_ALPHABET.indexOf(gstin[i]) * (i % 2 === 0 ? 1 : 2);
    sum += Math.floor(product / 36) + (product % 36);
  }
  return GSTIN_ALPHABET[(36 - (sum % 36)) % 36] === gstin[14];
}

export const PATTERNS = {
  code: /^[A-Za-z0-9._-]{2,20}$/,
  pan: /^[A-Z]{5}\d{4}[A-Z]$/,
  stateCode: /^\d{2}$/,
  pincode: /^\d{6}$/,
  phone: /^[0-9+ -]{7,15}$/,
  email: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
  hsn: /^\d{4}(\d{2})?(\d{2})?$/,
  udyam: /^UDYAM-[A-Z]{2}-\d{2}-\d{7}$/,
  hex: /^#[0-9a-fA-F]{6}$/,
};

function isEmpty(value: unknown): boolean {
  return value === undefined || value === null || value === '';
}

/** Returns a message per invalid field; an empty object means the form may be sent. */
export function validate(fields: FieldDef[], values: Record<string, any>): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of fields) {
    if (field.showIf && !field.showIf(values)) continue;
    const value = values[field.name];
    if (isEmpty(value)) {
      if (field.required && field.type !== 'checkbox') errors[field.name] = 'is required';
      continue;
    }
    if (field.type === 'number') {
      const n = Number(value);
      if (!Number.isFinite(n)) errors[field.name] = 'must be a number';
      else if (field.min !== undefined && n < field.min) errors[field.name] = `cannot be less than ${field.min}`;
      else if (field.max !== undefined && n > field.max) errors[field.name] = `cannot be more than ${field.max}`;
      continue;
    }
    const text = String(value);
    if (field.maxLength && text.length > field.maxLength) errors[field.name] = `can be at most ${field.maxLength} characters`;
    else if (field.name === 'gstin' && !isValidGstin(text)) errors[field.name] = 'is not a valid GSTIN';
    else if (field.pattern && !field.pattern.test(text)) errors[field.name] = field.patternMessage ?? 'is not in the expected format';
  }
  return errors;
}

/** Starting values for a new record. */
export function defaults(fields: FieldDef[]): Record<string, any> {
  const values: Record<string, any> = {};
  for (const field of fields) {
    values[field.name] = field.default ?? (field.type === 'checkbox' ? false : '');
  }
  return values;
}

/** Shapes form values for the API: numbers as numbers, blanks as null, text trimmed. */
export function toPayload(fields: FieldDef[], values: Record<string, any>): Record<string, any> {
  const payload: Record<string, any> = { ...values };
  for (const field of fields) {
    const value = values[field.name];
    if (field.type === 'checkbox') payload[field.name] = Boolean(value);
    else if (isEmpty(value)) payload[field.name] = field.type === 'number' ? 0 : null;
    else if (field.type === 'number') payload[field.name] = Number(value);
    else payload[field.name] = field.uppercase ? String(value).trim().toUpperCase() : String(value).trim();
  }
  return payload;
}

export interface LineInput {
  qty: number | string;
  rate: number | string;
  discountPct?: number | string;
  taxRatePct?: number | string;
}

export interface Totals {
  subTotal: number;
  discount: number;
  taxable: number;
  tax: number;
  cgst: number;
  sgst: number;
  igst: number;
  grandTotal: number;
}

const round2 = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;

/** Live preview of a document's totals while it is being typed; mirrors the server's calculation. */
export function computeTotals(lines: LineInput[], intraState: boolean): Totals {
  let subTotal = 0;
  let discount = 0;
  let tax = 0;
  for (const line of lines) {
    const gross = round2(Number(line.qty || 0) * Number(line.rate || 0));
    const lineDiscount = round2((gross * Number(line.discountPct || 0)) / 100);
    subTotal += gross;
    discount += lineDiscount;
    tax += round2(((gross - lineDiscount) * Number(line.taxRatePct || 0)) / 100);
  }
  subTotal = round2(subTotal);
  discount = round2(discount);
  tax = round2(tax);
  const taxable = round2(subTotal - discount);
  const cgst = intraState ? round2(tax / 2) : 0;
  return {
    subTotal, discount, taxable, tax, cgst,
    sgst: intraState ? round2(tax - cgst) : 0,
    igst: intraState ? 0 : tax,
    grandTotal: round2(taxable + tax),
  };
}
