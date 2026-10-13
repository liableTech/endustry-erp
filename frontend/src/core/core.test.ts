import { inSentence, term } from './labels';
import { describe, expect, it } from 'vitest';
import { formatCompact, formatDate, formatMoney, formatQty, humanize, statusTone } from './format';
import { contrastOn } from './theme';
import { computeTotals, defaults, isValidGstin, PATTERNS, toPayload, validate, type FieldDef } from './validation';

describe('Indian formats', () => {
  it('groups money in lakhs and crores', () => {
    expect(formatMoney(1234567.5)).toBe('₹12,34,567.50');
    expect(formatMoney(0)).toBe('₹0.00');
    expect(formatMoney(null)).toBe('₹0.00');
    expect(formatMoney('abc')).toBe('-');
  });

  it('shortens large amounts for dashboard tiles', () => {
    expect(formatCompact(950)).toBe('₹950');
    expect(formatCompact(45000)).toBe('₹45 K');
    expect(formatCompact(425000)).toBe('₹4.25 L');
    expect(formatCompact(12000000)).toBe('₹1.2 Cr');
    expect(formatCompact(-250000)).toBe('-₹2.5 L');
  });

  it('shows dates as DD-MM-YYYY', () => {
    expect(formatDate('2026-10-08')).toBe('08-10-2026');
    expect(formatDate('2026-10-08T10:15:00Z')).toBe('08-10-2026');
    expect(formatDate(null)).toBe('-');
    expect(formatDate('')).toBe('-');
  });

  it('trims quantities to at most three decimals', () => {
    expect(formatQty(865.2)).toBe('865.2');
    expect(formatQty(1200)).toBe('1,200');
    expect(formatQty('x')).toBe('-');
  });

  it('turns status codes into words and tones', () => {
    expect(humanize('PARTLY_PAID')).toBe('Partly paid');
    expect(statusTone('PAID')).toBe('success');
    expect(statusTone('CANCELLED')).toBe('danger');
    expect(statusTone('SOMETHING_NEW')).toBe('neutral');
  });
});

describe('GSTIN check', () => {
  it('accepts a GSTIN with the right check digit', () => {
    expect(isValidGstin('27AAPFU0939F1ZV')).toBe(true);
  });

  it('rejects a wrong check digit, wrong case and wrong shape', () => {
    expect(isValidGstin('27AAPFU0939F1ZA')).toBe(false);
    expect(isValidGstin('27aapfu0939f1zv')).toBe(false);
    expect(isValidGstin('27AAPFU0939F1Z')).toBe(false);
    expect(isValidGstin('')).toBe(false);
  });
});

describe('form validation', () => {
  const fields: FieldDef[] = [
    { name: 'code', label: 'Code', type: 'text', required: true, pattern: PATTERNS.code, uppercase: true },
    { name: 'gstRate', label: 'GST', type: 'number', required: true, min: 0, max: 40 },
    { name: 'gstin', label: 'GSTIN', type: 'text' },
    { name: 'msme', label: 'MSME', type: 'checkbox' },
    { name: 'udyam', label: 'Udyam', type: 'text', required: true, pattern: PATTERNS.udyam, showIf: (v) => v.msme },
    { name: 'notes', label: 'Notes', type: 'text', maxLength: 5 },
  ];

  it('passes a complete, well-formed record', () => {
    expect(validate(fields, { code: 'RM-01', gstRate: 18, gstin: '27AAPFU0939F1ZV', msme: false, notes: 'ok' })).toEqual({});
  });

  it('reports every problem at once', () => {
    const errors = validate(fields, { code: 'x', gstRate: 55, gstin: 'NOPE', msme: true, udyam: '', notes: 'too long' });
    expect(Object.keys(errors).sort()).toEqual(['code', 'gstRate', 'gstin', 'notes', 'udyam']);
    expect(errors.gstRate).toBe('cannot be more than 40');
    expect(errors.udyam).toBe('is required');
  });

  it('treats zero as a value but blank as missing', () => {
    expect(validate(fields, { code: 'AB', gstRate: 0 })).toEqual({});
    expect(validate(fields, { code: 'AB', gstRate: '' }).gstRate).toBe('is required');
    expect(validate(fields, { code: 'AB', gstRate: 'abc' }).gstRate).toBe('must be a number');
    expect(validate(fields, { code: 'AB', gstRate: -1 }).gstRate).toBe('cannot be less than 0');
  });

  it('ignores a field that is hidden', () => {
    expect(validate(fields, { code: 'AB', gstRate: 5, msme: false, udyam: 'garbage' })).toEqual({});
  });

  it('builds defaults and a clean payload', () => {
    expect(defaults([{ name: 'active', label: 'Active', type: 'checkbox', default: true }, { name: 'name', label: 'Name', type: 'text' }]))
      .toEqual({ active: true, name: '' });
    expect(toPayload(fields, { code: ' rm-01 ', gstRate: '18', gstin: '', msme: 1, udyam: '', notes: ' a ' }))
      .toEqual({ code: 'RM-01', gstRate: 18, gstin: null, msme: true, udyam: null, notes: 'a' });
  });
});

describe('document totals preview', () => {
  it('splits tax equally within the state', () => {
    const t = computeTotals([{ qty: 10, rate: 1000, discountPct: 10, taxRatePct: 18 }], true);
    expect(t).toMatchObject({ subTotal: 10000, discount: 1000, taxable: 9000, cgst: 810, sgst: 810, igst: 0, grandTotal: 10620 });
  });

  it('charges IGST across states', () => {
    const t = computeTotals([{ qty: 3, rate: 333.33, taxRatePct: 18 }], false);
    expect(t).toMatchObject({ taxable: 999.99, igst: 180, cgst: 0, sgst: 0, grandTotal: 1179.99 });
  });

  it('keeps CGST plus SGST equal to the tax when it cannot be halved', () => {
    const t = computeTotals([{ qty: 1, rate: 0.25, taxRatePct: 18 }], true);
    expect(t.cgst + t.sgst).toBeCloseTo(t.tax, 2);
  });

  it('treats blank inputs as zero', () => {
    expect(computeTotals([{ qty: '', rate: '' }], true).grandTotal).toBe(0);
    expect(computeTotals([], true).grandTotal).toBe(0);
  });
});

describe('theme', () => {
  it('picks readable text for a background colour', () => {
    expect(contrastOn('#4F46E5')).toBe('#FFFFFF');
    expect(contrastOn('#FDE047')).toBe('#111827');
    expect(contrastOn('#000000')).toBe('#FFFFFF');
  });
});

describe('labels', () => {
  it('lowers an ordinary word mid-sentence but keeps acronyms', () => {
    expect(inSentence('Sales order')).toBe('sales order');
    expect(inSentence('Style BOM')).toBe('style BOM');
    expect(inSentence('BOM')).toBe('BOM');
  });

  it('falls back to the standard word for a term the session lacks', () => {
    expect(term({ customer: { singular: 'Client', plural: 'Clients' } }, 'customer').plural).toBe('Clients');
    expect(term({}, 'vendor').singular).toBe('Vendor');
    expect(term(null, 'item').plural).toBe('Items');
  });
});
