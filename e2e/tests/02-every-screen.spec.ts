import { expect, test } from '@playwright/test';
import { watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

// Every screen in the Owner's menu, plus the record pages reached from them.
const SCREENS: [string, string, RegExp | string][] = [
  ['Dashboard', '/', /Good (morning|afternoon|evening)/],
  ['Quotations', '/sales/quotations', 'Quotations'],
  ['Sales orders', '/sales/orders', 'Sales orders'],
  ['Invoices', '/sales/invoices', /invoices/i],
  ['Customers', '/masters/customers', 'Customers'],
  ['Purchase orders', '/purchase/orders', 'Purchase orders'],
  ['Vendor invoices', '/purchase/invoices', /invoices/i],
  ['Vendors', '/masters/vendors', 'Vendors'],
  ['Stock', '/inventory/stock', 'Stock'],
  ['Goods receipts', '/inventory/receipts', 'Goods receipts'],
  ['Stock ledger', '/inventory/ledger', 'Stock ledger'],
  ['Work orders', '/production/work-orders', 'Work orders'],
  ['Bills of material', '/production/boms', /Bills of material|BOMs/i],
  ['Material plan', '/production/material-plan', 'Material plan'],
  ['Inspections', '/quality/inspections', /Incoming/],
  ['Reports', '/reports', 'Reports'],
  ['Items', '/masters/items', 'Items'],
  ['Warehouses', '/masters/warehouses', 'Warehouses'],
  ['Machines', '/masters/work-centres', 'Machines'],
  ['Employees', '/masters/employees', 'Employees'],
  ['GST rates', '/masters/tax-rates', 'GST rates'],
  ['Lists', '/masters/lists', 'Lists'],
  ['Company', '/settings/company', 'Company'],
  ['Theme', '/settings/theme', 'Theme'],
  ['Labels', '/settings/labels', 'Labels'],
  ['Rules and limits', '/settings/parameters', 'Rules and limits'],
  ['Numbering', '/settings/numbering', 'Numbering'],
  ['Print templates', '/settings/print-templates', 'Print templates'],
  ['Users', '/settings/users', 'Users'],
  ['Roles and access', '/settings/access', 'Roles and access'],
  ['Audit trail', '/settings/audit', 'Audit trail'],
];

test.describe('Every screen opens without errors (Owner)', () => {
  for (const [name, path, heading] of SCREENS) {
    test(name, async ({ page }, info) => {
      const errors = watchErrors(page);
      await page.goto(path);
      await expect(page.locator('.page-title').first()).toContainText(heading);
      await expect(page.locator('.spinner')).toHaveCount(0, { timeout: 15_000 });
      await expect(page.getByRole('alert')).toHaveCount(0);
      await page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true });
      errors.check();
    });
  }

  test('every menu link goes to a working screen', async ({ page }) => {
    const errors = watchErrors(page);
    await page.goto('/');
    const links = page.locator('.sidebar .nav-item');
    const count = await links.count();
    expect(count).toBeGreaterThan(25);
    for (let i = 0; i < count; i++) {
      const link = links.nth(i);
      const label = (await link.innerText()).trim();
      await link.click();
      await expect(link, `menu item ${label}`).toHaveClass(/active/);
      await expect(page.locator('.spinner')).toHaveCount(0, { timeout: 15_000 });
      await expect(page.getByRole('alert'), `error on ${label}`).toHaveCount(0);
    }
    errors.check();
  });

  test('the first record of each list opens as its own page', async ({ page }) => {
    const errors = watchErrors(page);
    const failed: string[] = [];
    for (const path of ['/sales/quotations', '/sales/orders', '/sales/invoices', '/purchase/orders', '/purchase/invoices', '/inventory/receipts',
      '/production/work-orders', '/production/boms', '/masters/items', '/masters/customers', '/masters/vendors', '/masters/employees']) {
      await page.goto(path);
      const row = page.locator('table tbody tr').first();
      await expect(row, `a row on ${path}`).toBeVisible();
      await row.click();
      await expect(page, `record address for ${path}`).toHaveURL(new RegExp(`${path}/\\d+`));
      if (!(await page.locator('.full-page').waitFor({ state: 'visible', timeout: 5000 }).then(() => true, () => false))) { failed.push(path); continue; }
      await expect(page.locator('.spinner')).toHaveCount(0, { timeout: 15_000 });
      await expect(page.getByRole('alert'), `error on a ${path} record`).toHaveCount(0);
    }
    expect(failed, 'lists whose records did not open').toEqual([]);
    errors.check();
  });
});

test.describe('List tools (Owner)', () => {
  for (const path of ['/sales/invoices', '/purchase/orders', '/inventory/receipts']) {
    test(`status tabs and search keep their value on ${path}`, async ({ page }) => {
      await page.goto(path);
      await expect(page.locator('table tbody tr').first()).toBeVisible();
      const tab = page.getByRole('tab').nth(2);
      await tab.click();
      await expect(tab, 'chosen status tab stays selected').toHaveAttribute('aria-selected', 'true');
      const search = page.getByRole('textbox', { name: /Search/ });
      await search.fill('26-27');
      await expect(search, 'search box keeps the typed text').toHaveValue('26-27');
    });
  }

  for (const [path, button] of [['/sales/orders', 'From quotation'], ['/sales/invoices', 'From sales order'], ['/inventory/receipts', 'Receive against PO'], ['/purchase/invoices', 'From goods receipt']]) {
    test(`"${button}" opens its picker`, async ({ page }) => {
      await page.goto(path);
      await page.getByRole('button', { name: button }).click();
      await expect(page.getByRole('dialog')).toBeVisible();
    });
  }

  for (const path of ['/masters/items', '/masters/customers', '/masters/vendors', '/masters/warehouses', '/masters/work-centres', '/masters/employees', '/masters/tax-rates', '/masters/lists', '/settings/users']) {
    test(`search and "New" work on ${path}`, async ({ page }) => {
      const errors = watchErrors(page);
      await page.goto(path);
      const search = page.getByRole('textbox', { name: /Search/ });
      await search.fill('a');
      await expect(search, 'search box keeps the typed text').toHaveValue('a');
      await search.fill('');
      await page.getByRole('button', { name: /^New / }).click();
      await expect(page).toHaveURL(new RegExp(`${path}/new$`));
      await expect(page.locator('.full-page'), 'the new-record page opens').toBeVisible();
      errors.check();
    });
  }
});
