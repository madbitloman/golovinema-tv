#!/bin/bash
set -euo pipefail
tv_root="$(cd "$(dirname "$0")/.." && pwd)"
tv_sdk="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
tv_tools="$tv_sdk/build-tools/36.0.0"
tv_jar="$tv_sdk/platforms/android-36/android.jar"
tv_java_root="${TV_JAVA_BIN:-/opt/homebrew/opt/openjdk@17/bin}"
tv_build="$tv_root/android/build"
tv_key="$tv_root/android/.local/debug.jks"
tv_deps="$(python3 "$tv_root/scripts/prepare-tv-dependencies.py")"
rm -rf "$tv_build/classes" "$tv_build/generated" "$tv_build/dex"
mkdir -p "$tv_build/classes" "$tv_build/generated" "$tv_build/dex" "$tv_root/android/.local" "$tv_root/android/res/raw" "$tv_root/dist"

# Generated color/motion + quiet tone; no third-party footage or network needed.
if [ ! -f "$tv_root/android/res/raw/test_clip.mp4" ]; then
    ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=30' -f lavfi -i 'sine=frequency=440:sample_rate=48000' \
        -t 8 -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -crf 24 -c:a aac -b:a 64k -af volume=0.12 \
        -movflags +faststart "$tv_root/android/res/raw/test_clip.mp4"
fi
"$tv_tools/aapt2" compile --dir "$tv_root/android/res" -o "$tv_build/resources.zip"
"$tv_tools/aapt2" link -o "$tv_build/resources.apk" --manifest "$tv_root/android/AndroidManifest.xml" -I "$tv_jar" --java "$tv_build/generated" "$tv_build/resources.zip"
find "$tv_root/android/src" "$tv_build/generated" -name '*.java' > "$tv_build/sources.txt"
"$tv_java_root/javac" -source 8 -target 8 -Xlint:-options -classpath "$tv_jar:$tv_deps" -d "$tv_build/classes" @"$tv_build/sources.txt"
find "$tv_build/classes" -name '*.class' > "$tv_build/classes.txt"
find "$tv_build/dependencies" -name '*.jar' >> "$tv_build/classes.txt"
"$tv_java_root/java" -cp "$tv_tools/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$tv_jar" --output "$tv_build/dex" @"$tv_build/classes.txt"
cp "$tv_build/resources.apk" "$tv_build/unsigned.apk"
zip -q -j "$tv_build/unsigned.apk" "$tv_build/dex/"*.dex
# Preserve library resources (notably OkHttp's public suffix data) in the APK.
python3 - "$tv_build" "$tv_root" <<'PYRES'
from pathlib import Path
import sys, zipfile
build, root = map(Path, sys.argv[1:])
with zipfile.ZipFile(build / "unsigned.apk", "a") as apk:
    names = set(apk.namelist())
    for jar in sorted((build / "dependencies").glob("*.jar")):
        with zipfile.ZipFile(jar) as library:
            for entry in library.infolist():
                name = entry.filename
                if entry.is_dir() or name.endswith(".class") or name.startswith("META-INF/") or name in names:
                    continue
                apk.writestr(name, library.read(name)); names.add(name)
    apk.writestr("assets/THIRD-PARTY-NOTICES.txt", (root / "android/THIRD-PARTY-NOTICES.txt").read_bytes())
    for native in sorted((build / "dependencies/native").glob("*/*.so")):
        apk.write(native, "lib/" + native.relative_to(build / "dependencies/native").as_posix(), compress_type=zipfile.ZIP_DEFLATED)
PYRES
"$tv_tools/zipalign" -f -p 4 "$tv_build/unsigned.apk" "$tv_build/aligned.apk"
if [ ! -f "$tv_key" ]; then
    "$tv_java_root/keytool" -genkeypair -keystore "$tv_key" -storepass android -keypass android -alias tv-test -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Local TV Test' -noprompt
fi
"$tv_java_root/java" -jar "$tv_tools/lib/apksigner.jar" sign --ks "$tv_key" --ks-key-alias tv-test --ks-pass pass:android --key-pass pass:android --out "$tv_root/dist/cinema.apk" "$tv_build/aligned.apk"
"$tv_java_root/java" -jar "$tv_tools/lib/apksigner.jar" verify --verbose "$tv_root/dist/cinema.apk"
printf '\nBuilt: %s\n' "$tv_root/dist/cinema.apk"
