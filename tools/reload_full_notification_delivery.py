"""Reload approved SystemUI and Wallpaper only, after fresh target and inactive scene checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
args = parser.parse_args()
assert not args.serial.startswith("emulator-")
adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")

def run(*arguments):
    return subprocess.check_output([adb, "-s", args.serial, *arguments], timeout=30).decode("utf-8").strip()

assert "uid=0(root)" in run("shell", "su", "-c", "id")
receipt = root / "evidence/notification-before-reload-0.3.3.json"
subprocess.run(["python", str(root / "tools/collect_notification_runtime.py"), "--serial", args.serial, "--output", str(receipt)], check=True, timeout=35, stdout=subprocess.DEVNULL)
rows = json.loads(receipt.read_text(encoding="utf-8"))
assert all(row["sceneSession"] == "-1" for row in rows), "An active lock scene must be revoked through the manager before reloading"
ui = next(row for row in rows if row["package"] == "com.android.systemui")
# The known permanent adapter failure has no active notification lease.
assert ui["notificationState"] in {"failed", "unsupported", "ready", "awaiting-lock-engine", "awaiting-native-lockscreen", "shared-lockscreen-prewarmed"}, "Notification presentation still active"
assert run("shell", "pidof", ui["package"]) == ui["pid"], "Process changed; reacquire receipts"
elapsed = int(run("shell", "cat", "/proc/uptime").split()[0].split(".")[0]) * 1000
assert abs(elapsed - int(ui["elapsed"])) < 75000, "Inactive receipt is stale beyond the native 60-second heartbeat"
assert ui["boot"] == run("shell", "settings", "get", "global", "boot_count"), "Receipt belongs to an earlier boot"
path = run("shell", "pm", "path", "--user", "0", "com.zhuanz.flymemotion").removeprefix("package:")
assert re.fullmatch(r"/data/app/[A-Za-z0-9_./~+=-]+/base\.apk", path)
digest = run("shell", "su", "-c", "sha256sum " + path).split()[0]
local = hashlib.sha256((root / "dist/FlymeMotionPort-0.3.3-stage2.apk").read_bytes()).hexdigest()
assert digest == local
wallpaper = next(row for row in rows if row["package"] == "com.miui.miwallpaper")
assert run("shell", "pidof", wallpaper["package"]) == wallpaper["pid"]
assert wallpaper["boot"] == ui["boot"]
selected = [int(ui["pid"]),int(wallpaper["pid"])]
started = time.time()
run("shell", "su", "-c", "kill -TERM " + " ".join(map(str,selected)))
report = {"verifiedInstalledSha256": digest, "selectedPids": selected,
          "reloadedPackages": [ui["package"],wallpaper["package"]], "scenesInactive": True,
          "checkedAtUnix": started, "physicalSerial": args.serial,
          "phoneInput": False, "phonePixels": False, "frameworkReboot": False}
(root / "evidence/reload-0.3.3.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report))
