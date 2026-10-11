#!/usr/bin/env python3
"""Align central RELIABILITY iters with board UART events (both epoch-ms stamped).

  correlate.py <central.logcat> <board-uart.log>

For each `arm=.. iter=K result=OK|FAIL .. ms=M t=T` line, show the board events in the iter's
window [T-M-300, T+300] — specifically whether the board logged 'Connected' (link established on
the board side) on that iteration, and any disconnect reason / error. The linchpin question:
on a central FAIL, did the board see the connection at all?
"""
import re, sys

central, board = sys.argv[1], sys.argv[2]

def strip_ansi(s):
    return re.sub(r"\x1b\[[0-9;]*[mGKJ]", "", s).replace("\r", "")

# Parse board events: epoch-ms prefix + text
bev = []
for ln in open(board, errors="replace"):
    ln = strip_ansi(ln).strip()
    m = re.match(r"^(\d{13})\s+(.*)$", ln)
    if not m:
        continue
    t = int(m.group(1)); txt = m.group(2)
    for key in ("Connected", "Disconnected", "Advertising successfully started",
                "Advertising failed", "Connection failed", "FATAL", "assert", "rwble", "FSMERR"):
        if key in txt:
            bev.append((t, txt))
            break

# Parse central iters
rows = []
for ln in open(central, errors="replace"):
    ln = strip_ansi(ln)
    m = re.search(r"arm=(\w+) iter=(\d+) result=(OK|FAIL) detail=(\S+).*?ms=(\d+) t=(\d+)", ln)
    if m:
        rows.append(dict(arm=m.group(1), it=int(m.group(2)), res=m.group(3),
                         detail=m.group(4), ms=int(m.group(5)), t=int(m.group(6))))

def events_in(lo, hi):
    return [f"{txt}" for (t, txt) in bev if lo <= t <= hi]

print(f"central iters={len(rows)} board events={len(bev)}")
ok_conn = fail_conn = fail_noconn = 0
for r in rows:
    lo, hi = r["t"] - r["ms"] - 400, r["t"] + 400
    evs = events_in(lo, hi)
    saw_conn = any("Connected" in e and "Disconnected" not in e for e in evs)
    tag = ("conn" if saw_conn else "NO-conn")
    if r["res"] == "OK":
        ok_conn += saw_conn
    else:
        if saw_conn: fail_conn += 1
        else: fail_noconn += 1
    # compact board summary
    bsum = "; ".join(e[:42] for e in evs) or "(silent)"
    flag = "" if (r["res"] == "OK") == saw_conn else "  <-- MISMATCH"
    print(f"iter {r['it']:>2} {r['res']:>4} {r['detail']:<12} board[{tag}]: {bsum}{flag}")

print()
print(f"OK iters with board 'Connected': {ok_conn}")
print(f"FAIL iters WITH board 'Connected' (link OK, central couldn't use it): {fail_conn}")
print(f"FAIL iters with NO board 'Connected' (establishment never completed): {fail_noconn}")
