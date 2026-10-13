import { expect, test } from '@playwright/test';
import { confirmIfAsked, field, label, toast, unique, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

test('create, find, edit and delete an item', async ({ page }, info) => {
  const errors = watchErrors(page);
  const code = `E2E-${unique()}`;
  await page.goto('/masters/items');
  await page.getByRole('button', { name: 'New item' }).click();
  await expect(page).toHaveURL(/\/masters\/items\/new$/);

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Code is required')).toBeVisible();
  await expect(page.getByText('Name is required')).toBeVisible();

  await field(page, 'Code').fill(code);
  await field(page, 'Type').selectOption({ index: 1 });
  await field(page, 'Name').fill('Playwright test bracket');
  await field(page, 'Unit').selectOption({ index: 1 });
  const hsn = field(page, 'HSN / SAC');
  if (await hsn.count()) await hsn.fill('7326');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(toast(page)).toContainText('created');
  await expect(page).toHaveURL(/\/masters\/items$/);
  await page.screenshot({ path: info.outputPath('1-item-created.png'), fullPage: true });

  await page.getByRole('textbox', { name: /Search/ }).fill(code);
  const row = page.locator('table tbody tr', { hasText: code });
  await expect(row).toHaveCount(1);
  await row.click();
  await expect(page).toHaveURL(/\/masters\/items\/\d+$/);
  await expect(field(page, 'Code')).toHaveValue(code);
  await field(page, 'Name').fill('Playwright test bracket (edited)');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(toast(page)).toContainText('saved');

  await page.getByRole('textbox', { name: /Search/ }).fill(code);
  await expect(page.locator('table tbody tr', { hasText: '(edited)' })).toHaveCount(1);
  await page.getByRole('button', { name: /^Delete/ }).first().click();
  await confirmIfAsked(page);
  await expect(toast(page)).toContainText('deleted');
  await expect(page.locator('table tbody tr', { hasText: code })).toHaveCount(0);
  errors.check();
});

test('customer form checks GSTIN, PAN and PIN code', async ({ page }, info) => {
  await page.goto('/masters/customers/new');
  await field(page, 'Code').fill(`C-E2E${unique()}`);
  await field(page, 'Name').fill('Playwright Customer');
  await field(page, 'State').selectOption({ index: 1 });
  await field(page, 'GSTIN').fill('27ABCDE1234F1Z0');
  await field(page, 'PAN').fill('BAD');
  await field(page, 'PIN code').fill('12');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('PAN is not a valid PAN')).toBeVisible();
  await expect(page.getByText('PIN code must be 6 digits')).toBeVisible();
  await page.screenshot({ path: info.outputPath('customer-validation.png'), fullPage: true });
});

test('a valid customer can be added and is offered on a quotation', async ({ page }) => {
  const errors = watchErrors(page);
  const code = `C-E2E${unique()}`;
  await page.goto('/masters/customers/new');
  await field(page, 'Code').fill(code);
  await field(page, 'Name').fill(`Playwright Traders ${code}`);
  await field(page, 'State').selectOption('27');
  await field(page, 'City').fill('Pune');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(toast(page)).toContainText('created');
  await page.goto('/sales/quotations/new');
  await expect(field(page, 'Customer').locator('option', { hasText: code })).toHaveCount(1);
  errors.check();
});
