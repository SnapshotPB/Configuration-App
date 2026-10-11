// Guards the harness against the app growing past it: every page registered in
// app.json must be loadable by the page host, or a new screen would silently go
// untested.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { PAGE_LOADERS } from '../harness';

const manifest = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../app.json', import.meta.url)), 'utf8')
);

describe('app.json', () => {
  test('every registered page has a harness loader', () => {
    const registered = new Set();
    Object.values(manifest.targets).forEach(target => {
      target.module.page.pages.forEach(page => registered.add(page));
    });

    expect([...registered].sort()).toEqual(Object.keys(PAGE_LOADERS).sort());
  });

  test('declares the permissions the harness assumes', () => {
    expect(manifest.permissions).toContain('device:bluetooth');
    expect(manifest.permissions).toContain('device:os.local_storage');
  });
});
