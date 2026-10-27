#!/bin/bash
# Compile and run the cross-platform core CLI/bridge (JVM only; zero new dependencies).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
java_bin="${TV_JAVA_BIN:-/opt/homebrew/opt/openjdk@17/bin}"
json="${TV_JSON_JAR:-$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20180813/8566b2b0391d9d4479ea225645c6ed47ef17fe41/json-20180813.jar}"
[ -f "$json" ] || { printf 'Set TV_JSON_JAR to a local org.json jar.\n'; exit 1; }
deps="$(python3 "$root/scripts/prepare-tv-dependencies.py")"
build="$root/core/build"
mkdir -p "$build"
"$java_bin/javac" -cp "$json:$deps" -d "$build" \
    "$root/android/src/local/tv/streamtest/CatalogHttp.java" \
    "$root/android/src/local/tv/streamtest/CatalogTls.java" \
    "$root/android/src/local/tv/streamtest/CatalogDns.java" \
    "$root/android/src/local/tv/streamtest/AceStream.java" \
    "$root/android/src/local/tv/streamtest/IptvPlaylist.java" \
    "$root/core/CoreCli.java"
# Optional local-only events plugin (not committed; see /events.json hook in core/README.md).
if [ -f "$root/core/CoreSpi.java" ]; then
    "$java_bin/javac" -sourcepath "$root/android/src:$root/core" -cp "$build:$json:$deps" -d "$build" "$root/core/CoreSpi.java"
fi
exec "$java_bin/java" -cp "$build:$json:$deps" local.tv.core.CoreCli "$@"
