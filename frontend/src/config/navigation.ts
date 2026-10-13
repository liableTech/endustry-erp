import {
  BarChart3, Boxes, Building2, ClipboardCheck, ClipboardList, Cog, Factory, FileSpreadsheet, FileText, Gauge, History,
  Languages, LayoutDashboard, ListTree, Network, Package, PackageCheck, Palette, Percent, Printer, Receipt, ScrollText, ShieldCheck, ShoppingCart,
  SlidersHorizontal, Tags, Truck, UserCog, UserPlus, Users, Warehouse, Wrench, type LucideIcon,
} from 'lucide-react';

export interface NavItem {
  label: string;
  path: string;
  icon: LucideIcon;
  /** Permission module; the item is hidden when the role has no access to it. */
  module: string;
  /** Label service term; when set the menu shows the client's own plural word. */
  term?: string;
  /** A master that only serves other modules: hidden unless at least one of them is switched on. */
  serves?: string[];
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

/** The product admin's menu: clients and the product itself, never a client's data. */
export const PRODUCT_NAVIGATION: NavGroup[] = [
  {
    label: 'Product admin',
    items: [
      { label: 'Clients', path: '/platform/clients', icon: Building2, module: 'platform' },
      { label: 'Sign-ups', path: '/platform/signups', icon: UserPlus, module: 'platform' },
      { label: 'Translations', path: '/platform/translations', icon: Languages, module: 'platform' },
      { label: 'Product theme', path: '/platform/theme', icon: Palette, module: 'platform' },
      { label: 'Admin activity', path: '/platform/audit', icon: History, module: 'platform' },
    ],
  },
];

/** The menu. Order here is the order on screen. */
export const NAVIGATION: NavGroup[] = [
  { label: 'Overview', items: [{ label: 'Dashboard', path: '/', icon: LayoutDashboard, module: 'dashboard' }] },
  {
    label: 'Sales',
    items: [
      { label: 'Quotations', path: '/sales/quotations', icon: FileText, module: 'sales', term: 'quotation' },
      { label: 'Sales orders', path: '/sales/orders', icon: ClipboardList, module: 'sales', term: 'salesOrder' },
      { label: 'Invoices', path: '/sales/invoices', icon: Receipt, module: 'sales', term: 'salesInvoice' },
      { label: 'Customers', path: '/masters/customers', icon: Users, module: 'masters', serves: ['sales'], term: 'customer' },
    ],
  },
  {
    label: 'Purchase',
    items: [
      { label: 'Purchase orders', path: '/purchase/orders', icon: ShoppingCart, module: 'purchase', term: 'purchaseOrder' },
      { label: 'Vendor invoices', path: '/purchase/invoices', icon: ScrollText, module: 'purchase', term: 'purchaseInvoice' },
      { label: 'Vendors', path: '/masters/vendors', icon: Truck, module: 'masters', serves: ['purchase', 'inventory'], term: 'vendor' },
    ],
  },
  {
    label: 'Inventory',
    items: [
      { label: 'Stock', path: '/inventory/stock', icon: Boxes, module: 'inventory' },
      { label: 'Goods receipts', path: '/inventory/receipts', icon: PackageCheck, module: 'inventory', term: 'goodsReceipt' },
      { label: 'Stock ledger', path: '/inventory/ledger', icon: History, module: 'inventory' },
    ],
  },
  {
    label: 'Production',
    items: [
      { label: 'Work orders', path: '/production/work-orders', icon: Factory, module: 'production', term: 'workOrder' },
      { label: 'Bills of material', path: '/production/boms', icon: ListTree, module: 'production', term: 'bom' },
      { label: 'Material plan', path: '/production/material-plan', icon: Network, module: 'production' },
    ],
  },
  { label: 'Quality', items: [{ label: 'Inspections', path: '/quality/inspections', icon: ClipboardCheck, module: 'quality', term: 'inspection' }] },
  { label: 'Insights', items: [{ label: 'Reports', path: '/reports', icon: BarChart3, module: 'reports' }] },
  {
    label: 'Masters',
    items: [
      { label: 'Items', path: '/masters/items', icon: Package, module: 'masters', term: 'item' },
      { label: 'Warehouses', path: '/masters/warehouses', icon: Warehouse, module: 'masters', term: 'warehouse' },
      { label: 'Machines', path: '/masters/work-centres', icon: Wrench, module: 'masters', serves: ['production'], term: 'machine' },
      { label: 'Employees', path: '/masters/employees', icon: UserCog, module: 'masters', term: 'employee' },
      { label: 'GST rates', path: '/masters/tax-rates', icon: Percent, module: 'masters' },
      { label: 'Lists', path: '/masters/lists', icon: FileSpreadsheet, module: 'masters' },
    ],
  },
  {
    label: 'Settings',
    items: [
      { label: 'Company', path: '/settings/company', icon: Building2, module: 'settings' },
      { label: 'Theme', path: '/settings/theme', icon: Palette, module: 'settings' },
      { label: 'Labels', path: '/settings/labels', icon: Tags, module: 'settings' },
      { label: 'Rules and limits', path: '/settings/parameters', icon: SlidersHorizontal, module: 'settings' },
      { label: 'Numbering', path: '/settings/numbering', icon: Gauge, module: 'settings' },
      { label: 'Print templates', path: '/settings/print-templates', icon: Printer, module: 'settings' },
      { label: 'Users', path: '/settings/users', icon: Cog, module: 'admin' },
      { label: 'Roles and access', path: '/settings/access', icon: ShieldCheck, module: 'admin' },
      { label: 'Audit trail', path: '/settings/audit', icon: History, module: 'admin' },
    ],
  },
];
