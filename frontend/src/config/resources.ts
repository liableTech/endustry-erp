import type { ResourceConfig } from '../components/ResourcePage';
import { PATTERNS, type FieldDef, type Option } from '../core/validation';

/*
 * Master screens are declared here, not coded. To add a master: expose it on the server with a
 * CrudController, describe its columns and fields below, and add a route and a menu entry.
 */

const options = (...values: string[]): Option[] => values.map((v) => ({ value: v, label: v.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()) }));

const code: FieldDef = {
  name: 'code', label: 'Code', type: 'text', required: true, uppercase: true, pattern: PATTERNS.code,
  patternMessage: 'must be 2 to 20 letters, digits, dot, dash or underscore', lockedOnEdit: true,
};
const active: FieldDef = { name: 'active', label: 'Active', type: 'checkbox', default: true, help: 'Inactive records stay on old transactions but cannot be picked on new ones.' };

const ITEM_TYPES = options('RAW_MATERIAL', 'COMPONENT', 'CONSUMABLE', 'SEMI_FINISHED', 'FINISHED', 'SCRAP', 'SERVICE');

export const items: ResourceConfig = {
  term: 'item', title: 'Items', singular: 'item', endpoint: '/masters/items',
  subtitle: 'Everything you buy, make, stock or sell',
  searchPlaceholder: 'Search by code, name or HSN',
  filters: [{ key: 'type', label: 'Types', options: ITEM_TYPES }],
  columns: [
    { key: 'code', label: 'Code', sortable: true },
    { key: 'name', label: 'Name', sortable: true },
    { key: 'type', label: 'Type', type: 'label' },
    { key: 'uom', label: 'Unit' },
    { key: 'hsnCode', label: 'HSN' },
    { key: 'gstRate', label: 'GST %', type: 'number' },
    { key: 'avgCost', label: 'Avg cost', type: 'money' },
    { key: 'salePrice', label: 'Sale price', type: 'money' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: [
    { ...code, section: 'Identity' },
    { name: 'type', label: 'Type', type: 'select', required: true, options: ITEM_TYPES },
    { name: 'name', label: 'Name', type: 'text', required: true, maxLength: 120, span: 2 },
    { name: 'aliasName', label: 'Shop-floor name', type: 'text', maxLength: 120, help: 'Name in the local language, shown on job cards.', span: 2 },
    { name: 'category', label: 'Category', type: 'lookup', lookupType: 'ITEM_CATEGORY' },
    { name: 'uom', label: 'Unit', type: 'lookup', lookupType: 'UOM', required: true },
    { name: 'hsnCode', label: 'HSN / SAC', type: 'text', section: 'Tax and pricing', pattern: PATTERNS.hsn, patternMessage: 'must be 4, 6 or 8 digits' },
    { name: 'gstRate', label: 'GST rate %', type: 'number', required: true, min: 0, max: 40, default: 18, help: 'Used when the tax rate master has no dated rate for the HSN.' },
    { name: 'purchasePrice', label: 'Purchase price', type: 'number', min: 0, default: 0 },
    { name: 'salePrice', label: 'Sale price', type: 'number', min: 0, default: 0 },
    { name: 'standardCost', label: 'Standard cost', type: 'number', min: 0, default: 0 },
    { name: 'reorderLevel', label: 'Reorder level', type: 'number', min: 0, default: 0, section: 'Stock control' },
    { name: 'leadTimeDays', label: 'Lead time (days)', type: 'number', min: 0, default: 0 },
    { name: 'batchTracked', label: 'Track by batch / heat number', type: 'checkbox', showIf: (v) => v.type !== 'SERVICE' },
    { name: 'qcRequired', label: 'Inspect on receipt', type: 'checkbox', showIf: (v) => v.type !== 'SERVICE', help: 'Received stock waits in QC until quality releases it.' },
    active,
  ],
};

const partyFields = (role: 'CUSTOMER' | 'VENDOR'): FieldDef[] => [
  { ...code, section: 'Identity' },
  { name: 'type', label: 'Type', type: 'select', required: true, default: role, options: [{ value: role, label: role === 'CUSTOMER' ? 'Customer' : 'Vendor' }, { value: 'BOTH', label: 'Customer and vendor' }] },
  { name: 'name', label: 'Name', type: 'text', required: true, maxLength: 150, span: 2 },
  { name: 'stateCode', label: 'State', type: 'lookup', lookupType: 'STATE', required: true, section: 'Tax registration', help: 'Decides CGST + SGST or IGST.' },
  { name: 'gstin', label: 'GSTIN', type: 'text', uppercase: true, maxLength: 15, help: 'Leave blank for an unregistered party.' },
  { name: 'pan', label: 'PAN', type: 'text', uppercase: true, pattern: PATTERNS.pan, patternMessage: 'is not a valid PAN' },
  ...(role === 'VENDOR' ? [
    { name: 'msmeRegistered', label: 'MSME registered (micro or small)', type: 'checkbox' as const, section: 'MSME (43B(h))', span: 2 as const, help: 'Bills must be paid within 45 days at most.' },
    { name: 'udyamNumber', label: 'Udyam number', type: 'text' as const, uppercase: true, pattern: PATTERNS.udyam, patternMessage: 'must look like UDYAM-MH-12-0012345', showIf: (v: any) => v.msmeRegistered, required: true },
    { name: 'msmeAgreedTerms', label: 'Payment terms agreed in writing', type: 'checkbox' as const, showIf: (v: any) => v.msmeRegistered, help: 'Without an agreement the bill is due in 15 days.' },
  ] : []),
  { name: 'creditDays', label: role === 'CUSTOMER' ? 'Credit days' : 'Payment days', type: 'number', min: 0, max: 365, default: 30, section: 'Commercial terms' },
  ...(role === 'CUSTOMER' ? [{ name: 'creditLimit', label: 'Credit limit', type: 'number' as const, min: 0, default: 0, help: 'Zero means no limit is enforced.' }] : []),
  { name: 'contactPerson', label: 'Contact person', type: 'text', section: 'Contact' },
  { name: 'phone', label: 'Phone', type: 'text', pattern: PATTERNS.phone, patternMessage: 'is not a valid phone number' },
  { name: 'email', label: 'Email', type: 'text', pattern: PATTERNS.email, patternMessage: 'is not a valid email address' },
  { name: 'city', label: 'City', type: 'text' },
  { name: 'address', label: 'Address', type: 'textarea', maxLength: 250, span: 2 },
  { name: 'pincode', label: 'PIN code', type: 'text', pattern: PATTERNS.pincode, patternMessage: 'must be 6 digits' },
  active,
];

export const customers: ResourceConfig = {
  term: 'customer', title: 'Customers', singular: 'customer', endpoint: '/masters/parties',
  subtitle: 'Who you sell to, with credit terms',
  baseFilter: { type: 'CUSTOMER,BOTH' },
  searchPlaceholder: 'Search by name, code, GSTIN or city',
  columns: [
    { key: 'code', label: 'Code', sortable: true },
    { key: 'name', label: 'Name', sortable: true },
    { key: 'gstin', label: 'GSTIN' },
    { key: 'city', label: 'City' },
    { key: 'creditDays', label: 'Credit days', type: 'number' },
    { key: 'creditLimit', label: 'Credit limit', type: 'money' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: partyFields('CUSTOMER'),
};

export const vendors: ResourceConfig = {
  term: 'vendor', title: 'Vendors', singular: 'vendor', endpoint: '/masters/parties',
  subtitle: 'Who you buy from, with MSME status for the 45-day rule',
  baseFilter: { type: 'VENDOR,BOTH' },
  searchPlaceholder: 'Search by name, code, GSTIN or city',
  filters: [{ key: 'msmeRegistered', label: 'Vendors', options: [{ value: 'true', label: 'MSME registered' }, { value: 'false', label: 'Not MSME' }] }],
  columns: [
    { key: 'code', label: 'Code', sortable: true },
    { key: 'name', label: 'Name', sortable: true },
    { key: 'gstin', label: 'GSTIN' },
    { key: 'city', label: 'City' },
    { key: 'msmeRegistered', label: 'MSME', type: 'bool' },
    { key: 'creditDays', label: 'Payment days', type: 'number' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: partyFields('VENDOR'),
};

export const warehouses: ResourceConfig = {
  term: 'warehouse', title: 'Warehouses', singular: 'warehouse', endpoint: '/masters/warehouses',
  subtitle: 'Stores and stock locations',
  columns: [
    { key: 'code', label: 'Code', sortable: true },
    { key: 'name', label: 'Name', sortable: true },
    { key: 'address', label: 'Address' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: [code, { name: 'name', label: 'Name', type: 'text', required: true, maxLength: 100 }, { name: 'address', label: 'Address', type: 'textarea', maxLength: 250, span: 2 }, active],
};

export const workCentres: ResourceConfig = {
  term: 'machine', title: 'Machines and work centres', singular: 'work centre', endpoint: '/masters/work-centres',
  subtitle: 'Where operations run, and what an hour there costs',
  columns: [
    { key: 'code', label: 'Code', sortable: true },
    { key: 'name', label: 'Name', sortable: true },
    { key: 'location', label: 'Location' },
    { key: 'hourlyCost', label: 'Cost / hour', type: 'money' },
    { key: 'capacityHoursPerDay', label: 'Hours / day', type: 'number' },
    { key: 'shifts', label: 'Shifts', type: 'number' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: [
    code,
    { name: 'name', label: 'Name', type: 'text', required: true, maxLength: 100 },
    { name: 'location', label: 'Location', type: 'text' },
    { name: 'hourlyCost', label: 'Cost per hour', type: 'number', required: true, min: 0, default: 0, help: 'Used to cost routing operations.' },
    { name: 'capacityHoursPerDay', label: 'Capacity (hours per day)', type: 'number', required: true, min: 0.5, max: 24, default: 8 },
    { name: 'shifts', label: 'Shifts', type: 'number', required: true, min: 1, max: 3, default: 1 },
    active,
  ],
};

export const employees: ResourceConfig = {
  term: 'employee', title: 'Employees', singular: 'employee', endpoint: '/masters/employees',
  subtitle: 'Operators and staff, with wage basis',
  columns: [
    { key: 'code', label: 'Code', sortable: true },
    { key: 'name', label: 'Name', sortable: true },
    { key: 'department', label: 'Department' },
    { key: 'skill', label: 'Skill' },
    { key: 'wageType', label: 'Wage basis', type: 'label' },
    { key: 'wageRate', label: 'Rate', type: 'money' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: [
    code,
    { name: 'name', label: 'Name', type: 'text', required: true, maxLength: 100 },
    { name: 'department', label: 'Department', type: 'text' },
    { name: 'skill', label: 'Skill', type: 'text' },
    { name: 'wageType', label: 'Wage basis', type: 'select', required: true, options: options('PIECE_RATE', 'HOURLY', 'MONTHLY') },
    { name: 'wageRate', label: 'Wage rate', type: 'number', required: true, min: 0, default: 0 },
    { name: 'phone', label: 'Phone', type: 'text', pattern: PATTERNS.phone, patternMessage: 'is not a valid phone number' },
    active,
  ],
};

export const taxRates: ResourceConfig = {
  title: 'GST rates', singular: 'GST rate', endpoint: '/masters/tax-rates',
  subtitle: 'Rate per HSN code from a date, so a rate change never needs items edited',
  columns: [
    { key: 'hsnCode', label: 'HSN', sortable: true },
    { key: 'description', label: 'Description' },
    { key: 'ratePct', label: 'Rate %', type: 'number' },
    { key: 'effectiveFrom', label: 'Effective from', type: 'date', sortable: true },
  ],
  fields: [
    { name: 'hsnCode', label: 'HSN / SAC', type: 'text', required: true, pattern: PATTERNS.hsn, patternMessage: 'must be 4, 6 or 8 digits' },
    { name: 'ratePct', label: 'Rate %', type: 'number', required: true, min: 0, max: 40 },
    { name: 'effectiveFrom', label: 'Effective from', type: 'date', required: true },
    { name: 'description', label: 'Description', type: 'text', span: 2 },
  ],
};

const LIST_TYPES: Option[] = [
  { value: 'UOM', label: 'Units of measure' },
  { value: 'ITEM_CATEGORY', label: 'Item categories' },
  { value: 'REJECTION_REASON', label: 'Rejection reasons' },
  { value: 'DOWNTIME_REASON', label: 'Downtime reasons' },
  { value: 'ADJUSTMENT_REASON', label: 'Stock adjustment reasons' },
  { value: 'STATE', label: 'States (GST codes)' },
];

export const lists: ResourceConfig = {
  title: 'Lists', singular: 'list entry', endpoint: '/masters/lookups',
  subtitle: 'The choices offered in dropdowns across the application',
  filters: [{ key: 'lookupType', label: 'Lists', options: LIST_TYPES }],
  columns: [
    { key: 'lookupType', label: 'List', render: (row) => LIST_TYPES.find((t) => t.value === row.lookupType)?.label ?? row.lookupType },
    { key: 'code', label: 'Code', sortable: true },
    { key: 'name', label: 'Name', sortable: true },
    { key: 'sortOrder', label: 'Order', type: 'number' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: [
    { name: 'lookupType', label: 'List', type: 'select', required: true, options: LIST_TYPES, lockedOnEdit: true },
    { name: 'code', label: 'Code', type: 'text', required: true, maxLength: 30, lockedOnEdit: true },
    { name: 'name', label: 'Name', type: 'text', required: true, maxLength: 100, span: 2 },
    { name: 'sortOrder', label: 'Display order', type: 'number', min: 0, default: 0 },
    active,
  ],
};

const ROLES = options('OWNER', 'ACCOUNTS', 'SALES', 'PURCHASE', 'STORE', 'PRODUCTION', 'SUPERVISOR', 'QUALITY', 'CA');

export const users: ResourceConfig = {
  title: 'Users', singular: 'user', endpoint: '/admin/users',
  subtitle: 'Who can sign in, and in which role',
  canDelete: false,
  filters: [{ key: 'role', label: 'Roles', options: ROLES }],
  columns: [
    { key: 'fullName', label: 'Name', sortable: true },
    { key: 'email', label: 'Email', sortable: true },
    { key: 'role', label: 'Role', type: 'label' },
    { key: 'active', label: 'Active', type: 'bool' },
  ],
  fields: [
    { name: 'fullName', label: 'Full name', type: 'text', required: true, maxLength: 100, span: 2 },
    { name: 'email', label: 'Email', type: 'text', required: true, pattern: PATTERNS.email, patternMessage: 'is not a valid email address' },
    { name: 'role', label: 'Role', type: 'select', required: true, options: ROLES },
    { name: 'password', label: 'Password', type: 'password', help: 'At least 8 characters. Leave blank to keep the current password.', pattern: /^.{8,72}$/, patternMessage: 'must be 8 to 72 characters', span: 2 },
    { ...active, help: 'Deactivating a user ends their access without losing their history.' },
  ],
};
