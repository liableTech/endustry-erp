import { expect, test, type Browser, type Page } from '@playwright/test';
import { confirmIfAsked, field, openLogin, signIn, toast, unique, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/admin.json' });

const PASSWORD = 'Signup@Playwright1';
const flag = (page: Page, label: string) => page.locator('label.check', { hasText: label }).locator('input');

/** Sets one of the two sign-up switches on the product admin's Sign-ups page. */
async function setFlag(page: Page, label: string, on: boolean) {
  await page.goto('/platform/signups');
  const box = flag(page, label);
  await expect(box).toBeVisible();
  if ((await box.isChecked()) !== on) {
    await box.click();
    await expect(toast(page)).toContainText('Sign-up settings saved');
    await expect(box).toBeChecked({ checked: on });
  }
}

/** Sets how many months of free membership a new business gets; 0 switches it off. */
async function setFreeMonths(page: Page, count: number) {
  await page.goto('/platform/signups');
  const select = field(page, 'Free membership');
  await expect(select).toBeVisible();
  if ((await select.inputValue()) !== (count ? String(count) : '')) {
    await select.selectOption(count ? String(count) : '');
    await expect(toast(page)).toContainText('Sign-up settings saved');
    await expect(select).toHaveValue(count ? String(count) : '');
  }
}

/** Fills the "Your business" part of the sign-up form. */
async function fillForm(page: Page, industry: string, company: string, email: string) {
  await field(page, 'Company name').fill(company);
  await field(page, 'Industry').selectOption(industry);
  await field(page, 'Your name').fill('Pat Playwright');
  await field(page, 'Phone').fill('98765 43210');
  await field(page, 'Email').fill(email);
  await field(page, 'Password').fill(PASSWORD);
}

/** A visitor who is not signed in. */
async function visitor(browser: Browser) {
  const context = await browser.newContext({ storageState: undefined });
  return { context, page: await context.newPage() };
}

/** Fills the sign-up form and pays in the demo payment window. */
async function signUp(page: Page, plan: string, industry: string, company: string, email: string, total: string, shot?: (name: string) => string) {
  await page.goto('/');
  await page.locator('#pricing .lp-plan', { hasText: plan }).getByRole('link', { name: 'Sign up' }).click();
  await expect(page).toHaveURL(/\/signup\?plan=/);
  await expect(page.getByRole('button', { name: new RegExp(plan) })).toHaveAttribute('aria-pressed', 'true');

  // The form is checked before the payment window opens.
  await page.getByRole('button', { name: 'Continue to payment' }).click();
  await expect(page.getByText('Company name is required')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  await fillForm(page, industry, company, email);
  if (shot) await page.screenshot({ path: shot('1-sign-up-form'), fullPage: true });
  await page.getByRole('button', { name: 'Continue to payment' }).click();

  const payment = page.getByRole('dialog');
  await expect(payment).toContainText('This is a demo payment. No card is needed and no money is taken.');
  await expect(payment.locator('.lp-bill .total')).toContainText(total);
  await payment.getByLabel('Card').check();
  if (shot) await page.screenshot({ path: shot('2-demo-payment') });
  await payment.getByRole('button', { name: `Pay ${total}` }).click();
  await expect(page.getByText('Payment successful')).toBeVisible();
  await expect(page.locator('.lp-signup-note')).toContainText(/DEMO-[A-Z0-9]{10}/);
  await expect(page.getByRole('dialog')).toHaveCount(0);
}

test.describe.serial('Self sign-up', () => {
  test('while sign-up is off, only the Enterprise option is offered', async ({ page, browser }) => {
    await setFlag(page, 'Allow self sign-up', false);
    await setFlag(page, 'Approve sign-ups automatically', false);
    await setFreeMonths(page, 0);
    const { context, page: guest } = await visitor(browser);
    await guest.goto('/');
    await expect(guest.getByRole('link', { name: 'Sign up' })).toHaveCount(0);
    await expect(guest.locator('#pricing .lp-plan')).toHaveCount(1);
    await expect(guest.getByRole('link', { name: /Book a (free )?demo/ })).toHaveCount(1);
    await expect(guest.locator('#pricing .lp-plan', { hasText: 'Enterprise' }).getByRole('link', { name: 'Book a demo' })).toHaveAttribute('href', /^mailto:.*Enterprise%20demo/);
    await guest.goto('/signup');
    await expect(guest.getByRole('heading', { name: 'Sign-up is not open right now' })).toBeVisible();
    await expect(guest.getByRole('link', { name: 'Contact us' })).toBeVisible();
    await expect(guest.getByRole('link', { name: /Book a/ })).toHaveCount(0);
    await context.close();
  });

  test('a sign-up pays in the demo window, waits for approval and then gets its workspace', async ({ page, browser }, info) => {
    const errors = watchErrors(page);
    await setFlag(page, 'Allow self sign-up', true);
    const id = unique().toLowerCase();
    const company = `Playwright Signup Traders ${id}`;
    const email = `signup.${id}@playwright.test`;

    const { context, page: guest } = await visitor(browser);
    const guestErrors = watchErrors(guest);
    await guest.goto('/');
    await expect(guest.getByRole('link', { name: 'Sign up' }).first()).toBeVisible();
    // Anything custom still goes through the product team.
    const enterprise = guest.locator('#pricing .lp-plan', { hasText: 'Enterprise' });
    await expect(enterprise).toContainText('Deployed in your own environment');
    await expect(enterprise).toContainText('Your own database');
    await expect(enterprise).toContainText('White-label');
    // Booking a demo is only for Enterprise once sign-up is open: every other button signs up.
    await expect(guest.getByRole('link', { name: /Book a (free )?demo/ })).toHaveCount(1);
    await expect(enterprise.getByRole('link', { name: 'Book a demo' })).toHaveAttribute('href', /^mailto:.*Enterprise%20demo/);
    await expect(guest.locator('.lp-hero').getByRole('link', { name: 'Sign up' })).toBeVisible();
    await expect(guest.locator('#contact').getByRole('link', { name: 'Sign up' })).toBeVisible();
    await guest.locator('#pricing').scrollIntoViewIfNeeded();
    await guest.screenshot({ path: info.outputPath('0-pricing.png') });

    await signUp(guest, 'Starter', 'TRADING', company, email, '₹1,178.82', (name) => info.outputPath(`${name}.png`));
    await expect(guest.getByRole('heading', { name: 'We have your sign-up' })).toBeVisible();
    await guest.screenshot({ path: info.outputPath('3-waiting-for-approval.png') });
    guestErrors.check();

    // No workspace until the product admin approves.
    await openLogin(guest);
    await guest.getByLabel('Email').fill(email);
    await guest.getByLabel('Password').fill(PASSWORD);
    await guest.getByRole('button', { name: 'Sign in' }).click();
    await expect(guest.getByRole('alert')).toBeVisible();

    await page.goto('/platform/signups');
    const row = page.locator('table tbody tr', { hasText: company });
    await expect(row).toContainText('Waiting for approval');
    await expect(row).toContainText('Demo payment');
    await expect(row).toContainText('Starter');
    await page.screenshot({ path: info.outputPath('4-admin-requests.png'), fullPage: true });
    await row.getByRole('button', { name: 'Approve' }).click();
    await confirmIfAsked(page);
    await expect(row).toContainText('Approved');
    await expect(row.getByRole('button', { name: 'Approve' })).toHaveCount(0);

    // The Owner signs in with the password chosen at sign-up; Starter has no production or quality.
    await signIn(guest, email, PASSWORD);
    await expect(guest.locator('.topbar')).toContainText(company);
    await expect(guest.locator('.sidebar').getByText('Quotations')).toBeVisible();
    await expect(guest.locator('.sidebar').getByText('Work orders')).toHaveCount(0);
    await guest.screenshot({ path: info.outputPath('5-new-workspace.png'), fullPage: true });
    await context.close();

    await page.goto('/platform/clients');
    await expect(page.locator('table tbody tr', { hasText: company })).toContainText('Starter');
    errors.check();
  });

  test('with automatic approval the workspace is ready straight after payment', async ({ page, browser }, info) => {
    await setFlag(page, 'Allow self sign-up', true);
    await setFlag(page, 'Approve sign-ups automatically', true);
    const id = unique().toLowerCase();
    const company = `Playwright Signup Chem ${id}`;
    const email = `auto.${id}@playwright.test`;

    const { context, page: guest } = await visitor(browser);
    const errors = watchErrors(guest);
    await signUp(guest, 'Growth', 'CHEMICALS', company, email, '₹2,948.82');
    await expect(guest.getByRole('heading', { name: 'Your workspace is ready' })).toBeVisible();
    await guest.screenshot({ path: info.outputPath('workspace-ready.png') });
    await guest.locator('.lp-signup-note').getByRole('link', { name: 'Sign in' }).click();
    await guest.getByLabel('Email').fill(email);
    await guest.getByLabel('Password').fill(PASSWORD);
    await guest.getByRole('button', { name: 'Sign in' }).click();
    await expect(guest.locator('.sidebar').getByText('Formulations')).toBeVisible();
    errors.check();
    await context.close();

    await page.goto('/platform/signups');
    await expect(page.locator('table tbody tr', { hasText: company })).toContainText('automatic approval');
  });

  test('the sign-up page and plans are shown in Hindi', async ({ page, browser }, info) => {
    await setFlag(page, 'Allow self sign-up', true);
    const { context, page: guest } = await visitor(browser);
    await guest.goto('/signup');
    await expect(guest.getByRole('heading', { name: 'Create your account' })).toBeVisible();
    await guest.getByLabel('Language').selectOption('hi');
    await expect(guest.getByRole('heading', { name: 'अपना खाता बनाएँ' })).toBeVisible();
    await expect(guest.getByRole('button', { name: /स्टार्टर/ })).toBeVisible();
    await expect(guest.getByRole('button', { name: 'भुगतान के लिए आगे बढ़ें' })).toBeVisible();
    // Nothing on the page is left in English apart from the product name, emails and numbers.
    const texts = await guest.locator('.lp-signup-main h1, .lp-signup-main h2, .lp-signup-main h3, .lp-signup-main .field-label, .lp-signup-main button, .lp-signup-main li, .lp-signup-main .field-help').allInnerTexts();
    const english = texts.map((s) => s.trim()).filter((s) => /^[A-Za-z][A-Za-z ,.'()/-]{2,}$/.test(s) && !/^GST/.test(s));
    expect(english, 'sign-up text still in English while Hindi is chosen').toEqual([]);
    await guest.screenshot({ path: info.outputPath('sign-up-hindi.png'), fullPage: true });
    await guest.goto('/');
    await expect(guest.locator('#pricing .lp-plan', { hasText: 'एंटरप्राइज़' })).toContainText('आपके अपने एनवायरनमेंट में डिप्लॉय');
    await expect(guest.locator('#pricing .lp-plan', { hasText: 'एंटरप्राइज़' }).getByRole('link', { name: 'डेमो बुक करें' })).toBeVisible();
    await context.close();
  });

  test('free membership: nothing is charged and the workspace starts free', async ({ page, browser }, info) => {
    await setFlag(page, 'Allow self sign-up', true);
    await setFlag(page, 'Approve sign-ups automatically', true);
    await setFreeMonths(page, 2);
    const id = unique().toLowerCase();
    const company = `Playwright Free Member ${id}`;
    const email = `free.${id}@playwright.test`;

    const { context, page: guest } = await visitor(browser);
    const errors = watchErrors(guest);
    await guest.goto('/');
    await expect(guest.locator('.lp-hero').getByRole('link', { name: 'Start free for 2 months' })).toBeVisible();
    const growth = guest.locator('#pricing .lp-plan', { hasText: 'Growth' });
    await expect(growth).toContainText('Free for the first 2 months');
    await guest.locator('#pricing').scrollIntoViewIfNeeded();
    await guest.screenshot({ path: info.outputPath('1-free-plans.png') });
    await growth.getByRole('link', { name: 'Start free' }).click();
    await expect(guest.locator('.lp-free-note')).toContainText('your first 2 months cost nothing');

    await fillForm(guest, 'ENGINEERING', company, email);
    await guest.getByRole('button', { name: 'Continue', exact: true }).click();
    const dialog = guest.getByRole('dialog');
    await expect(dialog).toContainText('Your first 2 months are free. Nothing is charged today');
    await expect(dialog.locator('.lp-bill .total')).toContainText('₹0');
    await expect(dialog.getByRole('radio')).toHaveCount(0);
    await guest.screenshot({ path: info.outputPath('2-free-membership.png') });
    await dialog.getByRole('button', { name: 'Start free membership' }).click();
    const done = guest.locator('.lp-signup-note');
    await expect(done).toContainText('Free membership started');
    await expect(done).toContainText('Your workspace is ready');
    await expect(done).toContainText(/FREE-[A-Z0-9]{10}/);
    await signIn(guest, email, PASSWORD);
    errors.check();
    await context.close();

    await page.goto('/platform/signups');
    await expect(page.locator('table tbody tr', { hasText: company })).toContainText('Free for 2 months');
    await setFreeMonths(page, 0);
  });

  test('a coupon generated by the admin gives the visitor its discount', async ({ page, browser }, info) => {
    const errors = watchErrors(page);
    await setFlag(page, 'Allow self sign-up', true);
    await setFlag(page, 'Approve sign-ups automatically', true);
    await setFreeMonths(page, 0);
    const id = unique();
    const code = `PW-${id}`;
    const company = `Playwright Coupon Co ${id.toLowerCase()}`;
    const email = `coupon.${id.toLowerCase()}@playwright.test`;

    // The admin makes a 25% coupon; with the code left blank one is generated.
    await field(page, 'Coupon code').fill(code.toLowerCase());
    await field(page, 'Discount %').fill('25');
    await field(page, 'Note').fill('Playwright trade fair');
    await page.getByRole('button', { name: 'Generate coupon' }).click();
    await expect(toast(page)).toContainText(`Coupon ${code} created`);
    const couponRow = page.locator('table tbody tr', { has: page.locator('code', { hasText: code }) });
    await expect(couponRow).toContainText('25% off');
    await expect(couponRow).toContainText('Active');
    await field(page, 'Discount %').fill('5');
    await page.getByRole('button', { name: 'Generate coupon' }).click();
    await expect(toast(page)).toContainText(/Coupon [A-Z0-9]{8} created/);
    await page.screenshot({ path: info.outputPath('1-coupons.png'), fullPage: true });

    const { context, page: guest } = await visitor(browser);
    await guest.goto('/signup?plan=STARTER');
    const coupon = guest.getByPlaceholder('Coupon code');
    await coupon.fill('NOPE1234');
    await guest.getByRole('button', { name: 'Apply' }).click();
    await expect(guest.getByText('This coupon code is not valid')).toBeVisible();
    await coupon.fill(code.toLowerCase());
    await guest.getByRole('button', { name: 'Apply' }).click();
    await expect(guest.locator('.lp-coupon-ok')).toContainText('25% off: ₹749.25 a month instead of ₹999');
    // The discount follows the plan.
    await guest.getByRole('button', { name: /Growth/ }).click();
    await expect(guest.locator('.lp-coupon-ok')).toContainText('₹1,874.25 a month instead of ₹2,499');
    await guest.getByRole('button', { name: /Starter/ }).click();
    await expect(guest.locator('.lp-coupon-ok')).toContainText('₹749.25');

    await fillForm(guest, 'TRADING', company, email);
    await guest.getByRole('button', { name: 'Continue to payment' }).click();
    const dialog = guest.getByRole('dialog');
    await expect(dialog).toContainText(`Coupon ${code} (25% off)`);
    await expect(dialog).toContainText('-₹249.75');
    await expect(dialog.locator('.lp-bill .total')).toContainText('₹884.12');
    await guest.screenshot({ path: info.outputPath('2-payment-with-coupon.png') });
    await dialog.getByRole('button', { name: 'Pay ₹884.12' }).click();
    await expect(guest.locator('.lp-signup-note')).toContainText('Payment successful');
    await expect(guest.locator('.lp-signup-note')).toContainText(code);
    await context.close();

    // The use is counted, the request shows the coupon, and switching it off stops it.
    await page.goto('/platform/signups');
    await expect(page.locator('table tbody tr', { hasText: company })).toContainText(`Coupon ${code}`);
    await expect(couponRow.locator('td').nth(2)).toHaveText('1, no limit');
    // The admin edits the coupon: more businesses may use it, and the code is renamed.
    await couponRow.getByRole('button', { name: 'Edit' }).click();
    const editor = page.getByRole('dialog');
    await expect(field(editor, 'Coupon code')).toHaveValue(code);
    await expect(field(editor, 'Discount %')).toHaveValue('25');
    await field(editor, 'How many businesses can use it').fill('3');
    await field(editor, 'Coupon code').fill(`${code}X`);
    await page.screenshot({ path: info.outputPath('3-edit-coupon.png') });
    await editor.getByRole('button', { name: 'Save' }).click();
    await expect(toast(page)).toContainText(`Coupon ${code}X saved`);
    const renamedRow = page.locator('table tbody tr', { has: page.locator('code', { hasText: `${code}X` }) });
    await expect(renamedRow).toContainText('1 of 3 businesses');
    await expect(page.locator('table tbody tr', { hasText: company })).toContainText(`Coupon ${code}X`);
    await renamedRow.getByRole('button', { name: 'Edit' }).click();
    await field(editor, 'Coupon code').fill(code);
    await field(editor, 'How many businesses can use it').fill('0');
    await editor.getByRole('button', { name: 'Save' }).click();
    await expect(toast(page)).toContainText(`Coupon ${code} saved`);

    await couponRow.getByRole('button', { name: 'Switch off' }).click();
    await expect(couponRow).toContainText('Switched off');
    const { context: later, page: late } = await visitor(browser);
    await late.goto('/signup');
    await late.getByPlaceholder('Coupon code').fill(code);
    await late.getByRole('button', { name: 'Apply' }).click();
    await expect(late.getByText('This coupon code is not valid')).toBeVisible();
    await later.close();
    errors.check();
  });

  test('switching sign-up off again hides it from the home page', async ({ page, browser }) => {
    await setFlag(page, 'Approve sign-ups automatically', false);
    await setFlag(page, 'Allow self sign-up', false);
    const { context, page: guest } = await visitor(browser);
    await guest.goto('/');
    await expect(guest.locator('#pricing .lp-plan')).toHaveCount(1);
    await expect(guest.getByRole('link', { name: 'Sign up' })).toHaveCount(0);
    await context.close();
  });
});
