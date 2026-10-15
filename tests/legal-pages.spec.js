// /privacy and /terms carry counsel's final Privacy Policy and Terms of
// Service (rendered from legal/*.html). Each page: 200 at its clean URL,
// exactly one h1 with the document's title, the expected first section
// heading, requests only to our own origin (the Plausible proxy is served
// from it), and no horizontal overflow at a 375px viewport (the policy's data
// table stacks into cards below md; long URLs wrap), and no custom analytics event.
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, collectHosts, events } = require('./helpers');

const OWN_HOSTS = new Set(['localhost', '127.0.0.1']);

const PAGES = [
  { path: '/privacy', h1: 'FRED Privacy Policy', firstH2: '1. Introduction' },
  { path: '/terms', h1: 'Terms of Use', firstH2: '1. License.' },
];

for (const { path, h1, firstH2 } of PAGES) {
  test(`${path} renders counsel's text, stays on our origin and fits a 375px viewport`, async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    const seen = collectHosts(page);
    await page.setViewportSize({ width: 375, height: 812 });

    const response = await page.goto(path);
    expect(response.status()).toBe(200);
    await page.waitForLoadState('networkidle');

    const heading = page.locator('h1');
    await expect(heading).toHaveCount(1);
    await expect(heading).toHaveText(h1);
    await expect(page.locator('h2').first()).toHaveText(firstH2);

    // Scroll through so anything lazy would load before the host check.
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(300);
    const offenders = [...seen.keys()].filter((host) => !OWN_HOSTS.has(host));
    expect(offenders, `requests left our origin: ${offenders.join(', ')}`).toEqual([]);
    expect(seen.get('localhost') || seen.get('127.0.0.1')).toBeGreaterThan(0);
    // No custom analytics events on the legal pages (the recorder is installed above).
    expect(await events(page)).toEqual([]);

    const overflow = await page.evaluate(() => {
      const width = window.innerWidth;
      const wide = [...document.querySelectorAll('body *')]
        .filter((el) => el.getBoundingClientRect().right > width + 1)
        .map((el) => el.tagName.toLowerCase() + (el.id ? '#' + el.id : ''));
      return { scrollWidth: document.documentElement.scrollWidth, width, wide };
    });
    expect(overflow.scrollWidth, 'page scrolls horizontally').toBeLessThanOrEqual(overflow.width);
    expect(overflow.wide, 'elements wider than the viewport').toEqual([]);
  });
}
