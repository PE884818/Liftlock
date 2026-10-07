"""Bounded metadata only; no pixels, input, process reload or configuration writes."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
raw = subprocess.run([str(adb), "-s", args.serial, "shell", "content", "query", "--uri",
                      "content://com.zhuanz.flymemotion.runtime"], check=True,
                     capture_output=True, timeout=30).stdout.decode("utf-8")
keep = {"package", "state", "hooks", "pid", "boot", "elapsed", "moduleVersion", "error",
        "sceneSession", "enabled", "configRevision", "notificationState", "notificationDetail",
        "notificationGestures", "notificationFrames", "notificationSprings",
        "liveScene", "motionFrame", "motion", "releaseReason", "desktopAnimationState", "desktopAnimationDetail"}
rows = []
for line in raw.splitlines():
    if not line.startswith("Row:"):
        continue
    fields = re.split(r", (?=[A-Za-z]\w*=)", line.split(" ", 2)[2])
    parsed = dict(field.split("=", 1) for field in fields if "=" in field)
    rows.append({key: parsed.get(key, "") for key in sorted(keep)})
assert len(rows) == 4, "Expected four native process receipts"
args.output.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(rows, ensure_ascii=True))
