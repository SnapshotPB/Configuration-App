#!/usr/bin/env python3
"""Reset an ESP32-C3 over its UART auto-reset lines and stream timestamped output.

Usage:  python3 serial-monitor.py /dev/cu.usbserial-1120 [baud]

On open it pulses DTR/RTS to force a clean reset/boot (so each capture starts from
a known state), then prints every line prefixed with a host epoch timestamp. The
ROM/bootloader banner (`ESP-ROM`, `rst:0x1 (POWERON)`, `MCUboot`) is emitted on
UART on every reset regardless of the app log backend, so a mid-connection board
reset is detectable here even when app logs go over BLE.

Run it in the background during a connect and grep the output for `rst:0x`/`POWERON`
inside the connect window, and (if CONFIG_LOG_BACKEND_UART=y) for `snapshot_ble:
Connected` / `Disconnected, reason 0x..`.
"""
import serial
import sys
import time

port = sys.argv[1] if len(sys.argv) > 1 else "/dev/cu.usbserial-1120"
baud = int(sys.argv[2]) if len(sys.argv) > 2 else 115200

s = serial.Serial(port, baud, timeout=0.2)
# ESP32-C3 auto-reset: DTR->GPIO9(boot), RTS->EN(reset). Pulse RTS for a clean boot.
s.setDTR(False)
s.setRTS(True)
time.sleep(0.2)
s.setRTS(False)

buf = b""
while True:
    data = s.read(512)
    if not data:
        continue
    buf += data
    while b"\n" in buf:
        line, buf = buf.split(b"\n", 1)
        sys.stdout.write("%.3f %s\n" % (time.time(), line.decode("utf-8", "replace").rstrip("\r")))
        sys.stdout.flush()
