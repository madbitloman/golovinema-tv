#!/bin/bash
set -euo pipefail
tv_root="$(cd "$(dirname "$0")/.." && pwd)"
tv_java_root="${TV_JAVA_BIN:-/opt/homebrew/opt/openjdk@17/bin}"
tv_json="${TV_JSON_JAR:-$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20180813/8566b2b0391d9d4479ea225645c6ed47ef17fe41/json-20180813.jar}"
if [ ! -f "$tv_json" ]; then printf 'Set TV_JSON_JAR to a local org.json JVM test jar. The app uses Android’s built-in JSON implementation.\n'; exit 1; fi
tv_deps="$(python3 "$tv_root/scripts/prepare-tv-dependencies.py")"
tv_check="$tv_root/android/build/checks"
mkdir -p "$tv_check"
"$tv_java_root/javac" -cp "$tv_json:$tv_deps" -d "$tv_check" \
    "$tv_root/android/src/local/tv/streamtest/TrackLanguage.java" \
    "$tv_root/tests/TrackLanguageCheck.java" \
    "$tv_root/android/src/local/tv/streamtest/Movie.java" \
    "$tv_root/android/src/local/tv/streamtest/CatalogParser.java" \
    "$tv_root/android/src/local/tv/streamtest/CatalogHttp.java" \
    "$tv_root/android/src/local/tv/streamtest/CatalogTls.java" \
    "$tv_root/android/src/local/tv/streamtest/CatalogDns.java" \
    "$tv_root/android/src/local/tv/streamtest/AceStream.java" "$tv_root/android/src/local/tv/streamtest/IptvPlaylist.java" "$tv_root/tests/IptvPlaylistCheck.java" "$tv_root/tests/AceStreamCheck.java" \
    "$tv_root/android/src/local/tv/streamtest/PlayerProtocol.java" \
    "$tv_root/android/src/local/tv/streamtest/PlaylistProbe.java" \
    "$tv_root/android/src/local/tv/streamtest/TasteProtocol.java" \
    "$tv_root/android/src/local/tv/streamtest/OmdbClient.java" \
    "$tv_root/android/src/local/tv/streamtest/ServiceKeyPayload.java" \
    "$tv_root/tests/CatalogParserCheck.java" "$tv_root/tests/CatalogHttpCheck.java" "$tv_root/tests/CatalogTlsCheck.java" "$tv_root/tests/CatalogDnsCheck.java" "$tv_root/tests/NativeProtocolCheck.java" "$tv_root/tests/PlaylistProbeCheck.java" "$tv_root/tests/TasteAndRatingsCheck.java"
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" CatalogParserCheck "$@"
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" TrackLanguageCheck
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" CatalogHttpCheck
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" local.tv.streamtest.CatalogTlsCheck "$tv_root/tests/fixtures/tls-localhost.pem"
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" local.tv.streamtest.CatalogDnsCheck
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" local.tv.streamtest.IptvPlaylistCheck "$tv_root/tests/fixtures/iptv-org-sports.m3u"
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" local.tv.streamtest.AceStreamCheck
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" PlaylistProbeCheck
"$tv_java_root/java" -cp "$tv_check:$tv_json:$tv_deps" TasteAndRatingsCheck
cd "$tv_root"
python3 tests/test_configure_services.py
TV_CHECK_JAVA="$tv_java_root/java" TV_CHECK_CLASSES="$tv_check" node --input-type=module <<'NODE'
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {sealResolver, openResolver} from './scripts/player-protocol.mjs';
const actual = execFileSync(process.env.TV_CHECK_JAVA, ['-cp', process.env.TV_CHECK_CLASSES, 'NativeProtocolCheck'], {encoding:'utf8'}).trim();
const expected = sealResolver('2101+1+1+1791987000', {salt:Buffer.from(Array.from({length:8},(_,i)=>16+i)),iv:Buffer.from(Array.from({length:12},(_,i)=>i))});
assert.equal(actual, expected);
const episodeUrl = execFileSync(process.env.TV_CHECK_JAVA, ['-cp', process.env.TV_CHECK_CLASSES, 'NativeProtocolCheck', '1630854062', '812', '1'], {encoding:'utf8'}).trim();
assert.match(openResolver(episodeUrl.split('/get/')[1]), /^1630854062\+812\+1\+\d+$/);
console.log('PASS: native resolver encryption matches the browser-compatible protocol');
NODE
