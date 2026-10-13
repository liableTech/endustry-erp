import { expect, test, type Browser, type Page } from '@playwright/test';
import { field, signIn, unique, watchErrors } from './helpers';

const OWNER_PASSWORD = 'Playwright@2026';

test.describe('Agriculture and Chemicals on the landing page', () => {
  test('both industries are shown with their own words, in English and Hindi', async ({ page }, info) => {
    const errors = watchErrors(page);
    await page.goto('/');
    const tabs = page.getByRole('tablist');
    const preview = page.locator('.lp-window');

    await tabs.getByRole('tab', { name: 'Agriculture' }).click();
    await expect(preview.locator('.lp-mini-nav')).toContainText('Godowns');
    await expect(preview.locator('.lp-mini-nav')).toContainText('Processing batches');
    await expect(page.locator('.lp-preview-note')).toContainText('Seeds, fertilisers and produce');
    await page.screenshot({ path: info.outputPath('1-landing-agriculture.png') });

    await tabs.getByRole('tab', { name: 'Chemicals' }).click();
    await expect(preview.locator('.lp-mini-nav')).toContainText('Formulations');
    await expect(preview.locator('.lp-mini-nav')).toContainText('Production batches');
    await expect(preview.locator('.lp-tiles')).toContainText('Production batches running');
    await page.screenshot({ path: info.outputPath('2-landing-chemicals.png') });

    const cards = page.locator('#industries .lp-industry');
    await expect(cards).toHaveCount(6);
    await expect(cards.filter({ hasText: 'Agriculture' })).toHaveCount(1);
    await expect(cards.filter({ hasText: 'Chemicals' })).toHaveCount(1);
    await expect(page.locator('.lp-stats')).toContainText('14');

    // The same text in Hindi comes from the translations in the database.
    await page.getByLabel('Language').selectOption('hi');
    await expect(tabs.getByRole('tab', { name: 'कृषि' })).toBeVisible();
    await tabs.getByRole('tab', { name: 'रसायन' }).click();
    await expect(preview.locator('.lp-mini-nav')).toContainText('फ़ॉर्मूलेशन');
    await expect(preview.locator('.lp-tiles')).toContainText('चल रहे उत्पादन बैच');
    await expect(page.locator('.lp-preview-note')).not.toContainText('Formulations');
    await tabs.getByRole('tab', { name: 'कृषि' }).click();
    await expect(preview.locator('.lp-mini-nav')).toContainText('प्रसंस्करण बैच');
    await expect(page.locator('.lp-preview-note')).not.toContainText('Seeds');
    const agriculture = page.locator('#industries .lp-industry').filter({ hasText: 'कृषि' }).first();
    await expect(agriculture).not.toContainText(/[A-Za-z]{4,}/);
    await agriculture.scrollIntoViewIfNeeded();
    await page.screenshot({ path: info.outputPath('3-landing-hindi.png') });
    await page.getByLabel(/भाषा|Language/).selectOption('en');
    await expect(tabs.getByRole('tab', { name: 'Agriculture' })).toBeVisible();
    errors.check();
  });
});

test.describe('New industry templates for clients', () => {
  test.use({ storageState: '.test-data/auth/admin.json' });

  /** Creates a client of this industry as the product admin and returns the Owner's sign-in email. */
  async function createClient(page: Page, industry: string, industryLabel: string, name: string) {
    const ownerEmail = `owner.${unique().toLowerCase()}.${industry.toLowerCase()}@playwright.test`;
    await page.goto('/platform/clients');
    await page.getByRole('button', { name: 'New client' }).click();
    await field(page, 'Company name').fill(name);
    await field(page, 'Industry').selectOption(industry);
    // A manufacturing template keeps every module on.
    for (const module of ['sales', 'purchase', 'inventory', 'production', 'quality', 'reports']) {
      await expect(page.locator('label.check', { hasText: new RegExp(`^${module}$`, 'i') }).locator('input')).toBeChecked();
    }
    await field(page, 'Owner name').fill('Pat Playwright');
    await field(page, 'Owner email').fill(ownerEmail);
    await field(page, 'Starting password').fill(OWNER_PASSWORD);
    await page.getByRole('button', { name: 'Create client' }).click();
    const credentials = page.getByRole('dialog');
    await expect(credentials).toContainText(ownerEmail);
    await credentials.getByRole('button', { name: 'I have saved these' }).click();
    await expect(page.locator('table tbody tr', { hasText: name })).toContainText(industryLabel);
    return ownerEmail;
  }

  /** Signs the new Owner in and checks the menu words and starting lists, first in English and then in Hindi. */
  async function checkWorkspace(browser: Browser, ownerEmail: string, shot: (name: string) => string,
    english: { menu: string[]; options: string[] }, hindi: { menu: string[]; options: string[] }) {
    const context = await browser.newContext({ storageState: undefined });
    const owner = await context.newPage();
    const errors = watchErrors(owner);
    await signIn(owner, ownerEmail, OWNER_PASSWORD);
    const nav = owner.locator('.sidebar');
    for (const word of english.menu) await expect(nav.getByText(word, { exact: true })).toBeVisible();
    await owner.goto('/masters/items/new');
    for (const option of english.options) await expect(owner.locator('select option', { hasText: option })).not.toHaveCount(0);
    await owner.screenshot({ path: shot('english'), fullPage: true });

    await owner.locator('.topbar').getByLabel('Language').selectOption('hi');
    for (const word of hindi.menu) await expect(nav.getByText(word, { exact: true }).first()).toBeVisible();
    for (const word of english.menu) await expect(nav.getByText(word, { exact: true })).toHaveCount(0);
    await owner.goto('/masters/items/new');
    for (const option of hindi.options) await expect(owner.locator('select option', { hasText: option })).not.toHaveCount(0);
    await owner.screenshot({ path: shot('hindi'), fullPage: true });
    await owner.locator('.topbar').getByRole('combobox').first().selectOption('en');
    await expect(nav.getByText(english.menu[0], { exact: true })).toBeVisible();
    errors.check();
    await context.close();
  }

  test('the industry list offers the new templates', async ({ page }) => {
    await page.goto('/platform/clients');
    await page.getByRole('button', { name: 'New client' }).click();
    const options = field(page, 'Industry').locator('option');
    for (const label of ['Agriculture and agro-processing', 'Chemicals, paints and coatings', 'Pharmaceuticals and healthcare products', 'Electrical and electronics']) {
      await expect(options.filter({ hasText: label })).toHaveCount(1);
    }
  });

  test('an Agriculture client starts with godowns, processing batches and farm lists', async ({ page, browser }, info) => {
    const errors = watchErrors(page);
    const email = await createClient(page, 'AGRICULTURE', 'Agriculture and agro-processing', `Playwright Agro ${unique()}`);
    await checkWorkspace(browser, email, (name) => info.outputPath(`agriculture-${name}.png`),
      { menu: ['Godowns', 'Processing batches', 'Suppliers'], options: ['SEED - Seeds', 'FERTILISER - Fertilisers', 'PRODUCE - Farm produce', 'QTL - Quintals', 'BAG - Bags'] },
      { menu: ['प्रसंस्करण बैच', 'सप्लायर'], options: ['SEED - बीज', 'FERTILISER - उर्वरक', 'PRODUCE - कृषि उपज', 'QTL - क्विंटल', 'BAG - बोरे'] });
    errors.check();
  });

  test('a Chemicals client starts with formulations, production batches and chemical lists', async ({ page, browser }, info) => {
    const errors = watchErrors(page);
    const email = await createClient(page, 'CHEMICALS', 'Chemicals, paints and coatings', `Playwright Chem ${unique()}`);
    await checkWorkspace(browser, email, (name) => info.outputPath(`chemicals-${name}.png`),
      { menu: ['Formulations', 'Production batches', 'Products'], options: ['RAW_CHEMICAL - Raw chemicals', 'SOLVENT - Solvents', 'INTERMEDIATE - Intermediates', 'DRUM - Drums', 'TON - Tonnes'] },
      { menu: ['फ़ॉर्मूलेशन', 'उत्पादन बैच', 'प्रोडक्ट'], options: ['RAW_CHEMICAL - कच्चे रसायन', 'SOLVENT - सॉल्वेंट', 'INTERMEDIATE - इंटरमीडिएट', 'DRUM - ड्रम', 'TON - टन'] });
    errors.check();
  });

  test('the new words can be edited under Translations', async ({ page }) => {
    await page.goto('/platform/translations');
    for (const [english, hindi] of [['Processing batches', 'प्रसंस्करण बैच'], ['Formulations', 'फ़ॉर्मूलेशन'], ['Agriculture and agro-processing', 'कृषि और कृषि-प्रसंस्करण'], ['Raw chemicals', 'कच्चे रसायन']]) {
      await page.getByRole('textbox', { name: /Search/ }).fill(english);
      const row = page.locator('table tbody tr').filter({ has: page.getByText(english, { exact: true }) }).first();
      await expect(row).toContainText(hindi);
    }
  });
});
