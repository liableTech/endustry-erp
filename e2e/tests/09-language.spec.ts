import { expect, test } from '@playwright/test';
import { watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

test('switching to Hindi translates the menu and screens, and back', async ({ page }, info) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await page.locator('.topbar').getByLabel('Language').selectOption('hi');
  const nav = page.locator('.sidebar');
  await expect(nav.getByText('डैशबोर्ड')).toBeVisible();
  await expect(nav.getByText('कोटेशन')).toBeVisible();
  await expect(nav.getByText('स्टॉक', { exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath('hindi-dashboard.png'), fullPage: true });

  // Every screen still opens in Hindi, and its title is no longer the English one.
  for (const [path, english] of [['/sales/invoices', 'Sales invoices'], ['/inventory/stock', 'Stock'], ['/reports', 'Reports'], ['/settings/company', 'Company']]) {
    await page.goto(path);
    await expect(page.locator('.page-title')).not.toHaveText(english);
    await expect(page.getByRole('alert')).toHaveCount(0);
  }
  await page.goto('/sales/invoices');
  await page.screenshot({ path: info.outputPath('hindi-invoices.png'), fullPage: true });

  // The choice is remembered after a reload.
  await page.reload();
  await expect(nav.getByText('डैशबोर्ड')).toBeVisible();

  await page.locator('.topbar').getByRole('combobox').first().selectOption('en');
  await expect(nav.getByText('Dashboard')).toBeVisible();
  errors.check();
});

test('English words left untranslated on main screens in Hindi', async ({ page }, info) => {
  await page.goto('/');
  await page.locator('.topbar').getByLabel('Language').selectOption('hi');
  await expect(page.locator('.sidebar').getByText('डैशबोर्ड')).toBeVisible();
  const leftovers: Record<string, string[]> = {};
  for (const path of ['/', '/sales/quotations', '/sales/invoices/new', '/inventory/stock', '/production/work-orders', '/masters/items/new', '/settings/company', '/reports']) {
    await page.goto(path);
    await expect(page.locator('.spinner')).toHaveCount(0, { timeout: 15_000 });
    // Headings, labels, buttons and menu entries written only in English letters.
    const texts = await page.locator('.page-title, .field-label, button, .nav-label, th, .tab').allInnerTexts();
    const english = [...new Set(texts.map((s) => s.trim()).filter((s) => /^[A-Za-z][A-Za-z ,.'()/-]{2,}$/.test(s) && !/^(GST|GSTIN|PAN|HSN|PDF|CSV|SAC|IGST|CGST|SGST|MSME|BOM|UOM|PO|QC)\b/.test(s)))];
    if (english.length) leftovers[path] = english;
  }
  await page.locator('.topbar').getByRole('combobox').first().selectOption('en');
  info.annotations.push({ type: 'untranslated', description: JSON.stringify(leftovers) });
  await info.attach('untranslated.json', { body: JSON.stringify(leftovers, null, 2), contentType: 'application/json' });
  expect(leftovers, 'screen text still in English while Hindi is chosen').toEqual({});
});
