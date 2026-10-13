import { expect, test } from '@playwright/test';
import { apiGet, field, label, runAction, titleStatus, toast, unique, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

test('stock adjustment shows in stock and in the ledger', async ({ page }, info) => {
  const errors = watchErrors(page);
  await page.goto('/inventory/stock');
  const before = await apiGet<any[]>(page, '/inventory/stock');
  const qty = (rows: any[]) => rows.filter((r) => r.itemCode === 'PK-BOX-M' && r.status === 'AVAILABLE').reduce((s, r) => s + Number(r.qty), 0);

  await page.getByRole('button', { name: 'Adjust stock' }).click();
  const dialog = page.getByRole('dialog');
  await field(dialog, 'Item').selectOption({ label: 'PK-BOX-M - Corrugated Box Medium' });
  await field(dialog, 'Warehouse').selectOption({ index: 1 });
  await field(dialog, 'Quantity change').fill('7');
  await field(dialog, 'Reason').selectOption({ index: 1 });
  await page.screenshot({ path: info.outputPath('1-adjust-dialog.png') });
  await dialog.getByRole('button', { name: 'Post adjustment' }).click();
  await expect(dialog).toBeHidden();
  const after = await apiGet<any[]>(page, '/inventory/stock');
  expect(qty(after)).toBe(qty(before) + 7);

  await page.goto('/inventory/ledger');
  await expect(page.locator('table tbody tr').first()).toContainText('PK-BOX-M');
  await page.screenshot({ path: info.outputPath('2-ledger.png'), fullPage: true });
  errors.check();
});

test('a transfer to the same warehouse is refused', async ({ page }) => {
  await page.goto('/inventory/stock');
  await page.getByRole('button', { name: 'Transfer' }).click();
  const dialog = page.getByRole('dialog');
  await field(dialog, 'Item').selectOption({ label: 'PK-BOX-M - Corrugated Box Medium' });
  await field(dialog, 'From warehouse').selectOption({ index: 1 });
  await field(dialog, 'To warehouse').selectOption({ index: 1 });
  await field(dialog, 'Quantity').fill('1');
  await dialog.getByRole('button', { name: 'Transfer' }).click();
  await expect(dialog.getByText('must be different from the source')).toBeVisible();
});

test('work order: plan, release, run job cards and book output', async ({ page }, info) => {
  const errors = watchErrors(page);
  await page.goto('/production/work-orders');
  await page.getByRole('button', { name: 'New work order' }).click();
  const dialog = page.getByRole('dialog');
  await field(dialog, 'Product').selectOption({ label: 'FG-GEAR-24T - Spur Gear 24T' });
  await field(dialog, 'Quantity to make').fill('2');
  await field(dialog, 'Warehouse').selectOption({ index: 1 });
  const due = new Date(Date.now() + 7 * 86400_000).toISOString().slice(0, 10);
  await field(dialog, 'Due date').fill(due);
  await dialog.getByRole('button', { name: 'Plan work order' }).click();
  await expect(dialog).toBeHidden();
  await expect(page).toHaveURL(/\/production\/work-orders\/\d+$/);
  await expect(titleStatus(page)).toHaveText('Planned');
  await page.screenshot({ path: info.outputPath('1-work-order-planned.png'), fullPage: true });

  await runAction(page, 'Release to floor');
  await expect(titleStatus(page)).toHaveText('Released');

  // Run each job card: start, then complete with the planned quantity.
  const cards = page.locator('.card', { hasText: 'Job cards' });
  await expect(cards.getByRole('button', { name: 'Start' }).first()).toBeVisible();
  while (await cards.getByRole('button', { name: 'Start' }).count()) {
    await cards.getByRole('button', { name: 'Start' }).first().click();
    await expect(cards.getByRole('button', { name: 'Complete' }).first()).toBeVisible();
    await cards.getByRole('button', { name: 'Complete' }).first().click();
    const complete = page.getByRole('dialog');
    await complete.getByRole('button', { name: 'Complete' }).click();
    await expect(complete).toBeHidden();
  }
  await page.screenshot({ path: info.outputPath('2-job-cards-done.png'), fullPage: true });

  await page.locator('.sheet-foot').getByRole('button', { name: 'Book output' }).click();
  const book = page.getByRole('dialog');
  await book.getByRole('button', { name: 'Book output' }).click();
  await expect(book).toBeHidden();
  await expect(titleStatus(page)).toHaveText(/Completed|Closed/);
  await expect(page.locator('.card', { hasText: 'Output booked' }).locator('table tbody tr')).toHaveCount(1);
  await page.screenshot({ path: info.outputPath('3-output-booked.png'), fullPage: true });
  errors.check();
});

test('bill of material: open, copy to a new version', async ({ page }, info) => {
  const errors = watchErrors(page);
  await page.goto('/production/boms');
  await page.locator('table tbody tr').first().click();
  await expect(page).toHaveURL(/\/production\/boms\/\d+/);
  await expect(page.locator('.card', { hasText: 'Components' }).locator('table tbody tr').first()).toBeVisible();
  await page.screenshot({ path: info.outputPath('bom.png'), fullPage: true });
  await page.getByRole('button', { name: 'Copy to new version' }).click();
  await expect(toast(page)).toBeVisible();
  errors.check();
});

test('material plan lists what to buy', async ({ page }) => {
  await page.goto('/production/material-plan');
  await expect(page.locator('table')).toBeVisible();
});

test('incoming inspection: receive a QC item and accept it', async ({ page }, info) => {
  const errors = watchErrors(page);
  const ref = `E2E-QC-${unique()}`;
  await page.goto('/purchase/orders/new');
  await field(page, 'Vendor').selectOption({ index: 1 });
  await page.getByLabel('Vendor quotation ref').fill(ref);
  await page.locator('table.lines tbody tr').first().locator('select').selectOption({ label: 'RM-EN8-40 - EN8 Round Bar 40 mm' });
  await page.getByLabel('Line 1 quantity').fill('5');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page).toHaveURL(/\/purchase\/orders\/\d+$/);
  await runAction(page, 'Approve');
  await page.getByRole('button', { name: 'Receive goods' }).click();
  await expect(page).toHaveURL(/\/inventory\/receipts\/\d+/);
  await runAction(page, 'Receive into stock');
  await expect(titleStatus(page)).toHaveText('Received');

  await page.goto('/quality/inspections');
  const row = page.locator('table tbody tr', { hasText: 'RM-EN8-40' }).first();
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: 'Inspect' }).click();
  const dialog = page.getByRole('dialog');
  await field(dialog, 'Accepted quantity').fill('4');
  await field(dialog, 'Rejected quantity').fill('1');
  await field(dialog, 'Rejection reason').selectOption({ index: 1 });
  await page.screenshot({ path: info.outputPath('inspect.png') });
  await dialog.getByRole('button', { name: 'Record result' }).click();
  await expect(dialog).toBeHidden();
  await page.getByRole('tab', { name: 'Completed' }).click();
  await expect(page.locator('table tbody tr', { hasText: 'RM-EN8-40' }).first()).toBeVisible();
  errors.check();
});
