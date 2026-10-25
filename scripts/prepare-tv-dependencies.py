#!/usr/bin/env python3
"""Resolve pinned jars and resource-free AARs for the manual Android build."""
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "android/build/dependencies"
DEST.mkdir(parents=True, exist_ok=True)
paths = []
artifacts = []
native = {}
for dependency in json.loads((ROOT / "android/dependencies.lock.json").read_text()):
    group, artifact, version = (dependency[key] for key in ("group", "artifact", "version"))
    packaging = dependency.get("packaging", "jar")
    if packaging not in ("jar", "aar"):
        raise SystemExit(f"Unsupported dependency packaging: {packaging}")
    name = f"{artifact}-{version}.{packaging}"
    target = DEST / name
    expected = dependency["sha256"]
    def valid(path):
        return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected
    if not valid(target):
        cache = Path.home() / ".gradle/caches/modules-2/files-2.1" / group / artifact / version
        source = next((path for path in cache.glob(f"*/{name}") if valid(path)), None)
        if source:
            shutil.copyfile(source, target)
        else:
            url = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{name}"
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(8 * 1024 * 1024 + 1)
            if len(data) > 8 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != expected:
                raise SystemExit(f"Dependency verification failed: {name}")
            target.write_bytes(data)
    artifacts.append(target)
    if packaging == "aar":
        # This is deliberately not a general Android resource/manifest merger.
        # Conscrypt has no resources, components or additional dependencies.
        with zipfile.ZipFile(target) as library:
            if library.read("R.txt").strip() or any(n.startswith("res/") and not n.endswith("/") for n in library.namelist()):
                raise SystemExit(f"AAR resources require a proper merger: {name}")
            classes = DEST / f"{artifact}-{version}.jar"
            classes.write_bytes(library.read("classes.jar"))
            paths.append(str(classes))
            for entry in library.namelist():
                parts = entry.split("/")
                if len(parts) == 3 and parts[0] == "jni" and parts[1] in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64") and parts[2].endswith(".so"):
                    native[Path(parts[1]) / parts[2]] = library.read(entry)
    else:
        paths.append(str(target))
# A version change must not leave duplicate, unused jars in the D8 input directory.
for stale in DEST.glob("*.jar"):
    if str(stale) not in paths:
        stale.unlink()
for stale in DEST.glob("*.aar"):
    if stale not in artifacts:
        stale.unlink()
native_root = DEST / "native"
if native_root.exists():
    shutil.rmtree(native_root)
for relative, contents in native.items():
    output = native_root / relative
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(contents)
print(":".join(paths))
