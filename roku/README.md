# Roku channel — "Golovinema TV" (BrightScript, sideloaded)

Minimal Roku channel that consumes the same data core as the Android TV app:
it calls `core/CoreCli serve` over HTTP for the parsed playlist and plays the
channels natively (HLS). No separate parsing logic lives in BrightScript; the
core is the single source of truth.

## What works

- iptv-org (or any M3U) playlists via the bridge: video + per-channel HTTP
  headers, logos, grouping-friendly grid, numbering.
- Settings: press `*` (options) on the remote to change the bridge address;
  saved to Roku registry.

## What does NOT work on Roku (by design, see core/README.md)

- AceStream streams (engine required).
- Experimental local-only live sources: resolving them needs a JS-capable
  browser environment, which only the Android shell has; the bridge's
  `/events.json` hook exists for other clients but is inert by default.

## Sideload

1. On the Roku handheld remote: Home 5x, Up, Rewind 2x, Up, FastForward 2x,
   Up, FastForward 2x → Developer Settings.
2. Enable "Installer, developer, and uncompressed ZIP file".
3. Find the Roku's LAN IP (Developer Settings screen).
4. Build: `python3 scripts/build-roku.py` → `dist/roku-channel.zip`.
5. Sideload: open `http://<roku-ip>/` in a browser and upload the zip.
   Keep that tab open — it also shows BrightScript console errors.
6. Start the bridge on the Mac: `bash scripts/core.sh serve --playlist <m3u-url> --port 8090 --host 0.0.0.0`
   (`--host 0.0.0.0` is required so a different LAN device can reach it; the
   default manifest bridge URL — this Mac's LAN IP — lives in `roku/manifest`,
   or change it in the channel settings and it is stored in Roku registry).
7. Launch the "Golovinema TV" tile. Logos/websites sometimes load slowly the
   first time; grid fills as JSON arrives.

## Dev loop

- `scripts/core.sh serve` logs refreshed playlist fetches; set `--cache seconds`
  if you change the playlist source often.
- Bridge binds `127.0.0.1` by default — pass `--host 0.0.0.0` so the Roku
  (a different LAN host) can reach it. Mind that this exposes your playlist
  proxy to the whole LAN; laptop on public wifi → keep `127.0.0.1`.
