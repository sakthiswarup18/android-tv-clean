#!/usr/bin/env python3
"""Check that network ADB is closed on the TV: only "connection refused" proves it.

  tools/adb-closed.py TV_IP [port]

Exit 0: refused (closed). Exit 1: open. Exit 2: inconclusive (timeout / no route,
which on macOS can be Local Network privacy rather than the TV)."""
import errno, socket, sys

if len(sys.argv) < 2:
    sys.exit("usage: tools/adb-closed.py TV_IP [port]")
host = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555
s = socket.socket()
s.settimeout(5)
try:
    s.connect((host, port))
    print("OPEN: %s:%d accepts connections - network ADB is still on" % (host, port))
    sys.exit(1)
except ConnectionRefusedError:
    print("CLOSED: %s:%d refused the connection" % (host, port))
    sys.exit(0)
except OSError as e:
    print("INCONCLUSIVE: %s (%s) - check the TV is on and this Mac may reach the LAN"
          % (e.strerror or e, errno.errorcode.get(e.errno, "?")))
    sys.exit(2)
finally:
    s.close()
