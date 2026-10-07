"""Read-only SurfaceFlinger layer trace around one user unlock (no pixels, no input).

    python tools/trace_unlock_flash.py start <serial>
    (the user unlocks to the desktop once)
    python tools/trace_unlock_flash.py stop <serial>

Records only SurfaceFlinger layer state (visibility, alpha, blur, z-order, buffer) into a
ring buffer. It never captures screen pixels and never injects input.
"""
import pathlib
import subprocess
import sys
import os

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
ROOT = pathlib.Path(__file__).resolve().parent.parent
REMOTE_CFG = "/data/local/tmp/liftlock-flash.cfg"
REMOTE_OUT = "/data/misc/perfetto-traces/liftlock-flash.pftrace"
REMOTE_PID = "/data/local/tmp/liftlock-flash.pid"
CONFIG = """
buffers { size_kb: 393216 fill_policy: RING_BUFFER }
data_sources { config { name: "android.surfaceflinger.layers"
  surfaceflinger_layers_config { mode: MODE_ACTIVE trace_flags: TRACE_FLAG_COMPOSITION trace_flags: TRACE_FLAG_EXTRA } } }
duration_ms: 900000
"""


def adb(serial, *args, check=True):
    result = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {result.stdout}{result.stderr}")
    return result.stdout.strip()


def start(serial):
    local = ROOT / "build" / "liftlock-flash.cfg"
    local.parent.mkdir(exist_ok=True)
    local.write_text(CONFIG, encoding="utf-8")
    adb(serial, "push", str(local), REMOTE_CFG)
    pid = adb(serial, "shell", f"su -c 'rm -f {REMOTE_OUT}; perfetto --background --txt -c {REMOTE_CFG} -o {REMOTE_OUT}'")
    adb(serial, "shell", f"su -c 'echo {pid.split()[-1]} > {REMOTE_PID}'")
    print("tracing, perfetto pid", pid)


def stop(serial):
    pid = adb(serial, "shell", f"su -c 'cat {REMOTE_PID}'")
    adb(serial, "shell", f"su -c 'kill -TERM {pid}; while kill -0 {pid} 2>/dev/null; do sleep 0.2; done; chmod 644 {REMOTE_OUT}; cp {REMOTE_OUT} /data/local/tmp/'")
    out = ROOT / "evidence" / "liftlock-flash.pftrace"
    adb(serial, "pull", "/data/local/tmp/liftlock-flash.pftrace", str(out))
    print("saved", out)


if __name__ == "__main__":
    {"start": start, "stop": stop}[sys.argv[1]](sys.argv[2])
