import { expect, test } from '@playwright/test';
import { confirmIfAsked, field, label, signIn, toast, unique, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/admin.json' });

const checkbox = (page: import('@playwright/test').Page, module: string) =>
  page.locator('label.check', { hasText: new RegExp(`^${module}$`, 'i') }).locator('input');

test('product admin screens open', async ({ page }, info) => {
  const errors = watchErrors(page);
  for (const [path, title] of [['/platform/clients', 'Clients'], ['/platform/translations', /Translations/], ['/platform/theme', 'Product theme'], ['/platform/audit', 'Admin activity']] as const) {
    await page.goto(path);
    await expect(page.locator('.page-title')).toHaveText(title);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await page.screenshot({ path: info.outputPath(`${path.split('/').pop()}.png`), fullPage: true });
  }
  // A product admin never sees a client's screens.
  await page.goto('/sales/invoices');
  await expect(page).toHaveURL(/\/platform\/clients$/);
  errors.check();
});

test('new client with the Trading preset, module switch-off and a support session', async ({ page, browser }, info) => {
  const errors = watchErrors(page);
  const id = unique().toLowerCase();
  const name = `Playwright Traders ${id}`;
  const ownerEmail = `owner.${id}@playwright.test`;

  await page.goto('/platform/clients');
  await page.getByRole('button', { name: 'New client' }).click();
  await field(page, 'Company name').fill(name);
  await expect(field(page, 'Short name')).toHaveValue(`playwright-traders-${id}`);
  await field(page, 'Industry').selectOption('TRADING');
  // Trading switches production and quality off and keeps the rest.
  await expect(checkbox(page, 'production')).not.toBeChecked();
  await expect(checkbox(page, 'quality')).not.toBeChecked();
  await expect(checkbox(page, 'sales')).toBeChecked();
  await expect(checkbox(page, 'inventory')).toBeChecked();
  await field(page, 'Owner name').fill('Pat Playwright');
  await field(page, 'Owner email').fill(ownerEmail);
  await field(page, 'Starting password').fill('Playwright@2026');
  await page.screenshot({ path: info.outputPath('1-new-client.png'), fullPage: true });
  await page.getByRole('button', { name: 'Create client' }).click();

  const credentials = page.getByRole('dialog');
  await expect(credentials).toContainText(ownerEmail);
  await page.screenshot({ path: info.outputPath('2-credentials.png') });
  await credentials.getByRole('button', { name: 'I have saved these' }).click();
  const row = page.locator('table tbody tr', { hasText: name });
  await expect(row).toContainText('Trading and distribution');

  // The new Owner signs in and sees only the modules of the preset.
  const context = await browser.newContext({ storageState: undefined });
  const owner = await context.newPage();
  await signIn(owner, ownerEmail, 'Playwright@2026');
  const nav = owner.locator('.sidebar');
  await expect(nav.getByText('Quotations')).toBeVisible();
  await expect(nav.getByText('Work orders')).toHaveCount(0);
  await expect(nav.getByText('Inspections')).toHaveCount(0);
  await owner.goto('/production/work-orders');
  await expect(owner.getByText('The Production module is not enabled')).toBeVisible();
  await owner.screenshot({ path: info.outputPath('3-module-not-enabled.png'), fullPage: true });

  // Switch Sales off for the client; the Owner loses it without signing in again.
  await row.click();
  await checkbox(page, 'sales').uncheck();
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(toast(page)).toBeVisible();
  await owner.goto('/sales/invoices');
  await expect(owner.getByText('The Sales module is not enabled')).toBeVisible();
  await expect(owner.locator('.sidebar').getByText('Quotations')).toHaveCount(0);
  await context.close();

  // Support session as the Owner
  await page.getByRole('button', { name: 'Sign in as owner' }).click();
  await confirmIfAsked(page);
  await expect(page.locator('.support-bar')).toContainText('Support session');
  await expect(page.locator('.topbar')).toContainText(name);
  await page.screenshot({ path: info.outputPath('4-support-session.png'), fullPage: true });
  await page.getByRole('button', { name: 'End support session' }).click();
  await expect(page).toHaveURL(/\/platform\/clients/);
  await expect(page.locator('.support-bar')).toHaveCount(0);
  errors.check();
});

test('the admin adds a user to a client', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/platform/clients');
  await page.locator('table tbody tr').first().click();
  await page.getByRole('button', { name: 'Add user' }).click();
  const dialog = page.getByRole('dialog');
  const email = `e2e.${unique().toLowerCase()}@playwright.test`;
  await field(dialog, 'Full name').fill('Added By Admin');
  await field(dialog, 'Email').fill(email);
  await field(dialog, 'Role').selectOption({ index: 1 });
  await dialog.getByRole('button', { name: 'Add user' }).click();
  // The generated or chosen password is shown once.
  await expect(page.getByRole('dialog')).toContainText(email);
  await page.getByRole('dialog').getByRole('button').last().click();
  await expect(page.locator('table tbody tr', { hasText: email })).toHaveCount(1);
  errors.check();
});

test('editing a Hindi translation shows up in the app', async ({ page, browser }) => {
  const errors = watchErrors(page);
  await page.goto('/platform/translations');
  await page.getByRole('textbox', { name: /Search/ }).fill('Stock ledger');
  const row = page.locator('table tbody tr').filter({ has: page.getByText('Stock ledger', { exact: true }) }).first();
  const original = (await row.locator('td').nth(1).innerText()).trim();
  await row.getByRole('button', { name: 'Edit' }).click();
  const dialog = page.getByRole('dialog');
  const box = dialog.locator('textarea, input.input').last();
  await box.fill('स्टॉक बही (E2E)');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();

  const context = await browser.newContext({ storageState: '.test-data/auth/owner.json' });
  const owner = await context.newPage();
  await owner.goto('/');
  await owner.locator('.topbar').getByLabel('Language').selectOption('hi');
  await expect(owner.locator('.sidebar').getByText('स्टॉक बही (E2E)')).toBeVisible();
  await owner.locator('.topbar').getByRole('combobox').first().selectOption('en');
  await context.close();

  await row.getByRole('button', { name: 'Edit' }).click();
  await dialog.locator('textarea, input.input').last().fill(original);
  await dialog.getByRole('button', { name: 'Save' }).click();
  errors.check();
});
