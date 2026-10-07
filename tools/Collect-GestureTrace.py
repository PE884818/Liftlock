"""Read-only target receipts during human gestures; raw trace is excluded from source bundles."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--seconds", type=int, default=60)
parser.add_argument("--output", required=True)
args = parser.parse_args()
assert not args.serial.startswith("emulator-")
assert 1 <= args.seconds <= 300
def run(*command):
    return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                          timeout=8, check=True).stdout.decode("utf-8", errors="replace")
fingerprint = run("shell", "getprop", "ro.build.fingerprint").strip()
last = {}; samples = 0; deadline = time.monotonic() + args.seconds
with Path(args.output).open("w", encoding="utf-8") as out:
    while time.monotonic() < deadline:
        try:
            raw = run("shell", "content", "query", "--uri", "content://com.zhuanz.flymemotion.runtime")
            for line in raw.splitlines():
                if not line.startswith("Row:"): continue
                row = dict(part.split("=", 1) for part in re.split(r", (?=[A-Za-z][A-Za-z0-9]*=)",
                           re.sub(r"^Row: \d+ ", "", line)) if "=" in part)
                pkg = row["package"]
                keys = ("moduleVersion", "pid", "boot", "error", "motion", "scene", "blocked", "releaseReason",
                        "sceneSession", "sceneReadiness", "motionFrame", "wallpaper", "gestureJournal", "sceneWorkMaxMs")
                current = {key: row.get(key, "") for key in keys}
                if current == last.get(pkg): continue
                last[pkg] = current; samples += 1
                out.write(json.dumps({"hostEpochMs": int(time.time() * 1000), "package": pkg, **current}, ensure_ascii=False) + "\n")
                out.flush()
        except (subprocess.SubprocessError, OSError) as error:
            out.write(json.dumps({"error": str(error)}) + "\n"); out.flush()
        time.sleep(.2)
print(json.dumps({"samples": samples, "output": args.output, "screenInputPerformed": False}))
