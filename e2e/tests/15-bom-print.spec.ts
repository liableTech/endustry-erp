import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

test('a bill of material can be printed and downloaded as PDF', async ({ page }, info) => {
  const errors = watchErrors(page);
  await page.goto('/production/boms');
  await page.locator('table.table tbody tr').first().click();
  const sheet = page.locator('.full-page');
  await expect(sheet.getByRole('button', { name: 'Print', exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath('bom-with-print.png'), fullPage: true });

  const download = page.waitForEvent('download');
  await sheet.getByRole('button', { name: 'Download PDF' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^Bill-of-Material-.+\.pdf$/);
  const path = info.outputPath(file.suggestedFilename());
  await file.saveAs(path);
  expect(readFileSync(path).subarray(0, 4).toString()).toBe('%PDF');

  // Print fetches the same PDF and hands it to the browser's print dialog.
  const printed = page.waitForResponse((r) => /\/api\/production\/boms\/\d+\/print$/.test(r.url()));
  await sheet.getByRole('button', { name: 'Print', exact: true }).click();
  const response = await printed;
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('application/pdf');

  // The layout can be replaced like any other document's.
  await page.goto('/settings/print-templates');
  await expect(page.getByText('Bill of Material').first()).toBeVisible();
  errors.check();
});
