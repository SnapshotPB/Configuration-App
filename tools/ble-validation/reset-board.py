#!/usr/bin/env python3
"""Reset the ESP32-C3 and exit once it has re-advertised (clean known starting state).

  reset-board.py [port] [baud]

Deasserts DTR (IO0 high = normal boot), pulses RTS (EN) to reset, waits up to ~8 s for
'Advertising successfully started', prints OK/uptime, then closes the port so a measurement
run can have the radio to itself. Exit 0 = board confirmed advertising, 2 = timed out.

Since firmware 845c20e the BLE radio is off at every boot. When the board does not advertise
3 s after the reset, this script sends `ble on` on the console. Firmware that advertises at
boot sees no command. The banner match ignores case, because the current firmware writes
'Bluetooth enabled; advertising successfully started'.
"""
import sys, time

import serial

port = sys.argv[1] if len(sys.argv) > 1 else "/dev/cu.usbserial-1120"
baud = int(sys.argv[2]) if len(sys.argv) > 2 else 115200

s = serial.Serial()
s.port, s.baudrate, s.timeout = port, baud, 0.1
s.dtr = False
s.rts = False
s.open()
s.dtr = False
s.rts = True
time.sleep(0.12)
s.rts = False

BANNER = b"advertising successfully started"
start = time.time()
deadline = start + 8
buf = b""
booted = False
sent_ble_on = False
while time.time() < deadline:
    buf += s.read(512)
    if BANNER in buf.lower():
        booted = True
        break
    if not sent_ble_on and time.time() - start > 3:
        s.write(b"ble on\r\n")
        sent_ble_on = True
        deadline = time.time() + 10
s.close()
if booted:
    print("BOARD RESET OK (re-advertising)")
    sys.exit(0)
else:
    print("BOARD RESET TIMEOUT (no advertising banner seen)")
    sys.exit(2)
