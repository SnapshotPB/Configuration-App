// Vitest runs the app's real source in Node. It resolves the app's
// extensionless relative imports the same way the Zeus bundler does, and the
// aliases below swap the seven @zos modules the app imports for the mock host in
// .test/mocks — no bundler step, no simulator, no Zepp account.
//
// WHY .test/ AND NOT test/: `zeus build` walks the whole app directory, runs
// Rollup over every .js it finds and copies every other file into the packaged
// intermediate products. A plain `test/` therefore both breaks the build (it
// follows the `vitest` import into Vite's dist) and ships the harness inside the
// .zab. Zeus skips dot-directories outright, so the harness lives in .test/ and
// the npm scripts pass --config.
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

const appRoot = fileURLToPath(new URL('..', import.meta.url));

// Keep in step with DEFAULT_TIME_SCALE in .test/mocks/globals.js.
const timeScale = Number(process.env.ZEPP_TEST_TIME_SCALE || 0.05);
const timeout = Math.max(20000, Math.round(20000 * (timeScale / 0.05)));
const mock = name => fileURLToPath(new URL(`./mocks/${name}.js`, import.meta.url));

export default defineConfig({
  root: appRoot,
  resolve: {
    alias: {
      '@zos/ui': mock('zos-ui'),
      '@zos/router': mock('zos-router'),
      '@zos/storage': mock('zos-storage'),
      '@zos/device': mock('zos-device'),
      '@zos/utils': mock('zos-utils'),
      '@zos/ble': mock('zos-ble'),
      '@zos/app': mock('zos-app')
    }
  },
  test: {
    environment: 'node',
    include: ['.test/**/*.test.js'],
    // Flows run on a compressed clock (see .test/mocks/globals.js), so the cap
    // has to track it: at scale 1 a single connect really does take ~8s.
    testTimeout: timeout,
    hookTimeout: timeout
  }
});
