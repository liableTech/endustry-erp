import { expect, test } from '@playwright/test';
import { signIn } from './helpers';

// Each seeded role sees its own part of the app, and a screen outside it is refused.
const ROLES: [string, string[], string[]][] = [
  ['sales', ['Quotations', 'Invoices'], ['Purchase orders', 'Roles and access']],
  ['purchase', ['Purchase orders', 'Purchase invoices'], ['Quotations', 'Roles and access']],
  ['store', ['Stock', 'Goods receipts'], ['Roles and access']],
  ['production', ['Work orders', 'Bills of material'], ['Roles and access']],
  ['quality', ['Inspections'], ['Roles and access']],
  ['accounts', ['Invoices'], ['Roles and access']],
  ['ca', ['Reports'], ['Roles and access']],
];

for (const [role, sees, hidden] of ROLES) {
  test(`${role} role sees only its screens`, async ({ page }, info) => {
    await signIn(page, `${role}@endustry.local`);
    const nav = page.locator('.sidebar');
    for (const item of sees) await expect(nav.locator('.nav-item', { hasText: item }).first(), `${role} sees ${item}`).toBeVisible();
    for (const item of hidden) await expect(nav.locator('.nav-item', { hasText: item }), `${role} does not see ${item}`).toHaveCount(0);
    await page.screenshot({ path: info.outputPath(`${role}.png`), fullPage: true });
    await page.goto('/settings/access');
    await expect(page.getByText('You do not have access to this page')).toBeVisible();
  });
}

test('signing out returns to the sign-in page', async ({ page }) => {
  await signIn(page, 'sales@endustry.local');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.locator('.sidebar')).toHaveCount(0);
  await page.goto('/sales/invoices');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});
