// The Kotlin Gradle plugin adds each file of this directory to the Karma configuration of the
// browser tests of this module.
//
// Mocha stops a test after 2 seconds by default. WebPhotoTest makes and decodes images in a real
// browser. On a CI runner, the browser tests of other modules run at the same time, and such a
// test can be slower than 2 seconds. The failure then shows only as "Error", with no message.
// The limit of `runTest` (60 seconds) stays in effect.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 30000;
