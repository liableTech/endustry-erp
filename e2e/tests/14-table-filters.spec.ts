import { expect, test, type Page } from '@playwright/test';
import { watchErrors } from './helpers';

const filterButton = (page: Page) => page.getByRole('button', { name: /^Filters/ }).first();
const panel = (page: Page) => page.locator('form.filter-panel').first();
const chips = (page: Page) => page.locator('.filter-chips').first();

/** The "N records" count under a paged table. */
async function records(page: Page) {
  await expect(page.locator('.spinner')).toHaveCount(0);
  const pager = page.locator('.pager span').first();
  if (!(await pager.count())) return 0;
  return Number((await pager.innerText()).replace(/\D/g, ''));
}

/** Picks values in a search-and-tick dropdown of the filter panel: types part of each name, then ticks it. */
async function pick(page: Page, label: string, ...names: string[]) {
  await panel(page).getByRole('combobox', { name: label }).click();
  for (const name of names) {
    await panel(page).getByRole('searchbox', { name: `Search ${label}` }).fill(name.slice(0, 5));
    await panel(page).getByRole('option', { name }).click();
  }
  await panel(page).getByRole('button', { name: 'Done' }).click();
}

async function openFilters(page: Page) {
  await filterButton(page).click();
  await expect(panel(page)).toBeVisible();
}

async function apply(page: Page) {
  await panel(page).getByRole('button', { name: 'Apply filters' }).click();
  await expect(panel(page)).toBeHidden();
}

test.describe('Table filters', () => {
  test.use({ storageState: '.test-data/auth/owner.json' });

  test('every list screen has the Filters button', async ({ page }) => {
    const errors = watchErrors(page);
    const screens = ['/sales/quotations', '/sales/orders', '/sales/invoices', '/masters/customers', '/purchase/orders', '/purchase/invoices', '/masters/vendors',
      '/inventory/stock', '/inventory/receipts', '/inventory/ledger', '/production/work-orders', '/production/boms', '/production/material-plan',
      '/quality/inspections', '/reports/sales-register', '/masters/items', '/masters/warehouses', '/masters/work-centres', '/masters/employees',
      '/masters/tax-rates', '/masters/lists', '/settings/users', '/settings/audit'];
    for (const path of screens) {
      await page.goto(path);
      await expect(filterButton(page), path).toBeVisible();
      await openFilters(page);
      await expect(panel(page).locator('.field').first(), path).toBeVisible();
      await expect(page.getByRole('alert')).toHaveCount(0);
    }
    errors.check();
  });

  test('sales invoices: several filters together, chips, and clearing', async ({ page }, info) => {
    const errors = watchErrors(page);
    await page.goto('/sales/invoices');
    const all = await records(page);
    expect(all).toBeGreaterThan(3);

    await openFilters(page);
    await page.screenshot({ path: info.outputPath('1-panel-open.png'), fullPage: true });
    await panel(page).getByLabel('Total from').fill('100000');
    await apply(page);
    await expect(chips(page)).toContainText('Total');
    await expect(chips(page)).toContainText('from 1,00,000');
    await expect(filterButton(page)).toContainText('1');
    const large = await records(page);
    expect(large).toBeLessThan(all);
    for (const total of await page.locator('table.table tbody tr td:nth-child(5)').allInnerTexts()) {
      expect(Number(total.replace(/[^\d.]/g, ''))).toBeGreaterThanOrEqual(100000);
    }

    // A second filter narrows it further: one customer, picked from the list.
    await openFilters(page);
    const name = (await page.locator('table.table tbody tr td:nth-child(3)').first().innerText()).trim();
    // The customer list is a dropdown: searching narrows it, and more than one can be ticked.
    const names = [...new Set((await page.locator('table.table tbody tr td:nth-child(3)').allInnerTexts()).map((s) => s.trim()))];
    const other = names.find((n) => n !== name);
    await panel(page).getByRole('combobox', { name: 'Customer' }).click();
    const search = panel(page).getByRole('searchbox', { name: 'Search Customer' });
    await search.fill('zzzz');
    await expect(panel(page).getByText('No matches')).toBeVisible();
    await search.fill(name.slice(0, 6));
    await expect(panel(page).getByRole('option', { name })).toBeVisible();
    if (other && !other.startsWith(name.slice(0, 6))) await expect(panel(page).getByRole('option', { name: other })).toHaveCount(0);
    await panel(page).getByRole('option', { name }).click();
    if (other) {
      await search.fill(other.slice(0, 6));
      await panel(page).getByRole('option', { name: other }).click();
      await expect(panel(page).getByText('2 selected').first()).toBeVisible();
      await page.screenshot({ path: info.outputPath('1b-customer-dropdown.png') });
      // Unticking leaves the first one.
      await panel(page).getByRole('option', { name: other }).click();
    }
    await panel(page).getByRole('button', { name: 'Done' }).click();
    await expect(panel(page).getByRole('combobox', { name: 'Customer' })).toContainText(name);
    await apply(page);
    await expect(chips(page)).toContainText(name);
    await expect(filterButton(page)).toContainText('2');
    const one = await records(page);
    expect(one).toBeGreaterThan(0);
    expect(one).toBeLessThanOrEqual(large);
    for (const cell of await page.locator('table.table tbody tr td:nth-child(3)').allInnerTexts()) expect(cell.trim()).toBe(name);
    await page.screenshot({ path: info.outputPath('2-two-filters.png'), fullPage: true });

    // The filters are still there after a reload, and after opening a record and coming back.
    await page.reload();
    await expect(chips(page)).toContainText(name);
    expect(await records(page)).toBe(one);
    await page.locator('table.table tbody tr').first().click();
    await expect(page.locator('.full-page')).toBeVisible();
    await page.goBack();
    await expect(chips(page)).toContainText(name);

    // Removing one chip keeps the other; Clear all brings every invoice back.
    await chips(page).getByRole('button', { name: /Remove filter Customer/ }).click();
    await expect(chips(page)).not.toContainText(name);
    expect(await records(page)).toBe(large);
    await chips(page).getByRole('button', { name: 'Clear all' }).click();
    await expect(page.locator('.filter-chips')).toHaveCount(0);
    expect(await records(page)).toBe(all);

    // A date range with nothing in it says so.
    await openFilters(page);
    await panel(page).getByLabel('Date from', { exact: true }).fill('2001-01-01');
    await panel(page).getByLabel('Date to', { exact: true }).fill('2001-01-31');
    await apply(page);
    await expect(page.getByText('No record matches these filters.')).toBeVisible();
    await chips(page).getByRole('button', { name: 'Clear all' }).click();
    errors.check();
  });

  test('items: filters come from the fields of the master', async ({ page }, info) => {
    const errors = watchErrors(page);
    await page.goto('/masters/items');
    const all = await records(page);
    await openFilters(page);
    await expect(panel(page)).toContainText('GST rate %');
    await expect(panel(page)).toContainText('Unit');
    await expect(panel(page)).toContainText('Active');
    await panel(page).getByLabel('GST rate % from').fill('18');
    await panel(page).getByLabel('GST rate % to').fill('18');
    await panel(page).getByRole('group', { name: 'Active' }).getByRole('button', { name: 'Yes' }).click();
    await apply(page);
    await expect(chips(page)).toContainText('18 to 18');
    await expect(chips(page)).toContainText('Yes');
    const some = await records(page);
    expect(some).toBeGreaterThan(0);
    expect(some).toBeLessThanOrEqual(all);
    await page.screenshot({ path: info.outputPath('items-filtered.png'), fullPage: true });
    await openFilters(page);
    await panel(page).getByLabel('GST rate % from').fill('99');
    await panel(page).getByLabel('GST rate % to').fill('');
    await apply(page);
    await expect(page.getByText('No record matches these filters.')).toBeVisible();
    await chips(page).getByRole('button', { name: 'Clear all' }).click();
    expect(await records(page)).toBe(all);
    errors.check();
  });

  test('stock and stock ledger: quantity range and movement type', async ({ page }, info) => {
    const errors = watchErrors(page);
    await page.goto('/inventory/stock');
    await expect(page.locator('table.table tbody tr').first()).toBeVisible();
    const all = await page.locator('table.table tbody tr').count();
    await openFilters(page);
    await panel(page).getByLabel('Quantity from').fill('100');
    await apply(page);
    await expect(chips(page)).toContainText(/\d+ of \d+ match/);
    expect(await page.locator('table.table tbody tr').count()).toBeLessThanOrEqual(all);
    for (const qty of await page.locator('table.table tbody tr td:nth-child(5)').allInnerTexts()) {
      expect(Number(qty.replace(/[^\d.]/g, ''))).toBeGreaterThanOrEqual(100);
    }
    await chips(page).getByRole('button', { name: 'Clear all' }).click();

    await page.goto('/inventory/ledger');
    const movements = await records(page);
    await openFilters(page);
    await pick(page, 'Movement', 'Grn');
    await apply(page);
    await expect(chips(page)).toContainText('Grn');
    expect(await records(page)).toBeLessThan(movements);
    for (const type of await page.locator('table.table tbody tr td:nth-child(3)').allInnerTexts()) expect(type.trim()).toBe('Grn');
    await page.screenshot({ path: info.outputPath('ledger-filtered.png'), fullPage: true });
    await chips(page).getByRole('button', { name: 'Clear all' }).click();
    expect(await records(page)).toBe(movements);
    errors.check();
  });

  test('a report filters on its own columns and the totals follow', async ({ page }) => {
    const errors = watchErrors(page);
    await page.goto('/reports/sales-register');
    await expect(page.locator('table.table tbody tr').first()).toBeVisible();
    const all = await page.locator('table.table tbody tr').count();
    const total = await page.locator('table.table tfoot td').first().innerText();
    await openFilters(page);
    const numbers = panel(page).locator('input[type=number]');
    await numbers.first().fill('100000');
    await apply(page);
    await expect(chips(page)).toContainText(/\d+ of \d+ match/);
    const shown = await page.locator('table.table tbody tr').count();
    expect(shown).toBeLessThan(all);
    await expect(page.locator('table.table tfoot td').first()).not.toHaveText(total);
    await expect(page.locator('table.table tfoot td').first()).toContainText(String(shown));
    await chips(page).getByRole('button', { name: 'Clear all' }).click();
    errors.check();
  });

  test('the filters are in Hindi when Hindi is chosen', async ({ page }) => {
    await page.goto('/sales/invoices');
    await page.locator('.topbar').getByLabel('Language').selectOption('hi');
    await page.getByRole('button', { name: /^फ़िल्टर/ }).click();
    const form = page.locator('form.filter-panel');
    await expect(form.getByRole('button', { name: 'फ़िल्टर लागू करें' })).toBeVisible();
    await expect(form.getByRole('button', { name: 'इस महीने' })).toHaveCount(2);
    await expect(form.locator('.field-label').filter({ hasText: /^[A-Za-z ]+$/ })).toHaveCount(0);
    await page.locator('.topbar').getByRole('combobox').first().selectOption('en');
    await expect(filterButton(page)).toBeVisible();
  });
});

test.describe('Table filters for the product admin', () => {
  test.use({ storageState: '.test-data/auth/admin.json' });

  test('clients, sign-ups, coupons and admin activity can be filtered', async ({ page }, info) => {
    const errors = watchErrors(page);
    await page.goto('/platform/clients');
    await expect(page.locator('table.table tbody tr').first()).toBeVisible();
    const all = await page.locator('table.table tbody tr').count();
    await openFilters(page);
    await pick(page, 'Industry', 'Trading and distribution');
    await apply(page);
    await expect(chips(page)).toContainText('Trading and distribution');
    expect(await page.locator('table.table tbody tr').count()).toBeLessThan(all);
    for (const industry of await page.locator('table.table tbody tr td:nth-child(2)').allInnerTexts()) expect(industry.trim()).toBe('Trading and distribution');
    await page.screenshot({ path: info.outputPath('clients-filtered.png'), fullPage: true });
    await chips(page).getByRole('button', { name: 'Clear all' }).click();

    await page.goto('/platform/signups');
    await expect(page.getByRole('button', { name: /^Filters/ })).toHaveCount(2);
    await page.getByRole('button', { name: /^Filters/ }).last().click();
    const requests = page.locator('form.filter-panel');
    await requests.getByRole('group', { name: 'Status' }).getByRole('button', { name: 'Approved' }).click();
    await requests.getByRole('button', { name: 'Apply filters' }).click();
    await expect(page.locator('.filter-chips')).toContainText('Approved');
    await page.locator('.filter-chips').getByRole('button', { name: 'Clear all' }).click();

    await page.goto('/platform/audit');
    await openFilters(page);
    await panel(page).getByLabel('Action').fill('create');
    await apply(page);
    await expect(chips(page)).toContainText('"create"');
    for (const action of await page.locator('table.table tbody tr td:nth-child(3)').allInnerTexts()) expect(action.toLowerCase()).toContain('create');
    await chips(page).getByRole('button', { name: 'Clear all' }).click();
    errors.check();
  });
});
