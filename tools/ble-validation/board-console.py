#!/usr/bin/env python3
"""Send one command to the board shell on the UART console, and print what the board answers.

  board-console.py <port> "<command>" [baud]

Example: board-console.py /dev/cu.usbserial-1110 "ble off"

The script keeps DTR and RTS low, but on some hosts (macOS with the board USB-UART) the port open
itself resets the board. Thus the script reads the console until the boot output stops (at most
BOOT_WAIT_S), then sends the command. It reads the answer for 2 s, prints it, and closes the port.
Exit 0 = the port opened and the command went out.

A reset turns the radio off, because the firmware starts with the radio off. `ble on` after the
wait turns it on again.

The radio-off sensitivity arm of the desktop harness (DesktopReliabilityRunner, arm=radio-off)
calls this script to turn the board radio off and on again.
"""
import sys, time

import serial

if len(sys.argv) < 3:
    print(__doc__)
    sys.exit(2)

port = sys.argv[1]
command = sys.argv[2]
baud = int(sys.argv[3]) if len(sys.argv) > 3 else 115200

s = serial.Serial()
s.port, s.baudrate, s.timeout = port, baud, 0.1
# Keep DTR and RTS low before the port opens, so that the open does not reset the board.
s.dtr = False
s.rts = False
s.open()

# Wait until the board sends nothing for QUIET_S, so that a boot that the open caused is complete.
BOOT_WAIT_S = 6.0
QUIET_S = 1.0
start = time.time()
last_data = start
while time.time() - start < BOOT_WAIT_S and time.time() - last_data < QUIET_S:
    if s.read(512):
        last_data = time.time()

s.write(command.encode("ascii") + b"\r\n")
deadline = time.time() + 2
buf = b""
while time.time() < deadline:
    buf += s.read(512)
s.close()
sys.stdout.write(buf.decode("utf-8", "replace"))
