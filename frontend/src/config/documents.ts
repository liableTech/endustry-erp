/*
 * Every commercial document shares one screen (components/DocumentPage). What differs per kind is
 * declared here: wording, which statuses to offer as tabs, and what each action is called.
 */

export interface ActionDef {
  label: string;
  variant?: 'primary' | 'danger';
  /** When set, the user must confirm with this message first. */
  confirm?: string;
}

export interface DocumentConfig {
  /** URL segment of the kind on the API, e.g. sales-order. */
  slug: string;
  /** Label service term for the document itself. */
  term: string;
  /** Permission module and first API path segment. */
  module: string;
  /** Route of this kind's list screen. */
  route: string;
  title: string;
  singular: string;
  subtitle: string;
  partyRole: 'CUSTOMER' | 'VENDOR';
  partyLabel: string;
  referenceLabel: string;
  dueLabel: string;
  priceField: 'salePrice' | 'purchasePrice';
  needsWarehouse: boolean;
  /** False when the document can only be raised from its source (a goods receipt needs an order). */
  allowDirect: boolean;
  statuses: string[];
  statusLabels?: Record<string, string>;
  actions: Record<string, ActionDef>;
  /** The kind this one is raised from, offered as a "From ..." picker. */
  source?: { slug: string; buttonLabel: string; statuses: string };
}

const cancel: ActionDef = { label: 'Cancel document', variant: 'danger', confirm: 'Cancel this document? This cannot be undone.' };

export const DOCUMENTS: Record<string, DocumentConfig> = {
  quotation: {
    slug: 'quotation', term: 'quotation', module: 'sales', route: '/sales/quotations',
    title: 'Quotations', singular: 'quotation', subtitle: 'Offers sent to customers, with validity',
    partyRole: 'CUSTOMER', partyLabel: 'Customer', referenceLabel: 'Enquiry reference', dueLabel: 'Valid until',
    priceField: 'salePrice', needsWarehouse: false, allowDirect: true,
    statuses: ['DRAFT', 'SENT', 'ACCEPTED', 'FULFILLED', 'REJECTED'],
    statusLabels: { FULFILLED: 'Converted' },
    actions: {
      send: { label: 'Mark as sent', variant: 'primary' },
      accept: { label: 'Customer accepted', variant: 'primary' },
      reject: { label: 'Customer rejected', variant: 'danger', confirm: 'Mark this quotation as rejected by the customer?' },
      cancel,
    },
  },
  'sales-order': {
    slug: 'sales-order', term: 'salesOrder', module: 'sales', route: '/sales/orders',
    title: 'Sales orders', singular: 'sales order', subtitle: 'Confirmed customer orders and what is still to dispatch',
    partyRole: 'CUSTOMER', partyLabel: 'Customer', referenceLabel: 'Customer PO no', dueLabel: 'Delivery date',
    priceField: 'salePrice', needsWarehouse: true, allowDirect: true,
    statuses: ['DRAFT', 'CONFIRMED', 'PARTLY_FULFILLED', 'FULFILLED', 'CANCELLED'],
    statusLabels: { PARTLY_FULFILLED: 'Partly invoiced', FULFILLED: 'Invoiced' },
    actions: { confirm: { label: 'Confirm order', variant: 'primary' }, cancel },
    source: { slug: 'quotation', buttonLabel: 'From quotation', statuses: 'SENT,ACCEPTED' },
  },
  'sales-invoice': {
    slug: 'sales-invoice', term: 'salesInvoice', module: 'sales', route: '/sales/invoices',
    title: 'Sales invoices', singular: 'sales invoice', subtitle: 'GST tax invoices and what customers still owe',
    partyRole: 'CUSTOMER', partyLabel: 'Customer', referenceLabel: 'Customer PO no', dueLabel: 'Due date',
    priceField: 'salePrice', needsWarehouse: true, allowDirect: true,
    statuses: ['DRAFT', 'POSTED', 'PARTLY_PAID', 'PAID', 'CANCELLED'],
    statusLabels: { POSTED: 'Unpaid' },
    actions: { post: { label: 'Post invoice', variant: 'primary', confirm: 'Posting issues the stock and fixes the invoice. Continue?' }, cancel },
    source: { slug: 'sales-order', buttonLabel: 'From sales order', statuses: 'CONFIRMED,PARTLY_FULFILLED' },
  },
  'purchase-order': {
    slug: 'purchase-order', term: 'purchaseOrder', module: 'purchase', route: '/purchase/orders',
    title: 'Purchase orders', singular: 'purchase order', subtitle: 'What is on order from vendors, with approval',
    partyRole: 'VENDOR', partyLabel: 'Vendor', referenceLabel: 'Vendor quotation ref', dueLabel: 'Expected by',
    priceField: 'purchasePrice', needsWarehouse: true, allowDirect: true,
    statuses: ['DRAFT', 'APPROVED', 'PARTLY_FULFILLED', 'FULFILLED', 'CANCELLED'],
    statusLabels: { PARTLY_FULFILLED: 'Partly received', FULFILLED: 'Received' },
    actions: { approve: { label: 'Approve', variant: 'primary' }, cancel },
  },
  grn: {
    slug: 'grn', term: 'goodsReceipt', module: 'inventory', route: '/inventory/receipts',
    title: 'Goods receipts', singular: 'goods receipt', subtitle: 'Material received against purchase orders',
    partyRole: 'VENDOR', partyLabel: 'Vendor', referenceLabel: 'Vendor challan no', dueLabel: 'Due date',
    priceField: 'purchasePrice', needsWarehouse: true, allowDirect: false,
    statuses: ['DRAFT', 'POSTED', 'PARTLY_FULFILLED', 'FULFILLED', 'CANCELLED'],
    statusLabels: { POSTED: 'Received', PARTLY_FULFILLED: 'Partly billed', FULFILLED: 'Billed' },
    actions: { post: { label: 'Receive into stock', variant: 'primary', confirm: 'This brings the quantities into stock. Items marked for inspection wait in QC. Continue?' }, cancel },
    source: { slug: 'purchase-order', buttonLabel: 'Receive against PO', statuses: 'APPROVED,PARTLY_FULFILLED' },
  },
  'purchase-invoice': {
    slug: 'purchase-invoice', term: 'purchaseInvoice', module: 'purchase', route: '/purchase/invoices',
    title: 'Purchase invoices', singular: 'purchase invoice', subtitle: 'Vendor bills matched to receipts, and what you owe',
    partyRole: 'VENDOR', partyLabel: 'Vendor', referenceLabel: 'Vendor invoice no', dueLabel: 'Pay by',
    priceField: 'purchasePrice', needsWarehouse: false, allowDirect: true,
    statuses: ['DRAFT', 'POSTED', 'PARTLY_PAID', 'PAID', 'CANCELLED'],
    statusLabels: { POSTED: 'Unpaid' },
    actions: { post: { label: 'Book invoice', variant: 'primary' }, cancel },
    source: { slug: 'grn', buttonLabel: 'From goods receipt', statuses: 'POSTED,PARTLY_FULFILLED' },
  },
};

/** Wording for the "raise the next document" buttons, keyed by the target kind. */
export const DERIVE_LABELS: Record<string, string> = {
  'sales-order': 'Create sales order',
  'sales-invoice': 'Create invoice',
  grn: 'Receive goods',
  'purchase-invoice': 'Book vendor invoice',
};

/** A document kind in the client's own words: its name, and what it calls the customer or vendor. */
export function relabelDocument(config: DocumentConfig, t: (key: string) => { singular: string; plural: string }, one: (key: string) => string): DocumentConfig {
  const party = config.partyRole === 'CUSTOMER' ? 'customer' : 'vendor';
  return { ...config, title: t(config.term).plural, singular: one(config.term), partyLabel: t(party).singular };
}
