#!/usr/bin/env python3
"""Unit tests for tools/ble-validation/combine-findings.py.

Run with: python3 -m unittest discover -s tools/ble-validation/tests

The tests use only the local file system, so they run on every host.
"""

import contextlib
import copy
import io
import importlib.util
import json
import os
import tempfile
import unittest

_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_PATH = os.path.join(_DIR, "combine-findings.py")
_SPEC = importlib.util.spec_from_file_location("combine_findings", _PATH)
cf = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(cf)


def meas(mode, fail=None, n=None, **extra):
    x = {"mode": mode, "arm": "prod", "N": n, "fail": fail, "ok": None if n is None else n - fail,
         "ratePct": None if n is None else 100.0 * fail / n, "wedgeObserved": None}
    x.update(extra)
    return x


# A correct engine at a marginal RF range: the raw control fails on 25 % of the attempts, and the
# production engine has the same Mode B rate. The old gate made this row FAIL.
MARGINAL = {
    "schemaVersion": 3,
    "platform": "android",
    "engine": "dev.bluefalcon.engine.android.AndroidEngine",
    "enginePatched": False,
    "engineDistinctCoordinate": True,
    "host": {"os": "macOS", "osVersion": "15.5", "machineId": "mac-host-01"},
    "central": {"model": "Fire Max 11", "id": "SERIAL1"},
    "board": {"firmwareBuild": "diag"},
    "measurements": [
        meas("sensitivity", N=24, wedgeObserved=True),
        meas("raw-control", 25, 100, arm="raw", wedgeObserved=False),
        meas("A-wedge", 24, 100, wedgeObserved=False),
        meas("B-signoff", 27, 100),
        meas("user-visible", 3, 100),
    ],
}


def marginal(**changes):
    f = copy.deepcopy(MARGINAL)
    f.update(changes)
    return f


def replace(f, mode, **fields):
    for x in f["measurements"]:
        if x["mode"] == mode:
            x.update(fields)
    return f


class GateTests(unittest.TestCase):
    def test_correct_engine_at_marginal_range_is_green(self):
        self.assertEqual(cf.gate(marginal()), ("GREEN", "verified clean"))

    def test_raw_control_fails_prove_the_baseline(self):
        f = marginal()
        self.assertNotIn("baselineNonZero", f)
        self.assertEqual(cf.gate(f)[0], "GREEN")

    def test_zero_baseline_is_inconclusive(self):
        f = replace(marginal(), "raw-control", fail=0, ok=100)
        self.assertEqual(cf.gate(f)[0], "INCONCLUSIVE")

    def test_legacy_baseline_flag_still_counts(self):
        f = replace(marginal(baselineNonZero=True), "raw-control", fail=0, ok=100)
        replace(f, "B-signoff", fail=0)
        replace(f, "user-visible", fail=0)
        self.assertEqual(cf.gate(f)[0], "GREEN")

    def test_production_rate_much_higher_than_control_fails(self):
        f = replace(marginal(), "B-signoff", fail=45)
        verdict, why = cf.gate(f)
        self.assertEqual(verdict, "FAIL")
        self.assertIn("B-signoff 45/100 higher than raw control 25/100", why)

    def test_user_visible_rate_much_higher_than_control_fails(self):
        f = replace(marginal(), "user-visible", fail=60)
        verdict, why = cf.gate(f)
        self.assertEqual(verdict, "FAIL")
        self.assertIn("user-visible", why)

    def test_signoff_needs_enough_attempts(self):
        f = replace(marginal(), "B-signoff", fail=0, N=50)
        self.assertEqual(cf.gate(f), ("FAIL", "B-signoff N=50 < 100"))

    def test_ios_signoff_needs_twenty(self):
        f = marginal(platform="ios")
        replace(f, "raw-control", fail=5, N=20)
        replace(f, "B-signoff", fail=4, N=20)
        replace(f, "user-visible", fail=0, N=20)
        self.assertEqual(cf.gate(f)[0], "GREEN")

    def test_wedged_control_fails(self):
        f = replace(marginal(), "raw-control", wedgeObserved=True)
        self.assertEqual(cf.gate(f)[0], "FAIL")
        self.assertIn("raw control wedged", cf.gate(f)[1])

    def test_missing_control_with_legacy_baseline_fails(self):
        f = marginal(baselineNonZero=True)
        f["measurements"] = [x for x in f["measurements"] if x["mode"] != "raw-control"]
        self.assertEqual(cf.gate(f), ("FAIL", "raw control missing"))

    def test_mode_a_wedge_is_leak(self):
        f = replace(marginal(), "A-wedge", wedgeObserved=True, wedgeAtIter=27)
        self.assertEqual(cf.gate(f)[0], "LEAK")

    def test_no_sensitivity_fails(self):
        f = replace(marginal(), "sensitivity", wedgeObserved=False)
        self.assertEqual(cf.gate(f), ("FAIL", "no proven sensitivity"))

    def test_radio_off_run_with_every_attempt_failed_proves_the_sensitivity(self):
        # Firmware that does not wedge: the radio-off arm replaces the wedge as the proof.
        f = replace(marginal(), "sensitivity", arm="radio-off", N=10, fail=10, ok=0, wedgeObserved=False)
        self.assertEqual(cf.gate(f), ("GREEN", "verified clean"))

    def test_radio_off_run_with_a_false_ok_fails(self):
        f = replace(marginal(), "sensitivity", arm="radio-off", N=10, fail=9, ok=1, wedgeObserved=False)
        self.assertEqual(cf.gate(f), ("FAIL", "no proven sensitivity"))

    def test_radio_off_run_that_is_too_short_fails(self):
        f = replace(marginal(), "sensitivity", arm="radio-off", N=5, fail=5, ok=0, wedgeObserved=False)
        self.assertEqual(cf.gate(f), ("FAIL", "no proven sensitivity"))

    def test_all_failed_leak_arm_is_not_a_radio_off_proof(self):
        f = replace(marginal(), "sensitivity", arm="leak", N=10, fail=10, ok=0, wedgeObserved=False)
        self.assertEqual(cf.gate(f), ("FAIL", "no proven sensitivity"))

    def test_patched_engine_with_stock_version_is_amber(self):
        f = marginal(enginePatched=True, engineDistinctCoordinate=False)
        self.assertEqual(cf.gate(f)[0], "AMBER")


class NewestMeasurementTests(unittest.TestCase):
    def test_later_entry_without_date_replaces_earlier(self):
        f = marginal()
        f["measurements"].append(meas("B-signoff", 60, 100))
        self.assertEqual(cf.gate(f)[0], "FAIL")

    def test_later_date_wins_over_list_order(self):
        f = marginal()
        replace(f, "B-signoff", date="2026-07-02")
        f["measurements"].append(meas("B-signoff", 60, 100, date="2026-07-01"))
        self.assertEqual(cf.gate(f)[0], "GREEN")

    def test_shipping_rerun_takes_effect(self):
        f = replace(marginal(), "B-signoff", fail=60, date="2026-06-27")
        self.assertEqual(cf.gate(f)[0], "FAIL")
        f["measurements"].append(meas("B-signoff", 20, 100, date="2026-07-10", firmware="ship"))
        self.assertEqual(cf.gate(f)[0], "GREEN")
        self.assertEqual(cf.cell(cf.with_firmware(f), "B-signoff"), "20.0% (20/100, ship)")

    def test_measurement_without_firmware_takes_the_board_build(self):
        self.assertTrue(all(x["firmware"] == "diag" for x in cf.with_firmware(marginal())))

    def test_rate_uses_control_on_same_firmware(self):
        f = marginal()
        f["measurements"].append(meas("raw-control", 0, 100, arm="raw", firmware="ship", date="2026-07-10"))
        f["measurements"].append(meas("B-signoff", 20, 100, firmware="ship", date="2026-07-11"))
        # The shipping sign-off (20/100) is compared with the shipping control (0/100).
        self.assertEqual(cf.gate(f)[0], "FAIL")


class FisherTests(unittest.TestCase):
    def test_equal_rates_are_not_significant(self):
        self.assertGreater(cf.p_higher(25, 100, 25, 100), 0.5)

    def test_zero_fails_is_one(self):
        self.assertAlmostEqual(cf.p_higher(0, 100, 25, 100), 1.0)

    def test_known_value(self):
        # 3/10 against 0/10: C(3,3)*C(17,7)/C(20,10) = 19448/184756.
        self.assertAlmostEqual(cf.p_higher(3, 10, 0, 10), 19448 / 184756)


class LoadAndRenderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def write(self, name, f):
        with open(os.path.join(self.tmp.name, name), "w") as fh:
            json.dump(f, fh)

    def test_two_hosts_of_one_platform_give_two_rows(self):
        self.write("findings-android-a.json", marginal())
        other = marginal(host={"os": "Linux", "osVersion": "6.8", "machineId": "linux-01"})
        self.write("findings-android-b.json", other)
        files = cf.load(self.tmp.name)
        self.assertEqual(len(files), 2)
        text, n = cf.render(files)
        self.assertEqual(n, 2)
        self.assertIn("mac-host-01", text)
        self.assertIn("linux-01", text)

    def test_same_platform_host_and_central_is_an_error(self):
        self.write("findings-android-a.json", marginal())
        self.write("findings-android-b.json", marginal())
        with self.assertRaises(cf.DuplicateRowError):
            cf.load(self.tmp.name)
        out = os.path.join(self.tmp.name, "matrix.md")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(cf.main(["--in", self.tmp.name, "--out", out]), 2)
        self.assertIn("both give platform=android", err.getvalue())
        self.assertFalse(os.path.exists(out))

    def test_schema_2_file_loads(self):
        # The README example of schema 2: no host key of the row, no date, no firmware fields.
        old = marginal(schemaVersion=2)
        del old["board"]
        old["host"] = {"os": "macOS", "osVersion": "15.5"}
        self.write("findings-android-old.json", old)
        out = os.path.join(self.tmp.name, "matrix.md")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(cf.main(["--in", self.tmp.name, "--out", out]), 0)
        with open(out) as fh:
            text = fh.read()
        self.assertIn("| android | macOS 15.5 (macOS 15.5) |", text)
        self.assertIn("GREEN", text)
        self.assertIn("25.0% (25/100) |", text)


class ReadmeExampleTests(unittest.TestCase):
    def test_readme_example_is_green(self):
        with open(os.path.join(_DIR, "README.md")) as fh:
            text = fh.read()
        start = text.index("```json\n") + len("```json\n")
        example = json.loads(text[start:text.index("```", start)])
        self.assertEqual(example["schemaVersion"], 3)
        self.assertEqual(cf.gate(example), ("GREEN", "verified clean"))


if __name__ == "__main__":
    unittest.main()
