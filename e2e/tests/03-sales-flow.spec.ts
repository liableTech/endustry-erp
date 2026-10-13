import { expect, test } from '@playwright/test';
import { field, label, runAction, titleStatus, toast, unique, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

const ITEM = 'FG-GEAR-24T - Spur Gear 24T';

test('quotation to sales order to invoice to receipt, with PDF', async ({ page }, info) => {
  const errors = watchErrors(page);
  const ref = `E2E-${unique()}`;

  // Quotation
  await page.goto('/sales/quotations');
  await page.getByRole('button', { name: 'New quotation' }).click();
  await expect(page).toHaveURL(/\/sales\/quotations\/new$/);
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('alert')).toContainText('Choose a Customer');
  const customer = field(page, 'Customer');
  await customer.selectOption({ index: 1 });
  await page.getByLabel('Enquiry reference').fill(ref);
  await page.locator('table.lines tbody tr').first().locator('select').selectOption({ label: ITEM });
  await page.getByLabel('Line 1 quantity').fill('2');
  await expect(page.getByLabel('Line 1 rate')).not.toHaveValue('');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(toast(page)).toContainText('saved as draft');
  await expect(page).toHaveURL(/\/sales\/quotations\/\d+$/);
  await expect(titleStatus(page)).toHaveText('Draft');
  await page.screenshot({ path: info.outputPath('1-quotation-draft.png'), fullPage: true });

  await runAction(page, 'Mark as sent');
  await expect(titleStatus(page)).toHaveText('Sent');
  await runAction(page, 'Customer accepted');
  await expect(titleStatus(page)).toHaveText('Accepted');

  // Sales order raised from it
  await page.getByRole('button', { name: 'Create sales order' }).click();
  await expect(page).toHaveURL(/\/sales\/orders\/\d+/);
  await expect(page.getByText('Raised from')).toBeVisible();
  await runAction(page, 'Confirm order');
  await expect(titleStatus(page)).toHaveText('Confirmed');
  await page.screenshot({ path: info.outputPath('2-sales-order-confirmed.png'), fullPage: true });

  // Invoice raised from the order, posted and paid
  await page.getByRole('button', { name: 'Create invoice' }).click();
  await expect(page).toHaveURL(/\/sales\/invoices\/\d+/);
  await runAction(page, 'Post invoice');
  // The order raised from the quotation gets a warehouse, so posting does not stop to ask for one.
  await expect(page).not.toHaveURL(/\/edit$/);
  await expect(titleStatus(page)).toHaveText('Unpaid');
  await page.getByRole('button', { name: 'Record receipt' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Reference').fill(`UTR${ref}`);
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(titleStatus(page)).toHaveText('Paid');
  await expect(page.getByText(`UTR${ref}`)).toBeVisible();
  await page.screenshot({ path: info.outputPath('3-invoice-paid.png'), fullPage: true });

  // PDF
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download PDF' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/\.pdf$/);
  const path = info.outputPath(file.suggestedFilename());
  await file.saveAs(path);
  const { readFileSync } = await import('node:fs');
  expect(readFileSync(path).subarray(0, 4).toString()).toBe('%PDF');

  // The invoice shows in the list under Paid
  await page.goto('/sales/invoices');
  await page.getByRole('tab', { name: 'Paid', exact: true }).click();
  await page.getByRole('textbox', { name: /Search/ }).fill(ref);
  await expect(page.locator('table tbody tr')).toHaveCount(1);
  errors.check();
});

test('the editor refuses bad lines', async ({ page }) => {
  await page.goto('/sales/invoices/new');
  await field(page, 'Customer').selectOption({ index: 1 });
  await page.getByLabel('Line 1 quantity').fill('0');
  await page.getByRole('button', { name: 'Save draft' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).toContainText('Line 1: choose an item.');
  await expect(alert).toContainText('Line 1: quantity must be more than zero.');
});

test('a cancelled quotation cannot be edited', async ({ page }) => {
  await page.goto('/sales/quotations/new');
  await field(page, 'Customer').selectOption({ index: 1 });
  await page.locator('table.lines tbody tr').first().locator('select').selectOption({ label: ITEM });
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page).toHaveURL(/\/sales\/quotations\/\d+$/);
  await runAction(page, 'Cancel document');
  await expect(titleStatus(page)).toHaveText('Cancelled');
  await expect(page.locator('.sheet-foot').getByRole('button', { name: 'Edit' })).toHaveCount(0);
});
