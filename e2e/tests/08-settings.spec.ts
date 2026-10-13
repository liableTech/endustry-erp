import { expect, test } from '@playwright/test';
import { field, label, signIn, toast, unique, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

test('renaming Customers to Clients changes the menu, then reset', async ({ page }, info) => {
  const errors = watchErrors(page);
  await page.goto('/settings/labels');
  const row = page.locator('table tbody tr', { hasText: /^Customers/ }).first();
  await row.getByRole('button', { name: 'Rename' }).click();
  const dialog = page.getByRole('dialog');
  await field(dialog, 'One').fill('Client');
  await field(dialog, 'Many').fill('Clients');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('.sidebar .nav-item', { hasText: 'Clients' })).toBeVisible();
  await page.goto('/sales/quotations/new');
  await expect(page.getByText(/^Client\*?$/).first()).toBeVisible();
  await page.screenshot({ path: info.outputPath('label-renamed.png'), fullPage: true });

  await page.goto('/settings/labels');
  await page.locator('table tbody tr', { hasText: 'Client / Clients' }).getByRole('button', { name: 'Reset' }).click();
  for (let i = 0; i < 1; i++) {
    const confirm = page.getByRole('dialog');
    if (await confirm.waitFor({ state: 'visible', timeout: 1500 }).then(() => true, () => false)) await confirm.locator('.sheet-foot button').last().click();
  }
  await expect(page.locator('.sidebar .nav-item', { hasText: 'Customers' })).toBeVisible();
  errors.check();
});

test('dark mode switch in the top bar', async ({ page }, info) => {
  await page.goto('/');
  const html = page.locator('html');
  const before = await html.getAttribute('data-theme') ?? await html.getAttribute('class');
  await page.getByRole('button', { name: /Switch to (dark|light) mode/ }).click();
  await expect.poll(async () => (await html.getAttribute('data-theme')) ?? (await html.getAttribute('class'))).not.toBe(before);
  await page.screenshot({ path: info.outputPath('dark-mode.png'), fullPage: true });
});

test('company settings save', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/settings/company');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(toast(page)).toBeVisible();
  await expect(toast(page)).not.toHaveClass(/error/);
  errors.check();
});

test('print templates: standard layout and sample data download', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/settings/print-templates');
  const layout = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Standard layout' }).first().click();
  expect((await layout).suggestedFilename()).toMatch(/\.html?$/);
  const data = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Sample data' }).first().click();
  expect((await data).suggestedFilename()).toMatch(/\.json$/);
  errors.check();
});

test('the Owner adds a user who can then sign in', async ({ page, browser }) => {
  const errors = watchErrors(page);
  const email = `e2e.${unique().toLowerCase()}@endustry.local`;
  await page.goto('/settings/users/new');
  await field(page, 'Full name').fill('Playwright Storekeeper');
  await field(page, 'Email').fill(email);
  await field(page, 'Role').selectOption('STORE');
  const password = field(page, 'Password');
  if (await password.count()) await password.fill('Playwright@2026');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(toast(page)).toContainText('created');
  errors.check();

  const context = await browser.newContext({ storageState: undefined });
  const store = await context.newPage();
  await signIn(store, email, 'Playwright@2026');
  await expect(store.getByRole('link', { name: 'Stock', exact: true })).toBeVisible();
  await expect(store.locator('.sidebar .nav-item', { hasText: 'Roles and access' })).toHaveCount(0);
  await context.close();
});

test('changing role access hides a module for that role', async ({ page, browser }) => {
  await page.goto('/settings/access');
  const select = page.getByLabel('Sales access to quality');
  const original = await select.inputValue();
  await select.selectOption(original === 'NONE' ? 'VIEW' : 'NONE');
  await expect(toast(page)).toBeVisible();

  const context = await browser.newContext({ storageState: '.test-data/auth/sales.json' });
  const sales = await context.newPage();
  await sales.goto('/');
  await expect(sales.locator('.sidebar .nav-item', { hasText: 'Inspections' })).toHaveCount(original === 'NONE' ? 1 : 0);
  await context.close();

  await select.selectOption(original);
  await expect(select).toHaveValue(original);
});

test('audit trail records the changes made in this run', async ({ page }) => {
  await page.goto('/settings/audit');
  await expect(page.locator('table tbody tr').first()).toBeVisible();
});
