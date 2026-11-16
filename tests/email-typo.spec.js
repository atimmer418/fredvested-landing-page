// The "Did you mean name@gmail.com?" hint (frontend/assets/email-typo.js) on the waitlist
// forms of index.html and about.html. A suggestable address shows the hint on blur; the
// first submit of it is held (no POST to /api/waitlist, no Waitlist Failed event) and the
// second goes through with what was typed; the address in the hint is a button that puts
// the fix in the field; a correct address never shows anything; the hint adds no height
// until it shows and is gone in the success state. The suggestion logic itself is unit
// tested in tests/unit/email-typo.test.js.
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, eventNames, waitForTurnstile, revealIfNeeded, DOUBLE_OPT_IN_SIGNUP } = require('./helpers');

const PAGES = [
  { name: 'index.html', path: '/index.html' },
  { name: 'about.html', path: '/about.html' },
];

const hint = (page) => page.locator('#capture-email-typo');

async function open(page, path, mocks) {
  const calls = await installMocks(page, mocks);
  await page.goto(path);
  await revealIfNeeded(page);
  await waitForTurnstile(page);
  return calls;
}

// Puts a real hint on screen whatever the form's state: sets the (possibly hidden) field to a
// typo and fires its blur, which is the module's own show() path.
async function showHintNow(page, value) {
  await page.evaluate((v) => {
    const input = document.getElementById('capture-email');
    input.value = v;
    input.dispatchEvent(new Event('blur'));
  }, value);
  await expect(hint(page)).toContainText('Did you mean');
}

// Lets any request the submit would have made reach the mock before counting.
async function settle(page) {
  await page.waitForTimeout(600);
}

for (const { name, path } of PAGES) {
  test.describe(name, () => {
    test('a typo shows the hint on blur, the hint\'s button fixes the value and a fresh value hides it', async ({ page }) => {
      await open(page, path);
      const input = page.locator('#capture-email');
      await expect(hint(page)).toHaveAttribute('aria-live', 'polite');
      await expect(hint(page)).toBeEmpty();

      await input.fill('Ann.Smith@gmial.con');
      await input.blur();
      await expect(hint(page)).toHaveText('Did you mean Ann.Smith@gmail.com?');
      await expect(hint(page).locator('button')).toHaveText('Ann.Smith@gmail.com');

      await hint(page).locator('button').click();
      await expect(input).toHaveValue('Ann.Smith@gmail.com');
      await expect(input).toBeFocused();
      await expect(hint(page)).toBeEmpty();

      // Any edit hides it at once.
      await input.fill('ann@yaho.com');
      await input.blur();
      await expect(hint(page)).toHaveText('Did you mean ann@yahoo.com?');
      await input.focus();
      await page.keyboard.press('End');
      await page.keyboard.type('x');
      await expect(hint(page)).toBeEmpty();
    });

    test('the first submit of a typo is held with no request; the second goes through with what was typed', async ({ page }) => {
      await forceAnalytics(page);
      const calls = await open(page, path);
      const input = page.locator('#capture-email');
      await input.fill('ann@gmial.com');
      // Enter submits without a blur: the hint shows and the submit is held.
      await input.press('Enter');
      await expect(hint(page)).toHaveText('Did you mean ann@gmail.com?');
      await settle(page);
      expect(calls.signup).toHaveLength(0);
      expect(await eventNames(page)).not.toContain('Waitlist Failed');
      await expect(page.locator('#waitlist-form')).toBeVisible();

      const done = page.waitForResponse((r) => /\/api\/waitlist(\?|$)/.test(r.url()) && r.request().method() === 'POST');
      await input.press('Enter');
      await done;
      expect(calls.signup).toHaveLength(1);
      expect(calls.signup[0].email).toBe('ann@gmial.com');
      await expect(page.locator('#success-block')).toBeVisible({ timeout: 10_000 });
      await expect(hint(page)).toBeEmpty();
      await expect(hint(page)).toBeHidden();
      expect(await eventNames(page)).not.toContain('Waitlist Failed');
    });

    test('a click on the button with a typo is held too (its blur is not reading the hint); the fix then submits', async ({ page }) => {
      const calls = await open(page, path);
      await page.locator('#capture-email').fill('ann@hotmial.com');
      await page.locator('#join-waitlist-btn').click();
      await expect(hint(page)).toHaveText('Did you mean ann@hotmail.com?');
      await settle(page);
      expect(calls.signup).toHaveLength(0);

      await hint(page).locator('button').click();
      const done = page.waitForResponse((r) => /\/api\/waitlist(\?|$)/.test(r.url()) && r.request().method() === 'POST');
      await page.locator('#join-waitlist-btn').click();
      await done;
      expect(calls.signup[0].email).toBe('ann@hotmail.com');
      await expect(page.locator('#success-block')).toBeVisible({ timeout: 10_000 });
      await expect(hint(page)).toBeEmpty();
    });

    test('a slow press on the button (held over a second) is still held: the blur it causes is not reading the hint', async ({ page }) => {
      const calls = await open(page, path);
      await page.locator('#capture-email').fill('ann@hotmial.com');
      const box = await page.locator('#join-waitlist-btn').boundingBox();
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await expect(hint(page)).toHaveText('Did you mean ann@hotmail.com?');
      await page.waitForTimeout(1300);
      await page.mouse.up();
      await settle(page);
      expect(calls.signup).toHaveLength(0);
      await expect(hint(page)).toHaveText('Did you mean ann@hotmail.com?');
    });

    test('a hint on screen is cleared by the success state', async ({ page }) => {
      const calls = await open(page, path);
      await page.locator('#capture-email').fill('ann@gmail.com');
      const done = page.waitForResponse((r) => /\/api\/waitlist(\?|$)/.test(r.url()) && r.request().method() === 'POST');
      await page.locator('#join-waitlist-btn').click();
      await done;
      // The page holds the loading state for about 2s after the response: a hint shown now is
      // still on screen when the state changes, so only showSuccess's hide() can clear it.
      await showHintNow(page, 'ann@gmial.com');
      await expect(page.locator('#success-block')).toBeVisible({ timeout: 10_000 });
      await expect(hint(page)).toBeEmpty();
      expect(calls.signup).toHaveLength(1);
    });

    test('under double opt-in a hint on screen is cleared by the pending state and by "Use a different one"', async ({ page }) => {
      await open(page, path, { signup: DOUBLE_OPT_IN_SIGNUP });
      await page.locator('#capture-email').fill('ann@gmail.com');
      const done = page.waitForResponse((r) => /\/api\/waitlist(\?|$)/.test(r.url()) && r.request().method() === 'POST');
      await page.locator('#join-waitlist-btn').click();
      await done;
      await showHintNow(page, 'ann@gmial.com');
      await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
      await expect(hint(page)).toBeEmpty();

      // A hint shown while pending (the form is hidden) is cleared by the reset to the form.
      await showHintNow(page, 'ann@yaho.com');
      await page.locator('#pending-reset').click();
      await expect(page.locator('#waitlist-form')).toBeVisible();
      await expect(hint(page)).toBeEmpty();
    });

    test('a correct address never shows the hint and submits on the first try', async ({ page }) => {
      const calls = await open(page, path);
      const input = page.locator('#capture-email');
      for (const value of ['ann@gmail.com', 'ann@fredvested.com', 'ann@mail.com', 'ann@umich.edu']) {
        await input.fill(value);
        await input.blur();
        await settle(page);
        await expect(hint(page)).toBeEmpty();
      }
      const done = page.waitForResponse((r) => /\/api\/waitlist(\?|$)/.test(r.url()) && r.request().method() === 'POST');
      await page.locator('#join-waitlist-btn').click();
      await done;
      expect(calls.signup).toHaveLength(1);
      expect(calls.signup[0].email).toBe('ann@umich.edu');
      await expect(hint(page)).toBeEmpty();
    });

    for (const width of [320, 360, 390]) {
      test(`at ${width}px the empty hint adds no height and a long suggestion stays inside the page`, async ({ page }) => {
        await page.setViewportSize({ width, height: 660 });
        await open(page, path);
        const box = await hint(page).boundingBox();
        expect(box === null || box.height === 0).toBe(true);

        // In page coordinates, so a scroll still settling (focus, a smooth scroll) is not a move.
        const btnTop = () => page.locator('#join-waitlist-btn').evaluate((el) => el.getBoundingClientRect().top + window.scrollY);
        const btnBefore = await btnTop();
        await page.locator('#capture-email').fill('a.very.long.name.for.a.waitlist.member@gmial.com');
        await page.locator('#capture-email').blur();
        await expect(hint(page)).toContainText('Did you mean');
        // The hint sits under the form: the button does not move when it shows.
        const btnAfter = await btnTop();
        expect(btnAfter).toBe(btnBefore);
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        expect(overflow).toBeLessThanOrEqual(0);
        const hintBox = await hint(page).boundingBox();
        expect(hintBox.x).toBeGreaterThanOrEqual(0);
        expect(hintBox.x + hintBox.width).toBeLessThanOrEqual(width);
      });
    }
  });
}
