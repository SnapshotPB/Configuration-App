#!/usr/bin/env python3
"""Combine the BLE reliability findings of each machine into the master matrix.

  combine-findings.py [--in tools/ble-validation/results] [--out tools/ble-validation/results/matrix.md]

Run it from the repository root. It reads every findings-*.json in the --in directory and writes
the matrix. The matrix has one row for each platform, host and central. The findings schema and
the gate are in tools/ble-validation/README.md ("Findings" and "Combine").
The combiner sets the verdict. The hand-written top-level `verdict` field is only a cross-check.

Mode B (a per-attempt failure at link establishment) does not depend on the central. Thus at the
marginal RF geometry that the method needs, the raw control also fails on some attempts. The gate
compares each production rate with the raw control at the same geometry. It does not ask for zero
failures.

Gate for one row (all must hold for GREEN):
  - the baseline is proven: a raw control has fail > 0,
    or the file has baselineNonZero == true               (else INCONCLUSIVE)
  - the newest `sensitivity` measurement proves that the harness can see a failure: it has
    wedgeObserved == true (a board that wedges), or it is a radio-off run (arm == "radio-off")
    with N >= RADIO_OFF_MIN_N and fail == N (firmware that does not wedge, see below)
  - a `raw-control` measurement exists and did not wedge  (the A/B control is valid)
  - an `A-wedge` measurement has wedgeObserved == false   (else LEAK)
  - a `B-signoff` measurement has N >= signoffN and its rate is not higher than the raw control
    rate (one-sided Fisher exact test, p >= ALPHA)
  - a `user-visible` measurement has N >= signoffN and the same rate test against the raw control
  - if enginePatched then engineDistinctCoordinate        (else AMBER)

Firmware PR #25 fixed the board wedge, so on current firmware no leak arm can wedge the board, and
a wedge can no longer prove the sensitivity. The radio-off arm (DesktopReliabilityRunner, arm=radio-off)
turns the board radio off with `ble off` on the UART console, then makes N clean connects. Each one
must fail. A reached OTS with the radio off is a false OK, for example stale services on a reused
handle, and a harness that reports it cannot see a failure.

For each mode, the gate uses the newest measurement (the latest `date`; with no date, the last one
in the list). Thus a new run, for example on the shipping firmware, replaces an older one.
"""
import argparse
import glob
import json
import math
import os
import sys

SIGNOFF_N = {"ios": 20}  # default 100; iOS runs are slow, so its gate is N >= 20 (see the README)
DEFAULT_SIGNOFF_N = 100
# The smallest radio-off run that proves the sensitivity. Every attempt must fail.
RADIO_OFF_MIN_N = 10
ORDER = ["android", "ios", "desktop-macos", "desktop-windows", "desktop-linux"]
# The one-sided significance level. A production rate fails the gate only when a rate this much
# higher than the control is improbable with a correct engine.
ALPHA = 0.05


class DuplicateRowError(Exception):
    """Two findings files give the same platform, host and central."""


def latest(meas, mode, firmware=None):
    """Return the newest measurement of this mode, or None.

    A measurement with a later ISO 8601 `date` is newer. Measurements with the same date, or with
    no date, are in the order of the list, and the last one is the newest. Old files have no
    `date` field, so their last measurement of the mode is the newest.
    If `firmware` is set, only measurements on that firmware build are candidates.
    """
    found = [(x.get("date") or "", i, x) for i, x in enumerate(meas) if x.get("mode") == mode
             and (firmware is None or x.get("firmware") == firmware)]
    return max(found, key=lambda t: (t[0], t[1]))[2] if found else None


def with_firmware(f):
    """Return the measurements of a file. Each one gets the file firmware build if it has none."""
    default = (f.get("board") or {}).get("firmwareBuild")
    out = []
    for x in f.get("measurements", []):
        x = dict(x)
        if not x.get("firmware"):
            x["firmware"] = default
        out.append(x)
    return out


def counts(x):
    """Return (fail, N) of a measurement, or None if the measurement has no counts."""
    if not x:
        return None
    fail, n = x.get("fail"), x.get("N")
    if not isinstance(fail, int) or not isinstance(n, int) or n <= 0 or fail < 0 or fail > n:
        return None
    return fail, n


def p_higher(fail_a, n_a, fail_b, n_b):
    """One-sided Fisher exact test.

    Returns the probability of `fail_a` or more fails in sample A, if A and B have the same
    failure rate and the total number of fails is fixed. A small value shows that the rate of A is
    higher than the rate of B.
    """
    total_fail = fail_a + fail_b
    total = n_a + n_b
    denominator = math.comb(total, n_a)
    top = min(total_fail, n_a)
    return sum(math.comb(total_fail, k) * math.comb(total - total_fail, n_a - k)
               for k in range(fail_a, top + 1)) / denominator


def control_for(meas, x):
    """Return the raw control for measurement x: the newest one on the same firmware, else the newest."""
    return latest(meas, "raw-control", x.get("firmware")) or latest(meas, "raw-control")


def rate_check(meas, mode, need):
    """Return None if the newest `mode` measurement passes the rate gate, else the reason."""
    x = latest(meas, mode)
    c = counts(x)
    if not c:
        return f"{mode} missing"
    fail, n = c
    if n < need:
        return f"{mode} N={n} < {need}"
    ctrl = counts(control_for(meas, x))
    if not ctrl:
        return f"{mode}: no raw control to compare"
    p = p_higher(fail, n, ctrl[0], ctrl[1])
    if p < ALPHA:
        return f"{mode} {fail}/{n} higher than raw control {ctrl[0]}/{ctrl[1]} (p={p:.3f})"
    return None


def sensitivity_proven(sens):
    """True when a sensitivity measurement shows that the harness can see a failure."""
    if not sens:
        return False
    if sens.get("wedgeObserved") is True:
        return True
    c = counts(sens)
    return sens.get("arm") == "radio-off" and c is not None and c[1] >= RADIO_OFF_MIN_N and c[0] == c[1]


def gate(f):
    meas = with_firmware(f)
    raw = latest(meas, "raw-control")
    raw_counts = counts(raw)
    # One raw control with fails proves that the geometry of this row is marginal.
    baseline = f.get("baselineNonZero") is True or any(
        (counts(x) or (0, 0))[0] > 0 for x in meas if x.get("mode") == "raw-control")
    if not baseline:
        return "INCONCLUSIVE", "baseline never failed; margin unproven"
    if not sensitivity_proven(latest(meas, "sensitivity")):
        return "FAIL", "no proven sensitivity"
    if not raw_counts:
        return "FAIL", "raw control missing"
    if raw.get("wedgeObserved") is True:
        return "FAIL", "raw control wedged; examine the board or the RF"
    wedge = latest(meas, "A-wedge")
    if not (wedge and wedge.get("wedgeObserved") is False):
        if wedge and wedge.get("wedgeObserved"):
            return "LEAK", "Mode A wedge on production engine"
        return "FAIL", "Mode A missing"
    need = SIGNOFF_N.get(f.get("platform"), DEFAULT_SIGNOFF_N)
    for mode in ("B-signoff", "user-visible"):
        why = rate_check(meas, mode, need)
        if why:
            return "FAIL", why
    if f.get("enginePatched") and not f.get("engineDistinctCoordinate"):
        return "AMBER", "patched but version collides with stock"
    return "GREEN", "verified clean"


def cell(meas, mode):
    x = latest(meas, mode)
    c = counts(x)
    if not c:
        return "n/a"
    fail, n = c
    fw = f", {x['firmware']}" if x.get("firmware") else ""
    return f"{100.0 * fail / n:.1f}% ({fail}/{n}{fw})"


def row_key(f):
    """Return the (platform, host, central) identity of a findings file."""
    host = f.get("host") or {}
    central = f.get("central") or {}
    host_id = host.get("machineId") or f"{host.get('os', '?')} {host.get('osVersion', '?')}"
    central_id = central.get("id") or central.get("model") or "?"
    return f.get("platform", "?"), host_id, central_id


def load(indir):
    """Load every findings file. Raise DuplicateRowError if two files have the same row key."""
    files = {}
    for p in sorted(glob.glob(os.path.join(indir, "findings-*.json"))):
        with open(p) as fh:
            f = json.load(fh)
        f["_path"] = os.path.relpath(p)
        key = row_key(f)
        if key in files:
            raise DuplicateRowError(
                f"{files[key]['_path']} and {f['_path']} both give platform={key[0]} host={key[1]} "
                f"central={key[2]}. Put the measurements in one file, or correct the host or central id.")
        files[key] = f
    return files


def sort_key(key):
    plat = key[0]
    return (ORDER.index(plat) if plat in ORDER else len(ORDER), plat, key[1], key[2])


def render(files):
    rows = []
    for key in sorted(files, key=sort_key):
        f = files[key]
        verdict, why = gate(f)
        meas = with_firmware(f)
        w = latest(meas, "A-wedge")
        wcell = "n/a" if not w else (f"WEDGE@{w.get('wedgeAtIter')}" if w.get("wedgeObserved") else "no")
        host = f.get("host") or {}
        central = f.get("central") or {}
        rows.append("| {p} | {h} | {c} | {e} | {pa} | {w} | {rc} | {pb} | {uv} | {v} | {f} |".format(
            p=key[0], h=f"{key[1]} ({host.get('os', '?')} {host.get('osVersion', '?')})",
            c=central.get("model", key[2]), e=f.get("engine", "?").rsplit(".", 1)[-1],
            pa=("yes" if f.get("enginePatched") else "no"), w=wcell, rc=cell(meas, "raw-control"),
            pb=cell(meas, "B-signoff"), uv=cell(meas, "user-visible"), v=f"{verdict} ({why})", f=f["_path"]))
    hdr = ("# BLE platform reliability matrix\n\nGenerated by tools/ble-validation/combine-findings.py. Do not edit by hand.\n"
           "The gate is in tools/ble-validation/README.md (Combine).\n\n"
           "| Platform | Host | Central | Engine | Patched? | Mode A wedge? | Raw control rate | Per-attempt rate "
           "| User-visible | Verdict | Findings file |\n"
           "|---|---|---|---|---|---|---|---|---|---|---|\n")
    return hdr + "\n".join(rows) + "\n", len(rows)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="indir", default="tools/ble-validation/results")
    ap.add_argument("--out", default="tools/ble-validation/results/matrix.md")
    a = ap.parse_args(argv)
    try:
        files = load(a.indir)
    except DuplicateRowError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    text, n = render(files)
    # The results directory is not in git until the first findings file is added.
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w") as fh:
        fh.write(text)
    print("wrote", a.out, f"({n} rows)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
