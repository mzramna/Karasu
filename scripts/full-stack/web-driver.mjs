#!/usr/bin/env node
// Drives the real WebUI in a real browser, one action per call; prints one line of JSON.
//   node web-driver.mjs <action> [args...]      env: KARASU_URL KARASU_USER KARASU_PASS [CHROMIUM]
//
// Every action is something a person does by clicking: the answer is what the screen shows afterwards, so a
// scenario can compare the WebUI's view with the app's and the server's.
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const BASE = process.env.KARASU_URL;

/** Opens a page and waits until the SPA has settled; the Basic Authentication deprecation notice is dismissed. */
const open = async (page, path) => {
    await page.goto(`${BASE}${path}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(600);
    const notice = page.getByRole('dialog').filter({ hasText: /Basic Authentication deprecation/ });
    if (await notice.count()) {
        await notice.getByRole('button', { name: /cancel/i }).click();
        await page.waitForTimeout(300);
    }
};

const bool = (v) => String(v) === 'true';

/** Waits until a list that fills in as data arrives has stopped growing. */
const settled = async (locator, page) => {
    let last = -1;
    for (let i = 0; i < 20; i++) {
        const now = await locator.count();
        if (now === last && now > 0) return now;
        last = now;
        await page.waitForTimeout(400);
    }
    return last;
};

/** The row of the manga page that holds the chapter with this name. */
const chapterRow = (page, name) =>
    page.getByText(name, { exact: true }).first().locator('xpath=ancestor::*[.//button[@aria-label="more"]][1]');

const chapterMenu = async (page, name) => {
    await chapterRow(page, name).locator('button[aria-label="more"]').click();
    await page.waitForTimeout(300);
    return page.getByRole('menuitem');
};

/** The names shown on the category settings page; each row has an "Edit" and a "Delete" control next to its <h2>. */
const categoryNames = async (page) =>
    page.locator('[aria-label="Edit"]').evaluateAll((controls) =>
        controls.map((c) => (c.parentElement?.parentElement?.querySelector('h2')?.innerText || '').trim()),
    );

const actions = {
    async ping({ page }) {
        await open(page, '/settings');
        return { ok: true, title: await page.title() };
    },

    // ---------------------------------------------------------------- categories
    async categories({ page }) {
        await open(page, '/settings/categories');
        return { names: await categoryNames(page) };
    },

    async 'create-category'({ page, args: [name] }) {
        await open(page, '/settings/categories');
        await page.getByRole('button', { name: 'add' }).click();
        await page.getByRole('dialog').getByRole('textbox').first().fill(name);
        await page.getByRole('dialog').getByRole('button', { name: /submit/i }).click();
        await page.waitForTimeout(800);
        return { names: await categoryNames(page) };
    },

    async 'rename-category'({ page, args: [from, to] }) {
        await open(page, '/settings/categories');
        const names = await categoryNames(page);
        const index = names.indexOf(from);
        if (index < 0) throw new Error(`no category named ${from}: ${names.join(', ')}`);
        await page.locator('[aria-label="Edit"]').nth(index).click();
        const input = page.getByRole('dialog').getByRole('textbox').first();
        await input.fill(to);
        await page.getByRole('dialog').getByRole('button', { name: /submit/i }).click();
        await page.waitForTimeout(800);
        return { names: await categoryNames(page) };
    },

    async 'delete-category'({ page, args: [name] }) {
        await open(page, '/settings/categories');
        const index = (await categoryNames(page)).indexOf(name);
        if (index < 0) throw new Error(`no category named ${name}`);
        await page.locator('[aria-label="Delete"]').nth(index).click();
        await page.getByRole('dialog').getByRole('button', { name: /^(ok|delete|confirm|yes)/i }).last().click();
        await page.waitForTimeout(800);
        return { names: await categoryNames(page) };
    },

    /** The categories of a manga as the "Edit manga categories" dialog shows them. */
    async 'manga-categories'({ page, args: [mangaId] }) {
        await open(page, `/manga/${mangaId}`);
        await page.getByRole('button', { name: /edit manga categories/i }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByRole('checkbox').first().waitFor();
        await settled(dialog.getByRole('checkbox'), page);
        const boxes = await dialog.getByRole('checkbox').evaluateAll((els) =>
            els.map((e) => ({ name: (e.closest('label')?.innerText || '').trim(), checked: e.checked })),
        );
        await dialog.getByRole('button', { name: /cancel/i }).click();
        return { categories: boxes.filter((b) => b.checked).map((b) => b.name), all: boxes.map((b) => b.name) };
    },

    async 'set-manga-categories'({ page, args: [mangaId, csv] }) {
        const wanted = csv ? csv.split(',') : [];
        await open(page, `/manga/${mangaId}`);
        await page.getByRole('button', { name: /edit manga categories/i }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByRole('checkbox').first().waitFor();
        const count = await settled(dialog.getByRole('checkbox'), page);
        for (let i = 0; i < count; i++) {
            const box = dialog.getByRole('checkbox').nth(i);
            const label = ((await box.locator('xpath=ancestor::label[1]').innerText()) || '').trim();
            if ((await box.isChecked()) !== wanted.includes(label)) await box.click();
        }
        await dialog.getByRole('button', { name: /^(ok|submit|save|change)/i }).click();
        await page.waitForTimeout(800);
        return { ok: true };
    },

    // ---------------------------------------------------------------- chapters
    /** Whether the WebUI shows the chapter as read (its menu offers "Mark as unread") and as downloaded. */
    async chapter({ page, args: [mangaId, name] }) {
        await open(page, `/manga/${mangaId}`);
        const items = await (await chapterMenu(page, name)).allInnerTexts();
        await page.keyboard.press('Escape');
        return { read: items.some((t) => /mark as unread/i.test(t)), items, canDelete: items.some((t) => /^delete$/i.test(t.trim())) };
    },

    async mark({ page, args: [mangaId, name, state] }) {
        await open(page, `/manga/${mangaId}`);
        const menu = await chapterMenu(page, name);
        await menu.filter({ hasText: state === 'unread' ? /mark as unread/i : /^mark as read$/i }).first().click();
        await page.waitForTimeout(800);
        return { ok: true };
    },

    async 'delete-chapter'({ page, args: [mangaId, name, withKarasu] }) {
        await open(page, `/manga/${mangaId}`);
        const menu = await chapterMenu(page, name);
        await menu.filter({ hasText: /^delete$/i }).first().click();
        const dialog = page.getByRole('dialog');
        const box = dialog.getByRole('checkbox');
        if (await box.count()) {
            if ((await box.first().isChecked()) !== bool(withKarasu)) await box.first().click();
        }
        await dialog.getByRole('button', { name: /^ok$/i }).click();
        await page.waitForTimeout(1500);
        return { ok: true };
    },

    // ---------------------------------------------------------------- history
    /** The history page: one entry per line, "<manga> | <chapter> - <time>", under day headings. */
    async history({ page }) {
        await open(page, '/history');
        await page.waitForTimeout(800);
        const text = await page.locator('main').first().innerText().catch(() => page.locator('body').innerText());
        const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
        const entries = [];
        for (let i = 0; i < lines.length - 1; i++) {
            if (/^Chapter\b/i.test(lines[i + 1]) || /—/.test(lines[i + 1])) entries.push({ manga: lines[i], chapter: lines[i + 1] });
        }
        return { lines, entries };
    },

    // ---------------------------------------------------------------- merged sources
    async merged({ page, args: [mangaId] }) {
        await open(page, `/manga/${mangaId}`);
        await page.getByText(/merged sources/i).first().click();
        await page.waitForTimeout(600);
        const rows = await page.getByText(/(Unknown source \(\d+\)|Source \d+)/).allInnerTexts();
        return { sources: rows.map((t) => (t.match(/\d+/) || [''])[0]) };
    },

    async 'merged-reserve'({ page, args: [mangaId, index] }) {
        await open(page, `/manga/${mangaId}`);
        await page.getByText(/merged sources/i).first().click();
        await page.waitForTimeout(600);
        await page.getByText('Backup', { exact: true }).nth(Number(index)).click();
        await page.waitForTimeout(1000);
        return { ok: true };
    },

    // ---------------------------------------------------------------- backups and policy
    async backups({ page }) {
        await open(page, '/settings/karasu');
        await page.waitForTimeout(800);
        const text = await page.locator('main').first().innerText().catch(() => page.locator('body').innerText());
        const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
        return { lines, names: lines.filter((l) => /\.tachibk$/i.test(l)) };
    },

    async 'delete-backup'({ page, args: [name] }) {
        await open(page, '/settings/karasu');
        // the row holds two buttons: download, then delete
        const row = page.getByText(name, { exact: true }).first().locator('xpath=ancestor::*[count(.//button)>=2][1]');
        await row.locator('button').last().click();
        await page.getByRole('dialog').getByRole('button', { name: /^(delete|ok|confirm)/i }).last().click();
        await page.waitForTimeout(800);
        return { ok: true };
    },

    async 'policy-get'({ page }) {
        await open(page, '/settings/download');
        await page.waitForTimeout(800);
        const text = await page.locator('body').innerText();
        return { serverAndClients: /Server and Karasu devices/.test(text.split('Download policy')[1] || ''), text: (text.split('Download policy')[1] || '').slice(0, 80) };
    },

    async 'policy-set'({ page, args: [value] }) {
        await open(page, '/settings/download');
        await page.getByText('Download policy').first().click();
        const dialog = page.getByRole('dialog');
        await dialog.getByRole('combobox').click();
        await page.getByRole('option', { name: value === 'SERVER_AND_CLIENTS' ? /Server and Karasu devices/i : /Server only/i }).click();
        await dialog.getByRole('button', { name: /^ok$/i }).click();
        await page.waitForTimeout(800);
        return { ok: true };
    },
};

const [action, ...args] = process.argv.slice(2);
const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
try {
    const context = await browser.newContext({
        viewport: { width: 1280, height: 1000 },
        httpCredentials: { username: process.env.KARASU_USER, password: process.env.KARASU_PASS },
    });
    const page = await context.newPage();
    if (!actions[action]) throw new Error(`unknown action: ${action}`);
    const result = await actions[action]({ page, context, args });
    console.log(JSON.stringify(result ?? { ok: true }));
} catch (e) {
    console.error(String(e?.message ?? e));
    process.exitCode = 1;
} finally {
    await browser.close();
}
