# core/ — shared, JVM-portable TV core

The Android classes in `android/src/local/tv/streamtest/` were written to also compile
on any Java 17 JVM (no `android.*` imports in the parsers/resolvers — verified by
`scripts/check-native.sh`). This folder is the cross-platform façade over them:

- `core/CoreCli.java (package `local.tv.core`)` — command-line interface that renders the shared logic as JSON:
  - `playlist <url>` — parse an M3U playlist into normalized channel rows (numbering,
    grouping, backup streams, AceStream links folded in, per-stream HTTP headers).
  - `guide <playlist-url> <guide-url> [hours]` — XMLTV guide restricted to the playlist
    channel set, exported as milliseconds/UTC JSON.
  - `serve [port] [--playlist URL] [--cache seconds]` — a local HTTP bridge that
    exposes the same JSON to other platforms (Roku, web, anything that speaks HTTP):
    - `GET /playlist.json` — current playlist (refreshed on the cache TTL)
    - `GET /guide.json` — current guide window (now → +24 h by default)
    - `GET /health.json` — status counters
    - `GET /events.json` — optional local-only plugin hook (see below)
- `scripts/core.sh` — compile + run helper (JVM only, reuses `android/build/dependencies`).

## Portability contract

Classes that platform shells are allowed to share:

| Shared | Role |
| --- | --- |
| `IptvPlaylist` | M3U + XMLTV parsing/normalisation, channel scoring, guide windows |
| `AceStream` | AceStream content-id extraction → engine links |
| `CatalogParser`, `CatalogHttp`, `CatalogTls`, `CatalogDns` | movie catalogs + HTTP/TLS stack |

UI, playback, persistence, and device code stay per-platform by design:
- Android TV: `LiveTvActivity`, `CatalogActivity`, `VideoView`-based playback.
- Roku: the BrightScript channel in `roku/`, playback via the SceneGraph `Video` node.

## Events plugin hook (local-only)

`serve` maps `GET /events.json` to a static `events()` method on a class named
`CoreSpi`, if such a class is present on the classpath. This exists for
local-only stream-source integrations the project deliberately keeps out of
the published tree (device rules in the private handoff docs); the hook is
generic, the committed code never names what it reads, and nothing that
scrapes those sources is committed or pushed.

Bound by default to `127.0.0.1` only. Other platforms reach it from the same LAN
through the machine's LAN address — start with `--host 0.0.0.0` to allow that.
