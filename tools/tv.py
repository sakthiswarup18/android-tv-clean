#!/usr/bin/env python3
"""Minimal ADB client for the TV, built on the adb-shell package.

  tools/tv.py 'getprop ro.build.version.sdk'        run a shell command
  tools/tv.py --keys DPAD_RIGHT DPAD_DOWN           press remote keys
  tools/tv.py --screenshot shots/01.png [KEY ...]   press keys, then capture
  tools/tv.py --push local remote                   push a file (base64 fallback)

TV_HOST (required) and TV_PORT (default 5555) set the TV's address.
"""
import base64, hashlib, os, sys, time
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_HOST")
PORT = int(os.environ.get("TV_PORT", "5555"))
KEY = os.path.expanduser("~/.android/adbkey")


def connect():
    if not HOST:
        sys.exit("Set TV_HOST to the TV's IP address")
    if not os.path.exists(KEY):
        os.makedirs(os.path.dirname(KEY), exist_ok=True)
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=30)
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=60)
    return dev


def sh(dev, cmd, timeout=120):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout, decode=True)


def push(dev, local, remote, chunk=48000):
    """Sync push, falling back to base64 chunks through the shell (the sync
    service can hang on larger files). Returns the possibly new connection."""
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    try:
        if len(data) > 512 * 1024:
            raise RuntimeError("large file")
        dev.push(local, remote, read_timeout_s=60)
    except Exception as e:
        print("sync push skipped/failed (%r), using base64 chunks" % e, file=sys.stderr)
        try:
            dev.close()
        except Exception:
            pass
        dev = connect()
        sh(dev, "rm -f %s.b64" % remote)
        b64 = base64.b64encode(data).decode()
        for i in range(0, len(b64), chunk):
            sh(dev, "echo -n '%s' >> %s.b64" % (b64[i:i + chunk], remote))
        decode(dev, remote, len(data))
    got = sh(dev, "md5sum %s" % remote).split()[0]
    if got != want:
        sys.exit("md5 mismatch: %s != %s" % (got, want))
    print("pushed %s (%d bytes, md5 ok)" % (remote, len(data)))
    return dev


def decode(dev, remote, size, blocks=2048):
    """Decode remote.b64 into remote in slices of blocks*4096 bytes (a multiple of 4,
    so each slice is valid base64): one large decode outlasts the ADB read timeout,
    and a background decode is killed when the shell session closes."""
    b64size = int(sh(dev, "stat -c %%s %s.b64" % remote).strip())
    total = (b64size + 4095) // 4096
    sh(dev, "rm -f %s" % remote)
    for k in range(0, total, blocks):
        sh(dev, "dd if=%s.b64 bs=4096 skip=%d count=%d 2>/dev/null | base64 -d >> %s"
                % (remote, k, blocks, remote))
    sh(dev, "rm %s.b64" % remote)
    got = sh(dev, "stat -c %%s %s" % remote).strip()
    if got != str(size):
        sys.exit("decoded size %s != %d" % (got, size))


def keys(dev, names):
    for k in names:
        k = k if k.isdigit() or k.startswith("KEYCODE_") else "KEYCODE_" + k
        sh(dev, "input keyevent %s" % k)
        time.sleep(0.6)


def screenshot(dev, out):
    time.sleep(1.0)
    sh(dev, "screencap -p /sdcard/_shot.png")
    size = sh(dev, "stat -c %s /sdcard/_shot.png 2>/dev/null").strip()
    if not size or size == "0":
        sys.exit("empty screenshot (Live TV/HDMI/DRM app on screen); check "
                 "dumpsys activity activities | grep topResumedActivity")
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    try:
        dev.pull("/sdcard/_shot.png", out, read_timeout_s=60)
    except Exception:
        b64 = sh(dev, "base64 /sdcard/_shot.png")
        open(out, "wb").write(base64.b64decode(b64))
    print("saved", out)


def main(a):
    dev = connect()
    try:
        if a and a[0] == "--screenshot":
            keys(dev, a[2:])
            screenshot(dev, a[1])
        elif a and a[0] == "--keys":
            keys(dev, a[1:])
        elif a and a[0] == "--push":
            dev = push(dev, a[1], a[2])
        else:
            print(sh(dev, " ".join(a)), end="")
    finally:
        dev.close()


if __name__ == "__main__":
    main(sys.argv[1:])
