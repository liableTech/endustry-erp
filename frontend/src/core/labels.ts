/*
 * The label service on the screen: each client's own words for customers, items, machines and the
 * rest. They arrive with the session (industry template words, plus anything the Owner renamed
 * under Settings > Labels) and replace the standard words in menus, titles and forms.
 */

export interface Term {
  singular: string;
  plural: string;
}

export type Labels = Record<string, Term>;

/** Used until the session arrives, and for any term the server does not send. */
export const STANDARD_LABELS: Labels = {
  customer: { singular: 'Customer', plural: 'Customers' },
  vendor: { singular: 'Vendor', plural: 'Vendors' },
  item: { singular: 'Item', plural: 'Items' },
  warehouse: { singular: 'Warehouse', plural: 'Warehouses' },
  machine: { singular: 'Machine', plural: 'Machines' },
  employee: { singular: 'Employee', plural: 'Employees' },
  quotation: { singular: 'Quotation', plural: 'Quotations' },
  salesOrder: { singular: 'Sales order', plural: 'Sales orders' },
  salesInvoice: { singular: 'Sales invoice', plural: 'Sales invoices' },
  purchaseOrder: { singular: 'Purchase order', plural: 'Purchase orders' },
  goodsReceipt: { singular: 'Goods receipt', plural: 'Goods receipts' },
  purchaseInvoice: { singular: 'Purchase invoice', plural: 'Purchase invoices' },
  workOrder: { singular: 'Work order', plural: 'Work orders' },
  bom: { singular: 'Bill of material', plural: 'Bills of material' },
  inspection: { singular: 'Inspection', plural: 'Inspections' },
};

export function term(labels: Labels | null | undefined, key: string): Term {
  return labels?.[key] ?? STANDARD_LABELS[key] ?? { singular: key, plural: key };
}

/**
 * A word as it reads mid-sentence: "New sales order", but "New GST rate" keeps its capitals.
 * Only the first letter of a capitalised ordinary word is lowered.
 */
export function inSentence(word: string): string {
  return /^[A-Z][a-z]/.test(word) ? word[0].toLowerCase() + word.slice(1) : word;
}
