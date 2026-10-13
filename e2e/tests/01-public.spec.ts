import { expect, test } from '@playwright/test';
import { OWNER, PASSWORD, openLogin, watchErrors } from './helpers';

test.describe('Before sign-in', () => {
  test('text typed before the screen text loads is kept', async ({ page }) => {
    // First visit: nothing cached yet. Type at once, then let the translation bundle arrive.
    await page.route('**/api/public/i18n/en*', async (route) => { await new Promise((r) => setTimeout(r, 1500)); await route.continue(); });
    await page.goto('/login');
    await page.getByLabel('Email').fill(OWNER);
    await page.waitForLoadState('networkidle');
    await expect(page.getByLabel('Email')).toHaveValue(OWNER);
  });

  test('landing page shows the product and a way to sign in', async ({ page }) => {
    const errors = watchErrors(page);
    await page.goto('/');
    await expect(page.getByText('One ERP that fits')).toBeVisible();
    // Booking a demo is offered for the Enterprise option only.
    await expect(page.getByRole('link', { name: /Book a (free )?demo/ })).toHaveCount(1);
    await expect(page.locator('#pricing .lp-plan', { hasText: 'Enterprise' }).getByRole('link', { name: 'Book a demo' })).toBeVisible();
    await page.getByRole('link', { name: 'Sign in' }).first().click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    errors.check();
  });

  test('sign-in form checks the fields before sending', async ({ page }) => {
    await openLogin(page);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('Email is required')).toBeVisible();
    await expect(page.getByText('Password is required')).toBeVisible();
    await page.getByLabel('Email').fill('not-an-email');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('Email is not a valid email address')).toBeVisible();
  });

  test('a wrong password is refused with a message', async ({ page }) => {
    await openLogin(page);
    await page.getByLabel('Email').fill(OWNER);
    await page.getByLabel('Password').fill('wrong-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.locator('.sidebar')).toHaveCount(0);
  });

  test('a bookmarked screen opens after signing in', async ({ page }) => {
    await openLogin(page, '/inventory/stock');
    await page.getByLabel('Email').fill(OWNER);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('heading', { name: 'Stock', exact: true })).toBeVisible();
  });

  test('the sign-in page can be switched to Hindi', async ({ page }) => {
    await openLogin(page);
    await page.getByLabel('Language').selectOption('hi');
    await expect(page.getByRole('heading', { name: 'साइन इन करें' })).toBeVisible();
    await page.getByLabel(/भाषा|Language/).selectOption('en');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });
});
