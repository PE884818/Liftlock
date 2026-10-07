"""Reload only two approved native packages after checking artifact and inactive scene receipts."""
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
serial = "d3f205a7"
def run(*args):
    return subprocess.check_output([adb, "-s", serial, *args], timeout=30).decode("utf-8").strip()

fingerprint = run("shell", "getprop", "ro.build.fingerprint")
assert "uid=0(root)" in run("shell", "su", "-c", "id")
rows = json.loads((root / "evidence/notification-before-reload-0.3.1.json").read_text(encoding="utf-8"))
assert all(row["sceneSession"] == "-1" for row in rows)
ui = next(row for row in rows if row["package"] == "com.android.systemui")
assert ui["notificationState"] in {"ready", "awaiting-lock-engine", "awaiting-native-lockscreen", "shared-lockscreen-prewarmed"}
path = run("shell", "pm", "path", "com.zhuanz.flymemotion").removeprefix("package:")
assert re.fullmatch(r"/data/app/[A-Za-z0-9_./~+=-]+/base\.apk", path)
digest = run("shell", "su", "-c", "sha256sum " + path).split()[0]
installation = json.loads((root / "evidence/installation.json").read_text(encoding="utf-8-sig"))
assert digest == installation["sha256"]
selected = [row for row in rows if row["package"] in {"com.android.systemui", "com.miui.miwallpaper"}]
assert len(selected) == 2
pids = []
for row in selected:
    assert run("shell", "pidof", row["package"]) == row["pid"], "Process changed; reacquire receipts"
    pids.append(int(row["pid"]))
run("shell", "su", "-c", "kill -TERM " + " ".join(map(str, pids)))
report = {"verifiedInstalledSha256": digest, "selectedPids": pids,
          "reloadedPackages": [row["package"] for row in selected], "scenesInactive": True,
          "phoneInput": False, "phonePixels": False, "frameworkReboot": False}
(root / "evidence/reload-0.3.1.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report))
