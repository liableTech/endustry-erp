import { test as setup } from '@playwright/test';
import { ADMIN, OWNER, signIn } from './helpers';

// Signs in once per role and keeps the session, so each test starts already signed in.
for (const [name, email] of [['owner', OWNER], ['admin', ADMIN], ['sales', 'sales@endustry.local']] as const) {
  setup(`sign in as ${name}`, async ({ page }) => {
    await signIn(page, email);
    await page.context().storageState({ path: `.test-data/auth/${name}.json` });
  });
}
