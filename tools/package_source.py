"""Package authored sources and compact evidence, excluding vendor APKs and local caches."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
destination = root / "dist" / "Liftlock-0.3.5-miuix22-source.zip"
excluded = {"build", ".gradle", "deps", "target", "files", "__pycache__"}
files = set()
allowed_preview_images = {
    "evidence/manager-bottom-region-0.2.8.png",
    "evidence/manager-emulator/manager-advanced.png",
    "evidence/manager-emulator/manager-dark.png",
    "evidence/manager-emulator/manager-light.png",
}
private_media_suffixes = {".pftrace", ".perfetto-trace", ".mp4", ".webm", ".mov", ".mkv", ".jpg", ".jpeg", ".gif", ".wav", ".mp3"}
for name in ("README.md", "VERIFY.md", "NOTICE.md", "LICENSE", ".gitignore", "settings.gradle", "build.gradle", "gradle.properties", "gradlew", "gradlew.bat"):
    path = root / name
    if path.exists():
        files.add(path)
for folder in ("app", "motion-core", "gradle", "docs", "tools", "evidence", "third_party"):
    for path in (root / folder).rglob("*"):
        if path.is_file() and not any(part in excluded for part in path.relative_to(root).parts):
            # Only authored manager previews may enter this source archive.
            relative = path.relative_to(root).as_posix()
            manager_preview = relative.startswith("evidence/manager-miuix/") and path.suffix.lower() == ".png"
            if path.suffix.lower() in private_media_suffixes or (path.suffix.lower() == ".png" and relative not in allowed_preview_images and not manager_preview):
                continue
            if path.suffix not in {".apk", ".aar", ".log"} and not path.name.startswith(("scope-removal-", "scope-after-", "scope-read-", "return-scope-", "activity-", "home-apk-", "emulator-", "broadcasts-", "windows-", "activities-", "wallpaper-", "surfaceflinger-", "engine-lock-", "engine-home-", "anr_", "anr-list-", "logcat-", "gfxinfo-", "runtime-")):
                files.add(path)
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(files):
        archive.write(path, "FlymePort/" + path.relative_to(root).as_posix())
print(f"Packaged {len(files)} files: {destination.name} ({destination.stat().st_size} bytes)")
