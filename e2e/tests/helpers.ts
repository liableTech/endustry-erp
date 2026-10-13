import { expect, type Locator, type Page } from '@playwright/test';

export const PASSWORD = process.env.ERP_SEED_PASSWORD ?? 'Endustry@2026';
export const OWNER = 'owner@endustry.local';
export const ADMIN = 'admin@endustry.local';

/** A short unique suffix, so records made by one run never clash with the demo data. */
export const unique = () => Date.now().toString(36).slice(-5).toUpperCase();

/** Opens the sign-in page and waits for the screen text to load. */
export async function openLogin(page: Page, path = '/login') {
  await page.goto(path);
  await page.waitForLoadState('networkidle');
}

export async function signIn(page: Page, email: string, password = PASSWORD) {
  await openLogin(page);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.locator('.sidebar')).toBeVisible();
}

/**
 * Records every failed API call and browser error while a test runs, so a screen that looks fine but
 * logged a 500 still fails. Call check() at the end of the test.
 */
export function watchErrors(page: Page, allow: RegExp[] = []) {
  const problems: string[] = [];
  page.on('response', (response) => {
    const url = response.url();
    if (url.includes('/api/') && response.status() >= 500) problems.push(`${response.status()} ${response.request().method()} ${url}`);
  });
  page.on('pageerror', (error) => problems.push(`Page error: ${error.message}`));
  page.on('console', (message) => {
    if (message.type() === 'error' && !/Failed to load resource/.test(message.text())) problems.push(`Console: ${message.text()}`);
  });
  return {
    problems,
    check() {
      const real = problems.filter((p) => !allow.some((a) => a.test(p)));
      expect(real, 'server errors or browser errors during the test').toEqual([]);
    },
  };
}

/** The visible toast message after an action. */
export const toast = (page: Page) => page.locator('.toasts .toast').last();

/** The full-page record view (the editor or detail sheet). */
export const sheet = (page: Page) => page.locator('.full-page').first();

/** Clicks a footer or header button and confirms the "Are you sure" dialog if one opens. */
export async function clickAndConfirm(page: Page, name: string | RegExp) {
  await page.getByRole('button', { name, exact: typeof name === 'string' }).first().click();
  await confirmIfAsked(page);
}

/** Presses the main button of the "Are you sure" dialog when one is open. */
export async function confirmIfAsked(page: Page) {
  const dialog = page.getByRole('dialog');
  if (await dialog.waitFor({ state: 'visible', timeout: 1500 }).then(() => true, () => false)) {
    await dialog.locator('.sheet-foot button').last().click();
    await expect(dialog).toBeHidden();
  }
}

/** Picks the first real option of the select inside the field with this label. */
export async function chooseFirst(page: Page, label: string | RegExp, scope = page.locator('body')) {
  const select = scope.getByLabel(label).first();
  const value = await select.locator('option').nth(1).getAttribute('value');
  await select.selectOption(value!);
  return value!;
}

/** Runs a document action button and answers any confirmation (including the Owner's credit-limit override). */
export async function runAction(page: Page, name: string) {
  await page.locator('.sheet-foot').getByRole('button', { name, exact: true }).click();
  for (let i = 0; i < 2; i++) await confirmIfAsked(page);
}

/** Status badge in the record page title. */
export const titleStatus = (page: Page) => page.locator('.full-page .sheet-title .badge');

/** Calls the API with the signed-in user's token, for looking things up that the test needs. */
export async function apiGet<T = any>(page: Page, path: string): Promise<T> {
  const token = await page.evaluate(() => localStorage.getItem('endustry.token'));
  const response = await page.request.get(`/api${path}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `GET ${path}`).toBeTruthy();
  return response.json();
}

/** Matches a form field by its label, ignoring the required star and the help text after it. */
export const label = (text: string) => new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\*?(\\s|$)`);

/** The input, select or text box of the form field with this label (the required star is ignored). */
export function field(scope: Page | Locator, text: string) {
  const page = 'page' in scope && typeof (scope as Locator).page === 'function' ? (scope as Locator).page() : (scope as Page);
  const name = page.locator('.field-label', { hasText: label(text) });
  return scope.locator('.field', { has: name }).locator('input:not([type=color]), select, textarea').first();
}
