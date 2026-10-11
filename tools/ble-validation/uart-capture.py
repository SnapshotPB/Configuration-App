#!/usr/bin/env python3
"""Single-process epoch-timestamped ESP32-C3 UART capture for connect-churn correlation.

Resets the board once on open (RTS->EN pulse) so the capture starts from a clean boot, then
streams every line prefixed with epoch-ms (to align with the RELIABILITY logcat `t=` field).
One process, line-buffered — unlike a per-line shell fork, it keeps up with 115200 baud.

  uart-capture.py [port] [baud] [--ble-on] > board-uart.log

--ble-on: since firmware 845c20e the BLE radio is off at every boot. With this flag, the script
sends `ble on` on the console 3 s after the reset, through the port that it holds for the batch.
It writes BLE_ON_OK to stderr when the board reports that it advertises.
"""
import sys, time

import serial

ble_on = "--ble-on" in sys.argv[1:]
args = [a for a in sys.argv[1:] if a != "--ble-on"]
port = args[0] if len(args) > 0 else "/dev/cu.usbserial-1120"
baud = int(args[1]) if len(args) > 1 else 115200
BANNER = b"advertising successfully started"

# Construct WITHOUT opening so we can deassert DTR/RTS first. pyserial otherwise opens with both
# asserted, and an asserted DTR holds the ESP32-C3 IO0 low -> it boots into the silent ROM loader
# (no app UART). Deassert both (IO0 high = normal boot), open, then pulse RTS (EN) for a clean reset.
s = serial.Serial()
s.port = port
s.baudrate = baud
s.timeout = 0.1
s.dtr = False
s.rts = False
s.open()
s.dtr = False
s.rts = True
time.sleep(0.12)
s.rts = False

buf = b""
start = time.time()
sent_ble_on = not ble_on
reported = False
try:
    while True:
        if not sent_ble_on and time.time() - start > 3:
            s.write(b"ble on\r\n")
            sent_ble_on = True
        chunk = s.read(512)
        if not chunk:
            continue
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            if ble_on and not reported and BANNER in line.lower():
                reported = True
                print("BLE_ON_OK", file=sys.stderr, flush=True)
            txt = line.decode("utf-8", "replace").rstrip("\r")
            print(f"{int(time.time()*1000)} {txt}", flush=True)
except KeyboardInterrupt:
    pass
finally:
    s.close()
