# GOLOVINEMA for Android TV

A small native Android TV app for browsing and watching movies, shows and live TV with a remote. It is built without Gradle: plain `aapt2`, `javac`, D8 and `apksigner`. It targets older TVs too (Android 8+, tested on a Sony BRAVIA running Android 9).

## Features

**Movies and shows**
- Poster rows (Continue watching, Horror, TV Shows, picks), genre shortcuts, title search, season and episode picker.
- My List, resume playback, audio and subtitle track selection.
- Optional IMDb ratings via [OMDb](https://www.omdbapi.com/) and optional search-based recommendations via TypeSafe Jev. Keys are entered from your computer over ADB and encrypted on the TV with Android Keystore. They are never built into the APK.

**Live TV**, in the top bar:
- Full-screen channel playback, a channel list overlay with logos, and groups and favourites.
- Now/next guide from XMLTV, channel zapping with ▲▼ and CH+/CH−, and number-key channel jump.
- Presets for public [iptv-org](https://github.com/iptv-org/iptv) playlists: all channels worldwide (about 10,000, by category or by country), Canada, USA, UK, sports and movies. You can also paste any M3U playlist URL and XMLTV guide URL.
- Duplicate streams of the same channel are merged into one entry. The best stream plays first, and the others are tried automatically if it fails.

**Networking for old TVs**
- Bundles Conscrypt so TLS 1.3 works on Android 9.
- DNS-over-HTTPS for the catalog hosts (with system DNS fallback), for networks whose resolver returns wrong answers.

## Content disclaimer

This project is a client. It hosts no media. Movie and show playback depends on third-party websites that you choose to access. Make sure you have the right to watch what you stream where you live. The live TV presets point at iptv-org, a community index of publicly available free-to-air streams. Channels may be down or geo-blocked.

## Requirements

- macOS or Linux, Python 3, Node 22+, `ffmpeg`, `zip`
- JDK 17. The default is `/opt/homebrew/opt/openjdk@17/bin`; override with `TV_JAVA_BIN`.
- Android SDK with `build-tools/36.0.0` and `platforms/android-36`. The default is `~/Library/Android/sdk`; override with `ANDROID_SDK_ROOT`.
- Pinned Maven dependencies (OkHttp, Okio, Kotlin stdlib, Conscrypt) are listed in `android/dependencies.lock.json` and are checked by SHA-256 on download.

## Build, test, install

```sh
npm install            # dev tooling for JS tests
npm run build:tv       # → dist/cinema.apk (debug-signed with a locally generated key)
npm run test:native    # JVM checks: parser, HTTP, TLS, DNS, IPTV, ratings (set TV_JSON_JAR to an org.json jar)
npm test               # Node checks

# Enable ADB debugging on the TV (Settings → Device Preferences → Developer options), then:
bash scripts/install-golovinema.sh 192.168.1.50
python3 scripts/configure-services.py --tv 192.168.1.50:5555   # optional OMDb/TypeSafe keys
```

## Layout

| Path | What |
| --- | --- |
| `android/src/local/tv/streamtest/` | App source: `CatalogActivity` (home/browse/details), `MainActivity` (player), `LiveTvActivity` + `IptvPlaylist` (live TV), `Catalog*` (HTTP, TLS, DNS, parsing) |
| `android/res/` | Icons, banner, network security config |
| `scripts/` | Build, dependency, install and key-provisioning scripts |
| `tests/` | JVM, Python and Node checks with fixtures |

## License

[MIT](LICENSE). Third-party library notices are in `android/THIRD-PARTY-NOTICES.txt`.
