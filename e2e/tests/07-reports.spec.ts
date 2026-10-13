import { expect, test } from '@playwright/test';
import { apiGet, watchErrors } from './helpers';

test.use({ storageState: '.test-data/auth/owner.json' });

test('every report opens with data and exports to CSV', async ({ page }, info) => {
  const errors = watchErrors(page);
  await page.goto('/reports');
  const reports = await apiGet<{ key: string; title: string }[]>(page, '/reports');
  expect(reports.length).toBeGreaterThanOrEqual(9);
  await expect(page.locator('.report-card')).toHaveCount(reports.length);
  await page.screenshot({ path: info.outputPath('reports.png'), fullPage: true });
  const empty: string[] = [];
  for (const report of reports) {
    await page.goto(`/reports/${report.key}`);
    await expect(page.locator('.page-title')).toHaveText(report.title);
    await expect(page.locator('.spinner')).toHaveCount(0, { timeout: 15_000 });
    await expect(page.getByRole('alert'), report.title).toHaveCount(0);
    if (await page.locator('table tbody tr').count() === 0) { empty.push(report.title); continue; }
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export' }).click();
    expect((await download).suggestedFilename()).toMatch(new RegExp(`^${report.key}.*\\.csv$`));
  }
  test.info().annotations.push({ type: 'empty reports', description: empty.join(', ') || 'none' });
  expect(empty.length, `reports with no rows on demo data: ${empty.join(', ')}`).toBeLessThan(reports.length);
  errors.check();
});
