import { expect, test } from '@playwright/test';
import { field, runAction, titleStatus, toast, unique, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

test('purchase order to goods receipt to vendor invoice to payment', async ({ page }, info) => {
  const errors = watchErrors(page);
  const ref = `E2E-${unique()}`;

  await page.goto('/purchase/orders');
  await page.getByRole('button', { name: 'New purchase order' }).click();
  await field(page, 'Vendor').selectOption({ index: 1 });
  await page.getByLabel('Vendor quotation ref').fill(ref);
  await page.locator('table.lines tbody tr').first().locator('select').selectOption({ label: 'BO-SEAL-35 - Oil Seal 35 mm' });
  await page.getByLabel('Line 1 quantity').fill('10');
  await page.getByLabel('Line 1 rate').fill('40');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(toast(page)).toContainText('saved as draft');
  await expect(page).toHaveURL(/\/purchase\/orders\/\d+$/);
  await runAction(page, 'Approve');
  await expect(titleStatus(page)).toHaveText('Approved');
  await page.screenshot({ path: info.outputPath('1-po-approved.png'), fullPage: true });

  await page.getByRole('button', { name: 'Receive goods' }).click();
  await expect(page).toHaveURL(/\/inventory\/receipts\/\d+/);
  await runAction(page, 'Receive into stock');
  await expect(titleStatus(page)).toHaveText('Received');
  await page.screenshot({ path: info.outputPath('2-goods-received.png'), fullPage: true });

  await page.getByRole('button', { name: 'Book vendor invoice' }).click();
  await expect(page).toHaveURL(/\/purchase\/invoices\/\d+/);
  // Booking without the vendor's invoice number is refused, with a message.
  await runAction(page, 'Book invoice');
  await expect(toast(page)).toContainText("Enter the vendor's invoice number");
  await expect(titleStatus(page)).toHaveText('Draft');
  await page.locator('.sheet-foot').getByRole('button', { name: 'Edit' }).click();
  await page.getByLabel('Vendor invoice no').fill(`VI-${ref}`);
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page).not.toHaveURL(/\/edit$/);
  await runAction(page, 'Book invoice');
  await expect(titleStatus(page)).toHaveText('Unpaid');
  await page.getByRole('button', { name: 'Record payment' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(titleStatus(page)).toHaveText('Paid');
  await page.screenshot({ path: info.outputPath('3-vendor-invoice-paid.png'), fullPage: true });

  // The PO now shows as received
  await page.goto('/purchase/orders');
  await page.getByRole('textbox', { name: /Search/ }).fill(ref);
  await expect(page.locator('table tbody tr').first()).toContainText('Received');
  errors.check();
});
