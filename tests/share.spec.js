// "Share my Freedom Date" (2026-11-14). After a signup the success state carries a share row;
// it opens FRED's own sheet with three actions: the phone's share menu, a story-sized image to
// save, and "Copy link". The link carries the rounded age and the month and year, nothing else
// (frontend/assets/share.js builds and validates it). A recipient lands on /s?r=...: the plain
// calculator at its defaults, with `r` gone from the address before Plausible's pageview.
//
// Nothing of the row, the sheet or the story view exists in the DOM before the success state.
// Shareable: a dated result (under 65, or 65 and later). Not the at-target state, not the two
// no-date states. Copy is attorney-pending and checked verbatim here.
//
// Reference numbers (frontend/assets/projection.js; moderate 10%):
//   defaults 30 / $2,000 / $7,500 / $0:   53  -> bands 11+ / plan
//   42 / $500 / $4,000 / $0:               73  -> bands hidden / hidden (65 and later)
//   30 / $2,000 / $1,000 / $1,000,000:     at the target, no date
//   30 / $0 / $7,500 / $0:                 no investment, no date
//   60 / $50 / $30,000 / $0:               more than a lifetime away, no date
const { test, expect } = require('@playwright/test');
const {
  SHARE_PATH, installMocks, forceAnalytics, seedStorage, events, eventNames, pageviewUrl,
  collectHosts, assertOnlyAllowedHosts, submitWaitlist, storage,
} = require('./helpers');
const FredShare = require('../frontend/assets/share.js');

const RSQUO = String.fromCharCode(0x2019);
const COPY = {
  SHARE_LABEL: 'Show someone your date',
  SHARE_SUB: 'Sends your age and date. Nothing else you entered.',
  SHARE_BUTTON: 'Share',
  SHEET_TITLE: 'Share your Freedom Date',
  SHEET_LINK: 'Share link',
  SHEET_LINK_SUB: `Opens your phone${RSQUO}s share menu`,
  SHEET_STORY: 'Save a story postable image',
  SHEET_STORY_SUB: 'Story size, ready for Instagram or TikTok',
  SHEET_COPY: 'Copy link',
  SHEET_COPY_SUB: 'Pastes with your Freedom Date as the preview',
  SHEET_COPIED: 'Link copied',
  SHEET_FINE: 'The link carries your age and date only. No email, no income, no amounts.',
  STORY_HINT: 'Press and hold the image to save it.',
  SHEET_CLOSE: 'Close',
};
const ORIGIN = 'http://localhost:5500';
const API_ORIGIN = 'http://localhost:8081';
const SHARE_IDS = ['share-row', 'share-open', 'share-sheet', 'share-sheet-thumb', 'share-native', 'share-story', 'share-copy', 'share-copy-status', 'share-close', 'story-view', 'story-img', 'story-close'];
const PHONE = { width: 390, height: 660 };

const named = (list, name) => list.filter((e) => e.name === name);

/** How many of the share elements exist in the document (a <template>'s content does not count). */
async function shareElementCount(page) {
  return page.evaluate((ids) => ids.filter((id) => document.getElementById(id)).length, SHARE_IDS);
}

/** navigator.clipboard.writeText replaced by a recorder (window.__copied). */
async function stubClipboard(page) {
  await page.addInitScript(() => {
    window.__copied = [];
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: (text) => { window.__copied.push(text); return Promise.resolve(); } },
    });
  });
}

/**
 * navigator.share replaced: 'ok' resolves, 'abort' rejects with AbortError (the visitor backed out),
 * 'reject' rejects with NotAllowedError (the share menu could not open), 'throw' throws at the call,
 * 'missing' removes it.
 */
async function stubShare(page, mode) {
  await page.addInitScript((m) => {
    window.__shared = [];
    try { delete Navigator.prototype.share; } catch (e) { /* not there */ }
    if (m === 'missing') return;
    Object.defineProperty(navigator, 'share', {
      configurable: true,
      value: (data) => {
        window.__shared.push(data);
        if (m === 'abort') return Promise.reject(new DOMException('Share canceled', 'AbortError'));
        if (m === 'reject') return Promise.reject(new DOMException('Permission denied', 'NotAllowedError'));
        if (m === 'throw') throw new TypeError('share failed');
        return Promise.resolve();
      },
    });
  }, mode);
}

/** The result on the card (the comparison's right cell) as share.js sees it. */
async function resultOnCard(page) {
  const age = Number(await page.locator('#compare-right-age').textContent());
  const monthYear = await page.locator('#compare-right-date').textContent();
  const result = FredShare.fromSaved(age, monthYear, new Date());
  expect(result, `the card's result (${age}, ${monthYear}) is shareable`).not.toBeNull();
  return result;
}

async function signUp(page, email) {
  await submitWaitlist(page, email || 'share@example.com');
  await expect(page.locator('#success-block')).toBeVisible();
}

async function openSheet(page) {
  await page.click('#share-open');
  await expect(page.locator('#share-sheet')).toBeVisible();
}

test.describe('where the share control exists', () => {
  test('nothing of it is in the DOM before the reveal, or after the reveal and before a signup', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    expect(await shareElementCount(page)).toBe(0);
    await page.click('#reveal-btn');
    await expect(page.locator('#capture-email')).toBeVisible();
    expect(await shareElementCount(page)).toBe(0);
  });

  test('after a dated signup the row is under the note, with its copy, and the existing button and note are unchanged', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await signUp(page);
    await expect(page.locator('#share-row')).toBeVisible();
    await expect(page.locator('#success-block #share-row')).toHaveCount(1);
    await expect(page.locator('#share-row')).toContainText(COPY.SHARE_LABEL);
    await expect(page.locator('#share-row')).toContainText(COPY.SHARE_SUB);
    await expect(page.locator('#share-open')).toHaveText(COPY.SHARE_BUTTON);
    await expect(page.locator('#success-link')).toHaveText(`You${RSQUO}re in! See how FRED works →`);
    await expect(page.locator('#success-note')).toHaveText(`You${RSQUO}re in line for a Founder invite.`);
    const order = await page.evaluate(() => {
      const note = document.getElementById('success-note').getBoundingClientRect();
      const row = document.getElementById('share-row').getBoundingClientRect();
      return { noteBottom: note.bottom, rowTop: row.top };
    });
    expect(order.rowTop).toBeGreaterThanOrEqual(order.noteBottom);
    // The sheet and the story view exist now, closed.
    await expect(page.locator('#share-sheet')).toBeHidden();
    await expect(page.locator('#story-view')).toBeHidden();
    await expect(page.locator('#share-sheet')).toHaveAttribute('role', 'dialog');
    await expect(page.locator('#share-sheet')).toHaveAttribute('aria-modal', 'true');
  });

  test('a 65-and-later signup shows the row too', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await page.click('#reveal-btn');
    await expect(page.locator('#compare-left-age')).toHaveText('No date');
    await signUp(page);
    await expect(page.locator('#share-row')).toBeVisible();
  });

  const NOT_SHAREABLE = [
    { name: 'the at-target state', set: async (page) => { await page.fill('#retire-range', '1000'); await page.fill('#existing-range', '76'); }, shows: '#at-target-block' },
    { name: 'the no-investment state', set: async (page) => { await page.fill('#invest-range', '0'); }, shows: '#infeasible-block' },
    { name: 'the more-than-a-lifetime state', set: async (page) => { await page.fill('#age-range', '60'); await page.fill('#invest-range', '50'); await page.fill('#retire-range', '30000'); }, shows: '#infeasible-block' },
  ];
  for (const state of NOT_SHAREABLE) {
    test(`a signup in ${state.name} hides the row and clears the saved freedom date`, async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      // A freedom date left behind by an earlier result must not survive this signup.
      await page.evaluate(() => localStorage.setItem('waitlist_freedom', JSON.stringify({ age: 45, monthYear: 'March 2040' })));
      await state.set(page);
      await page.click('#reveal-btn');
      await expect(page.locator(state.shows)).toBeVisible();
      await signUp(page);
      await expect(page.locator('#share-row')).toBeHidden();
      expect(await storage(page, 'waitlist_freedom')).toBeNull();
    });
  }

  test('the double opt-in pending state has no share row', async ({ page }) => {
    const { DOUBLE_OPT_IN_SIGNUP } = require('./helpers');
    await installMocks(page, { signup: DOUBLE_OPT_IN_SIGNUP });
    await page.goto('/index.html');
    await submitWaitlist(page, 'pending@example.com');
    await expect(page.locator('#pending-block')).toBeVisible();
    await expect(page.locator('#share-row')).toBeHidden();
    await expect(page.locator('#pending-block #share-row')).toHaveCount(0);
  });
});

test.describe('the fit on a phone', () => {
  test.use({ viewport: PHONE });

  test('at 390 x 660 the form is in view after the reveal exactly as before, and the row and sheet fit after the signup', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    await expect(page.locator('#capture-email')).toBeVisible();
    const rects = () => page.evaluate(() => {
      const r = (id) => { const b = document.getElementById(id).getBoundingClientRect(); return { top: b.top, bottom: b.bottom, left: b.left, right: b.right }; };
      return { card: r('result-card'), input: r('capture-email'), button: r('join-waitlist-btn'), vh: window.innerHeight, vw: window.innerWidth };
    });
    await expect.poll(async () => Math.round((await rects()).card.top), { timeout: 3000 }).toBe(8);
    await page.waitForTimeout(300);
    const r = await rects();
    for (const box of [r.input, r.button]) {
      expect(box.top).toBeGreaterThanOrEqual(0);
      expect(box.bottom).toBeLessThanOrEqual(r.vh);
    }
    expect(await shareElementCount(page)).toBe(0);

    await signUp(page);
    await expect(page.locator('#share-row')).toBeVisible();
    const row = await page.evaluate(() => {
      const card = document.getElementById('result-card').getBoundingClientRect();
      const b = document.getElementById('share-row').getBoundingClientRect();
      const btn = document.getElementById('share-open').getBoundingClientRect();
      return { left: b.left, right: b.right, cardLeft: card.left, cardRight: card.right, btnH: btn.height, scrollW: document.documentElement.scrollWidth, vw: window.innerWidth };
    });
    expect(row.left).toBeGreaterThanOrEqual(row.cardLeft);
    expect(row.right).toBeLessThanOrEqual(row.cardRight);
    expect(row.btnH).toBeGreaterThanOrEqual(44);
    expect(row.scrollW).toBeLessThanOrEqual(row.vw);
  });

  for (const width of [320, 390]) {
    test(`at ${width}px the open sheet sits on the viewport bottom, inside the viewport, with every action reachable`, async ({ page }) => {
      await page.setViewportSize({ width, height: 660 });
      await stubShare(page, 'ok');
      await installMocks(page);
      await page.goto('/index.html');
      await signUp(page);
      await openSheet(page);
      await page.waitForTimeout(400); // the slide
      const box = await page.evaluate(() => {
        const b = document.getElementById('share-sheet').getBoundingClientRect();
        const rows = ['share-native', 'share-story', 'share-copy', 'share-close'].map((id) => {
          const x = document.getElementById(id).getBoundingClientRect();
          return { id, top: x.top, bottom: x.bottom, left: x.left, right: x.right, h: x.height };
        });
        return { top: b.top, bottom: b.bottom, left: b.left, right: b.right, rows, vh: window.innerHeight, vw: window.innerWidth, scrollW: document.documentElement.scrollWidth };
      });
      expect(Math.round(box.bottom)).toBe(box.vh);
      expect(box.top).toBeGreaterThanOrEqual(0);
      expect(box.left).toBeGreaterThanOrEqual(0);
      expect(box.right).toBeLessThanOrEqual(box.vw);
      expect(box.scrollW).toBeLessThanOrEqual(box.vw);
      for (const row of box.rows) {
        expect(row.top, row.id).toBeGreaterThanOrEqual(box.top);
        expect(row.bottom, row.id).toBeLessThanOrEqual(box.bottom);
        expect(row.left, row.id).toBeGreaterThanOrEqual(box.left);
        expect(row.right, row.id).toBeLessThanOrEqual(box.right);
        expect(row.h, row.id).toBeGreaterThanOrEqual(44);
      }
    });
  }
});

test.describe('the sheet', () => {
  test('carries its copy, the link image as the thumbnail and the result as "month year, age N"', async ({ page }) => {
    await stubShare(page, 'ok');
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    const result = await resultOnCard(page);
    await signUp(page);
    await openSheet(page);
    const sheet = page.locator('#share-sheet');
    await expect(sheet).toContainText(COPY.SHEET_TITLE);
    await expect(sheet).toContainText(`${result.dateLabel}, age ${result.age}`);
    await expect(page.locator('#share-native')).toContainText(COPY.SHEET_LINK);
    await expect(page.locator('#share-native')).toContainText(COPY.SHEET_LINK_SUB);
    await expect(page.locator('#share-story')).toContainText(COPY.SHEET_STORY);
    await expect(page.locator('#share-story')).toContainText(COPY.SHEET_STORY_SUB);
    await expect(page.locator('#share-copy')).toContainText(COPY.SHEET_COPY);
    await expect(page.locator('#share-copy')).toContainText(COPY.SHEET_COPY_SUB);
    await expect(sheet).toContainText(COPY.SHEET_FINE);
    await expect(page.locator('#share-close')).toHaveAccessibleName(COPY.SHEET_CLOSE);
    await expect(page.locator('#share-sheet-thumb')).toHaveAttribute('src', FredShare.linkImageUrl(API_ORIGIN, result));
    // No em dash and none of the prohibited terms in anything the feature renders.
    const text = (await sheet.innerText()) + ' ' + (await page.locator('#share-row').innerText());
    expect(text).not.toContain(String.fromCharCode(0x2014));
    for (const bad of [/guaranteed/i, /will retire/i, /you.ll be free/i, /predictions/i]) expect(text).not.toMatch(bad);
  });

  test('opening moves focus into the sheet; Escape closes it and returns focus to the Share button', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await signUp(page);
    await openSheet(page);
    expect(await page.evaluate(() => document.getElementById('share-sheet').contains(document.activeElement))).toBe(true);
    await page.keyboard.press('Escape');
    await expect(page.locator('#share-sheet')).toBeHidden();
    await expect(page.locator('#share-open')).toBeFocused();
  });

  test('the close button and a tap on the scrim close it too, and Tab stays inside it', async ({ page }) => {
    await stubShare(page, 'ok');
    await installMocks(page);
    await page.goto('/index.html');
    await signUp(page);
    await openSheet(page);
    for (let i = 0; i < 8; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => document.getElementById('share-sheet').contains(document.activeElement))).toBe(true);
    }
    await page.click('#share-close');
    await expect(page.locator('#share-sheet')).toBeHidden();
    await expect(page.locator('#share-open')).toBeFocused();
    await openSheet(page);
    await page.mouse.click(10, 10); // the scrim, above the sheet
    await expect(page.locator('#share-sheet')).toBeHidden();
    await expect(page.locator('#share-open')).toBeFocused();
  });
});

test.describe('copy link', () => {
  test('writes a link whose query is exactly r and the three UTM values, and announces "Link copied"', async ({ page }) => {
    await forceAnalytics(page);
    await stubClipboard(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    const result = await resultOnCard(page);
    await signUp(page);
    await openSheet(page);
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
    await expect(page.locator('#share-copy-status')).toHaveAttribute('aria-live', 'polite');
    const copied = await page.evaluate(() => window.__copied);
    expect(copied).toEqual([FredShare.shareUrl(ORIGIN, result)]);
    const url = new URL(copied[0]);
    expect(url.origin + url.pathname).toBe(ORIGIN + SHARE_PATH);
    expect([...url.searchParams.entries()]).toEqual([
      ['r', FredShare.param(result)],
      ['utm_source', 'share'],
      ['utm_medium', 'referral'],
      ['utm_campaign', 'freedom_date'],
    ]);

    // Result Shared: the method and the two bands, nothing that holds the age or the date.
    await expect.poll(async () => named(await events(page), 'Result Shared').length).toBe(1);
    const shared = named(await events(page), 'Result Shared')[0];
    expect(shared.props).toEqual({ method: 'copy', stake_years_band: '11+', stake_source: 'plan' });
    const flat = JSON.stringify(shared.props);
    expect(flat).not.toContain(String(result.age));
    expect(flat).not.toContain(String(result.year));
    expect(flat).not.toContain(result.dateLabel.split(' ')[0]);
  });

  test('falls back to execCommand when the clipboard API is missing, and still announces', async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
      window.__execCopied = [];
      document.execCommand = (cmd) => {
        if (cmd !== 'copy') return false;
        const el = document.activeElement;
        window.__execCopied.push(el && 'value' in el ? el.value : null);
        return true;
      };
    });
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    const result = await resultOnCard(page);
    await signUp(page);
    await openSheet(page);
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
    expect(await page.evaluate(() => window.__execCopied)).toEqual([FredShare.shareUrl(ORIGIN, result)]);
    // The fallback's scratch field is gone and focus is back on the action.
    await expect(page.locator('#share-copy')).toBeFocused();
  });

  test('a second copy of the same result sends nothing; a new result on the card is a new share with its own bands', async ({ page }) => {
    await forceAnalytics(page);
    await stubClipboard(page);
    await installMocks(page);
    await page.goto('/index.html');
    await signUp(page); // defaults: 53, bands 11+ / plan
    await openSheet(page);
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
    await page.click('#share-copy');
    await expect.poll(() => page.evaluate(() => window.__copied.length)).toBe(2);
    await page.waitForTimeout(150);
    expect(named(await events(page), 'Result Shared').map((e) => e.props)).toEqual([
      { method: 'copy', stake_years_band: '11+', stake_source: 'plan' },
    ]);
    await page.keyboard.press('Escape');

    // A later result (65 and later): the same action is a share of something else.
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    const late = await resultOnCard(page);
    expect(late.age).toBeGreaterThanOrEqual(65);
    await openSheet(page);
    await page.click('#share-copy');
    await expect.poll(async () => named(await events(page), 'Result Shared').map((e) => e.props)).toEqual([
      { method: 'copy', stake_years_band: '11+', stake_source: 'plan' },
      { method: 'copy', stake_years_band: 'hidden', stake_source: 'hidden' },
    ]);
    expect((await page.evaluate(() => window.__copied))[2]).toBe(FredShare.shareUrl(ORIGIN, late));
    for (const e of named(await events(page), 'Result Shared')) expect(Object.keys(e.props)).toEqual(['method', 'stake_years_band', 'stake_source']);
  });

  test('when the clipboard and execCommand both fail, the link itself is shown, selected, to copy by hand', async ({ page }) => {
    await forceAnalytics(page);
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
      document.execCommand = () => false;
    });
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    const result = await resultOnCard(page);
    await signUp(page);
    await openSheet(page);
    await expect(page.locator('#share-link-field')).toBeHidden();
    await page.click('#share-copy');
    const field = page.locator('#share-link-field');
    await expect(field).toBeVisible();
    await expect(field).toHaveValue(FredShare.shareUrl(ORIGIN, result));
    await expect(field).toHaveJSProperty('readOnly', true);
    await expect(field).toBeFocused();
    expect(await field.evaluate((el) => el.value.slice(el.selectionStart, el.selectionEnd))).toBe(FredShare.shareUrl(ORIGIN, result));
    // Nothing was copied: no "Link copied", no event; the field sits inside the sheet and the viewport.
    await expect(page.locator('#share-copy-status')).toHaveText('');
    await page.waitForTimeout(150);
    expect(named(await events(page), 'Result Shared')).toEqual([]);
    const box = await page.evaluate(() => {
      const f = document.getElementById('share-link-field').getBoundingClientRect();
      const sh = document.getElementById('share-sheet').getBoundingClientRect();
      return { fTop: f.top, fBottom: f.bottom, fLeft: f.left, fRight: f.right, top: sh.top, bottom: sh.bottom, left: sh.left, right: sh.right, vh: window.innerHeight };
    });
    expect(box.fLeft).toBeGreaterThanOrEqual(box.left);
    expect(box.fRight).toBeLessThanOrEqual(box.right);
    expect(box.fTop).toBeGreaterThanOrEqual(box.top);
    expect(box.fBottom).toBeLessThanOrEqual(Math.min(box.bottom, box.vh));
    // Tab still stays inside the sheet, and reopening starts without the field.
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => document.getElementById('share-sheet').contains(document.activeElement))).toBe(true);
    }
    await page.keyboard.press('Escape');
    await openSheet(page);
    await expect(page.locator('#share-link-field')).toBeHidden();
  });

  test('a 65-and-later result sends hidden bands', async ({ page }) => {
    await forceAnalytics(page);
    await stubClipboard(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await page.click('#reveal-btn');
    const result = await resultOnCard(page);
    expect(result.age).toBeGreaterThanOrEqual(65);
    await signUp(page);
    await openSheet(page);
    await page.click('#share-copy');
    await expect.poll(async () => named(await events(page), 'Result Shared').length).toBe(1);
    expect(named(await events(page), 'Result Shared')[0].props).toEqual({ method: 'copy', stake_years_band: 'hidden', stake_source: 'hidden' });
    expect(await page.evaluate(() => window.__copied)).toEqual([FredShare.shareUrl(ORIGIN, result)]);
  });
});

test.describe('share link (the phone\'s share menu)', () => {
  test('navigator.share is called with the url and nothing else, and Result Shared says native', async ({ page }) => {
    await forceAnalytics(page);
    await stubShare(page, 'ok');
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    const result = await resultOnCard(page);
    await signUp(page);
    await openSheet(page);
    await expect(page.locator('#share-native')).toBeVisible();
    await page.click('#share-native');
    await expect.poll(() => page.evaluate(() => window.__shared.length)).toBe(1);
    expect(await page.evaluate(() => window.__shared)).toEqual([{ url: FredShare.shareUrl(ORIGIN, result) }]);
    await expect.poll(async () => named(await events(page), 'Result Shared').map((e) => e.props.method)).toEqual(['native']);
  });

  test('without navigator.share the action is hidden and Copy link still works', async ({ page }) => {
    await stubShare(page, 'missing');
    await stubClipboard(page);
    await installMocks(page);
    await page.goto('/index.html');
    await signUp(page);
    await openSheet(page);
    await expect(page.locator('#share-native')).toBeHidden();
    await expect(page.locator('#share-story')).toBeVisible();
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
  });

  test('a cancelled share (AbortError) surfaces nothing and fires no event; Copy link still works after it', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    await forceAnalytics(page);
    await stubShare(page, 'abort');
    await stubClipboard(page);
    await installMocks(page);
    await page.goto('/index.html');
    await signUp(page);
    await openSheet(page);
    await page.click('#share-native');
    await expect.poll(() => page.evaluate(() => window.__shared.length)).toBe(1);
    await page.waitForTimeout(200);
    expect(named(await events(page), 'Result Shared')).toEqual([]);
    expect(errors).toEqual([]);
    await expect(page.locator('#share-sheet')).toBeVisible();
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
    await expect.poll(async () => named(await events(page), 'Result Shared').map((e) => e.props.method)).toEqual(['copy']);
  });
});

test.describe('a share menu that fails', () => {
  for (const mode of ['reject', 'throw']) {
    test(`navigator.share that ${mode === 'reject' ? 'rejects with something other than a cancel' : 'throws'} falls back to copying the link`, async ({ page }) => {
      const errors = [];
      page.on('pageerror', (e) => errors.push(String(e)));
      await forceAnalytics(page);
      await stubShare(page, mode);
      await stubClipboard(page);
      await installMocks(page);
      await page.goto('/index.html');
      await page.click('#reveal-btn');
      const result = await resultOnCard(page);
      await signUp(page);
      await openSheet(page);
      await page.click('#share-native');
      // The lead button is not a dead end: the visitor gets the existing "Link copied" confirmation.
      await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
      expect(await page.evaluate(() => window.__copied)).toEqual([FredShare.shareUrl(ORIGIN, result)]);
      // What happened is a copy, not a native share.
      await expect.poll(async () => named(await events(page), 'Result Shared').map((e) => e.props.method)).toEqual(['copy']);
      expect(errors).toEqual([]);
    });
  }
});

test.describe('the story image, when it does not arrive', () => {
  test('a failed image closes the view back to the sheet, sends no event, and the next tap tries again', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    await forceAnalytics(page);
    await installMocks(page);
    let storyRequests = 0;
    let fail = true;
    await page.route('**/api/og/v1/story/**', (route) => {
      storyRequests += 1;
      if (fail) return route.fulfill({ status: 500, contentType: 'text/plain', body: 'no' });
      return route.fallback();
    });
    await page.goto('/index.html');
    await signUp(page);
    await openSheet(page);
    await page.click('#share-story');
    await expect.poll(() => storyRequests).toBe(1);
    // No broken image under "Press and hold the image to save it.": the view is gone, the sheet is there.
    await expect(page.locator('#story-view')).toBeHidden();
    await expect(page.locator('#share-sheet')).toBeVisible();
    await expect(page.locator('#share-story')).toBeFocused();
    await page.waitForTimeout(150);
    expect(named(await events(page), 'Result Shared')).toEqual([]);

    // The API is back: the same tap requests the image again, shows it, and only then is it a share.
    fail = false;
    await page.click('#share-story');
    await expect.poll(() => storyRequests).toBe(2);
    await expect(page.locator('#story-view')).toBeVisible();
    await expect(page.locator('#story-img')).toBeVisible();
    await expect.poll(async () => named(await events(page), 'Result Shared').map((e) => e.props.method)).toEqual(['story']);
    expect(errors).toEqual([]);
  });

  test('the event waits for the image: nothing is sent while it is still loading', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    let release;
    const held = new Promise((resolve) => { release = resolve; });
    await page.route('**/api/og/v1/story/**', async (route) => { await held; return route.fallback(); });
    await page.goto('/index.html');
    await signUp(page);
    await openSheet(page);
    await page.click('#share-story');
    await expect(page.locator('#story-view')).toBeVisible();
    await page.waitForTimeout(300);
    expect(named(await events(page), 'Result Shared')).toEqual([]);
    release();
    await expect.poll(async () => named(await events(page), 'Result Shared').map((e) => e.props.method)).toEqual(['story']);
  });
});

test.describe('the story image', () => {
  test('the action shows the story view with the image from the API origin and the hint; Escape steps back to the sheet', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    const result = await resultOnCard(page);
    await signUp(page);
    await openSheet(page);
    await page.click('#share-story');
    await expect(page.locator('#story-view')).toBeVisible();
    const expected = FredShare.storyImageUrl(API_ORIGIN, result);
    await expect(page.locator('#story-img')).toHaveAttribute('src', expected);
    await expect(page.locator('#story-img')).toBeVisible();
    await expect(page.locator('#story-view')).toContainText(COPY.STORY_HINT);
    await expect(page.locator('#story-close')).toHaveAccessibleName(COPY.SHEET_CLOSE);
    await expect.poll(() => calls.images.includes(expected)).toBe(true);
    // The image is scaled to fit the viewport.
    const fit = await page.evaluate(() => {
      const b = document.getElementById('story-img').getBoundingClientRect();
      return { top: b.top, bottom: b.bottom, left: b.left, right: b.right, vh: window.innerHeight, vw: window.innerWidth };
    });
    expect(fit.top).toBeGreaterThanOrEqual(0);
    expect(fit.bottom).toBeLessThanOrEqual(fit.vh);
    expect(fit.left).toBeGreaterThanOrEqual(0);
    expect(fit.right).toBeLessThanOrEqual(fit.vw);
    await expect.poll(async () => named(await events(page), 'Result Shared').map((e) => e.props)).toEqual([{ method: 'story', stake_years_band: '11+', stake_source: 'plan' }]);

    await page.keyboard.press('Escape');
    await expect(page.locator('#story-view')).toBeHidden();
    await expect(page.locator('#share-sheet')).toBeVisible();
    await expect(page.locator('#share-story')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.locator('#share-sheet')).toBeHidden();
    await expect(page.locator('#share-open')).toBeFocused();
  });
});

test.describe('a return visit', () => {
  test('with a saved signup and the sliders at their defaults, the copied link carries the saved age and date', async ({ page }) => {
    await forceAnalytics(page);
    await stubClipboard(page);
    await seedStorage(page, { waitlist_status: 'WAITLISTED', waitlist_freedom: JSON.stringify({ age: 45, monthYear: 'March 2040' }) });
    await installMocks(page);
    await page.goto('/index.html');
    await expect(page.locator('#success-block')).toBeVisible();
    // The card shows the default sliders' result, which is not the saved one.
    await expect(page.locator('#compare-right-age')).not.toHaveText('45');
    await expect(page.locator('#share-row')).toBeVisible();
    await openSheet(page);
    await expect(page.locator('#share-sheet')).toContainText('March 2040, age 45');
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
    expect(await page.evaluate(() => window.__copied)).toEqual([
      ORIGIN + '/s?r=45-2040-03&utm_source=share&utm_medium=referral&utm_campaign=freedom_date',
    ]);
    // The bands are the shared age's (65 - 45 = 20), not the live render's, and the open was no reveal.
    await expect.poll(() => eventNames(page)).toEqual(['Result Shared']);
    expect((await events(page))[0].props).toEqual({ method: 'copy', stake_years_band: '11+', stake_source: 'plan' });
  });

  test('a reveal click on the untouched default sliders does not replace the saved result or what is shared', async ({ page }) => {
    await stubClipboard(page);
    await seedStorage(page, { waitlist_status: 'WAITLISTED', waitlist_freedom: JSON.stringify({ age: 45, monthYear: 'March 2040' }) });
    await installMocks(page);
    await page.goto('/index.html');
    await expect(page.locator('#share-row')).toBeVisible();
    // The card shows the defaults' result, one this visitor never computed.
    await page.click('#reveal-btn');
    await expect(page.locator('#compare-right-age')).not.toHaveText('45');
    expect(JSON.parse(await storage(page, 'waitlist_freedom'))).toEqual({ age: 45, monthYear: 'March 2040' });
    await expect(page.locator('#share-row')).toBeVisible();
    await openSheet(page);
    await expect(page.locator('#share-sheet')).toContainText('March 2040, age 45');
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
    expect(await page.evaluate(() => window.__copied)).toEqual([
      ORIGIN + '/s?r=45-2040-03&utm_source=share&utm_medium=referral&utm_campaign=freedom_date',
    ]);
    await page.keyboard.press('Escape');

    // Once they move a slider the result is theirs: saved, and shared.
    await page.locator('#invest-range').evaluate((el) => { el.value = el.max; el.dispatchEvent(new Event('input', { bubbles: true })); });
    const result = await resultOnCard(page);
    await page.click('#reveal-btn');
    expect(JSON.parse(await storage(page, 'waitlist_freedom'))).toEqual({ age: result.age, monthYear: result.dateLabel });
    await openSheet(page);
    await expect(page.locator('#share-sheet')).toContainText(`${result.dateLabel}, age ${result.age}`);
  });

  const UNUSABLE = [
    { name: 'no saved freedom date', value: null },
    { name: 'a date that has passed', value: JSON.stringify({ age: 45, monthYear: 'March 2020' }) },
    { name: 'a malformed month', value: JSON.stringify({ age: 45, monthYear: 'Julember 2040' }) },
    { name: 'an age the calculator cannot produce for that date', value: JSON.stringify({ age: 90, monthYear: 'January 2030' }) },
    { name: 'a value that is not JSON', value: '{age:45' },
    { name: 'an at-target style null', value: JSON.stringify({ age: null, monthYear: null }) },
  ];
  for (const saved of UNUSABLE) {
    test(`with ${saved.name} the share row stays hidden`, async ({ page }) => {
      const entries = { waitlist_status: 'WAITLISTED' };
      if (saved.value !== null) entries.waitlist_freedom = saved.value;
      await seedStorage(page, entries);
      await installMocks(page);
      await page.goto('/index.html');
      await expect(page.locator('#success-block')).toBeVisible();
      await expect(page.locator('#share-row')).toBeHidden();
    });
  }

  test('a joined visitor who recalculates shares the new result; sliding to a no-date state hides the row', async ({ page }) => {
    await stubClipboard(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.evaluate(() => {
      localStorage.setItem('waitlist_status', 'WAITLISTED');
      localStorage.setItem('waitlist_freedom', JSON.stringify({ age: 45, monthYear: 'March 2040' }));
    });
    await page.goto('/index.html');
    await expect(page.locator('#share-row')).toBeVisible();
    await page.locator('#invest-range').evaluate((el) => { el.value = el.max; el.dispatchEvent(new Event('input', { bubbles: true })); });
    const result = await resultOnCard(page);
    await openSheet(page);
    await page.click('#share-copy');
    await expect(page.locator('#share-copy-status')).toHaveText(COPY.SHEET_COPIED);
    expect(await page.evaluate(() => window.__copied)).toEqual([FredShare.shareUrl(ORIGIN, result)]);
    await page.keyboard.press('Escape');
    await page.locator('#invest-range').evaluate((el) => { el.value = '0'; el.dispatchEvent(new Event('input', { bubbles: true })); });
    await expect(page.locator('#share-row')).toBeHidden();
  });
});

test.describe('the recipient', () => {
  const LINK = SHARE_PATH + '?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date';

  test('r is gone from the address before the Plausible pageview, the UTM values stay, and the calculator is at its defaults', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page);
    await page.goto(LINK);
    const seenByPlausible = new URL(await pageviewUrl(page));
    expect(seenByPlausible.search).not.toContain('r=');
    expect(seenByPlausible.searchParams.get('r')).toBeNull();
    expect(seenByPlausible.pathname).toBe('/');
    expect(seenByPlausible.search).toBe('?utm_source=share&utm_medium=referral&utm_campaign=freedom_date');
    expect(page.url()).toBe(ORIGIN + '/?utm_source=share&utm_medium=referral&utm_campaign=freedom_date');

    // The ordinary calculator: defaults, closed zone, nothing of the sharer's result on the page.
    await expect(page.locator('#age-range')).toHaveValue('30');
    await expect(page.locator('#invest-range')).toHaveValue('2000');
    await expect(page.locator('#retire-range')).toHaveValue('7500');
    await expect(page.locator('#existing-range')).toHaveValue('0');
    await expect(page.locator('#reveal-zone')).not.toHaveClass(/open/);
    expect(await page.locator('body').innerText()).not.toContain('2048');
    expect(await shareElementCount(page)).toBe(0);

    // The page works from there (styles and scripts resolved), and its events never carry r.
    await page.click('#reveal-btn');
    await expect(page.locator('#capture-email')).toBeVisible();
    for (const e of await events(page)) expect(e.url).not.toContain('r=');
    // The UTM values reach the signup through attribution.js.
    await submitWaitlist(page, 'friend@example.com');
    expect(calls.signup[0].utmSource).toBe('share');
    expect(calls.signup[0].utmMedium).toBe('referral');
    expect(calls.signup[0].utmCampaign).toBe('freedom_date');
  });

  test('an invalid or missing r is stripped the same way and nothing errors', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    await installMocks(page);
    for (const query of ['?r=18-2026-10', '?r=abc&utm_source=share', '']) {
      await page.goto(SHARE_PATH + query);
      expect(new URL(await pageviewUrl(page)).searchParams.get('r')).toBeNull();
      await expect(page.locator('#age-range')).toHaveValue('30');
    }
    expect(errors).toEqual([]);
  });

  test('on the homepage itself the address is left alone', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html?r=52-2048-07&utm_source=x');
    expect(await pageviewUrl(page)).toBe(ORIGIN + '/index.html?r=52-2048-07&utm_source=x');
  });
});

// Sharing is an extra. A failed request for assets/share.js (a dropped connection; no ad-block list
// matches the path) must cost the visitor nothing but the share row: the signup still succeeds
// cleanly, a saved signup still shows, and `r` still leaves the address before the pageview.
test.describe('when assets/share.js does not load', () => {
  const BLOCKS = [
    { name: 'the request is dropped', install: (page) => page.route('**/assets/share.js', (route) => route.abort()) },
    { name: 'the server answers 404', install: (page) => page.route('**/assets/share.js', (route) => route.fulfill({ status: 404, contentType: 'text/plain', body: 'Not Found' })) },
  ];
  for (const block of BLOCKS) {
    test(`${block.name}: a signup ends in the clean success state, with no share row and no failure`, async ({ page }) => {
      const errors = [];
      page.on('pageerror', (e) => errors.push(String(e)));
      await forceAnalytics(page);
      await installMocks(page);
      await block.install(page);
      await page.goto('/index.html');
      await signUp(page, 'noshare@example.com');
      await expect(page.locator('#success-link')).toBeVisible();
      await expect(page.locator('#success-note')).toHaveText(`You${RSQUO}re in line for a Founder invite.`);
      await expect(page.locator('#waitlist-msg')).toBeEmpty();
      await expect(page.locator('#share-row')).toBeHidden();
      expect(await storage(page, 'waitlist_status')).toBe('WAITLISTED');
      const names = await eventNames(page);
      expect(names).toContain('Waitlist Submitted');
      expect(names).not.toContain('Waitlist Failed');
      // Sliding afterwards (the result re-renders for a joined visitor) is as harmless.
      await page.locator('#invest-range').evaluate((el) => { el.value = el.max; el.dispatchEvent(new Event('input', { bubbles: true })); });
      await page.click('#reveal-btn');
      expect(errors).toEqual([]);
    });

    test(`${block.name}: a return visit with a saved signup shows the success state and nothing throws`, async ({ page }) => {
      const errors = [];
      page.on('pageerror', (e) => errors.push(String(e)));
      await seedStorage(page, { waitlist_status: 'WAITLISTED', waitlist_freedom: JSON.stringify({ age: 45, monthYear: 'March 2040' }) });
      await installMocks(page);
      await block.install(page);
      await page.goto('/index.html');
      await expect(page.locator('#success-block')).toBeVisible();
      await expect(page.locator('#share-row')).toBeHidden();
      await page.click('#reveal-btn');
      expect(errors).toEqual([]);
    });

    test(`${block.name}: r is still gone from the address before the Plausible pageview`, async ({ page }) => {
      await forceAnalytics(page);
      await installMocks(page);
      await block.install(page);
      await page.goto(SHARE_PATH + '?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date');
      expect(await pageviewUrl(page)).toBe(ORIGIN + '/?utm_source=share&utm_medium=referral&utm_campaign=freedom_date');
      await page.click('#reveal-btn');
      await expect(page.locator('#capture-email')).toBeVisible();
      for (const e of await events(page)) expect(e.url).not.toContain('r=');
    });
  }
});

test('a signup, the sheet, the story image and a recipient visit stay inside the allowed hosts', async ({ page }) => {
  await forceAnalytics(page);
  await stubClipboard(page);
  await installMocks(page);
  const seen = collectHosts(page);
  await page.goto('/index.html');
  await signUp(page, 'hosts-share@example.com');
  await openSheet(page);
  await page.click('#share-copy');
  await page.click('#share-story');
  await expect(page.locator('#story-img')).toBeVisible();
  await page.waitForLoadState('networkidle');
  await page.goto(SHARE_PATH + '?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date');
  await page.waitForLoadState('networkidle');
  assertOnlyAllowedHosts(seen);
});
