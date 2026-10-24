# Firmware site fixtures

These files are copies of the output of the firmware site generator. Do not edit them by hand.

- `index.json`, `snapshot_v2/index.json` and `snapshot_v2/latest.json` come from
  `scripts/tests/fixtures/pages/expected/` in the `Firmware` repository. `scripts/pages_index.py`
  wrote them, and `scripts/tests/test_pages_index.py` compares its output with them.
- The two `.bin` files are the fake update images of the same fixture tree
  (`scripts/tests/fixtures/pages/downloads/<tag>/`). Their size and SHA-256 are the values in the
  JSON files. `.gitattributes` keeps their bytes as they are.

The tree has the layout of the site, so `FakeFirmwareSite` gives each file at its site URL. When
the generator or its fixtures change, copy the files again.
