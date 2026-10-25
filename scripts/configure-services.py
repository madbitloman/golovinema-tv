#!/usr/bin/env python3
"""Install GOLOVINEMA and provision personal keys over authorized ADB stdin, never argv."""
import argparse
import getpass
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = "local.tv.streamtest"
ROOT = Path(__file__).resolve().parent.parent
KEY_FILE = "files/cinema-keys.json"
RESULT_FILE = "files/cinema-keys-result.json"
WRITE_KEYS = f"umask 077; mkdir -p files && cat > {KEY_FILE}"


class SetupError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def key_input(label):
    raw = getpass.getpass(label)
    value = raw.strip()
    if len(value) > 4096 or any(ord(char) < 32 or ord(char) == 127 for char in raw):
        raise SetupError("Invalid key format. No keys transferred.")
    return value


def verify_omdb(key):
    url = "https://www.omdbapi.com/?" + urllib.parse.urlencode({"i": "tt3896198", "apikey": key})
    try:
        with urllib.request.build_opener(NoRedirect).open(url, timeout=15) as response:
            raw = response.read(131073)
            if len(raw) > 131072:
                raise ValueError()
            data = json.loads(raw)
        if data.get("Response") != "True" or data.get("imdbID") != "tt3896198":
            return False, "OMDb rejected the test lookup. Check activation and the key."
        return True, "OMDb key verified with a live IMDb-ID lookup."
    except urllib.error.HTTPError as error:
        return False, f"OMDb returned HTTP {error.code}; key not verified."
    except Exception:
        # URL-bearing exception messages must never be displayed.
        return False, "Could not reach or validate OMDb; key not verified."


def adb_run(adb, serial, *args, payload=None, timeout=20, check=True, stage=None):
    label = stage or (args[0] if args else "ADB command")
    # None normally inherits Terminal's stdin. In particular, exec-in then waits
    # for keyboard EOF even for commands such as rm. Never inherit the terminal.
    stream = {"input": payload} if payload is not None else {"stdin": subprocess.DEVNULL}
    try:
        result = subprocess.run([str(adb), "-s", serial, *args], **stream,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    except FileNotFoundError:
        raise SetupError(f"ADB executable not found: {adb}") from None
    except subprocess.TimeoutExpired:
        raise SetupError(f"ADB timed out after {timeout}s during: {label}.") from None
    except OSError as error:
        raise SetupError(f"Could not execute ADB during {label}: {error.strerror or 'OS error'}") from None
    if check and result.returncode:
        detail = ""
        if payload is None:
            raw = (result.stderr or result.stdout).decode("utf-8", errors="replace")
            detail = "\n" + "".join(char for char in raw if char.isprintable() or char == "\n").strip()[:1500]
        raise SetupError(f"ADB failed during {label} (exit {result.returncode}).{detail}")
    return result


def adb_shell(adb, serial, *args, payload=None, **options):
    # Shell v2 delivers stdin EOF and a remote exit status. No PTY means ADB
    # cannot change Terminal modes, echo credentials, or transform payload bytes.
    command = shlex.join(args)
    flags = ("-T", "-n") if payload is None else ("-T",)
    return adb_run(adb, serial, "shell", *flags, command, payload=payload, **options)


def provision(adb, serial, keys):
    request = secrets.token_hex(16)
    payload = json.dumps({"version": 1, "request": request, "keys": keys}).encode()
    if len(payload) > 12288:
        raise SetupError("Key setup too large; no keys transferred.")
    print("Saving service keys on the TV…", flush=True)
    adb_shell(adb, serial, "am", "force-stop", PACKAGE, stage="stopping GOLOVINEMA for key setup")
    adb_shell(adb, serial, "run-as", PACKAGE, "rm", "-f", RESULT_FILE, stage="clearing the old setup receipt")
    try:
        adb_shell(adb, serial, "run-as", PACKAGE, "sh", "-c", WRITE_KEYS,
                  payload=payload, stage="transferring keys to GOLOVINEMA")
        adb_shell(adb, serial, "am", "start", "-W", "-n", PACKAGE + "/.CatalogActivity", stage="launching GOLOVINEMA to import keys")
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            response = adb_shell(adb, serial, "run-as", PACKAGE, "cat", RESULT_FILE, check=False, stage="reading key setup confirmation")
            try:
                receipt = json.loads(response.stdout)
            except (ValueError, UnicodeError):
                time.sleep(.5)
                continue
            if receipt.get("request") != request:
                raise SetupError("TV rejected the setup payload. Check GOLOVINEMA settings.")
            if not receipt.get("ok") or set(receipt.get("saved", [])) != set(keys) or not receipt.get("pending_removed"):
                raise SetupError("TV could not confirm secure storage. Some keys may have saved; check GOLOVINEMA settings.")
            print("TV confirmed encrypted storage and removed the temporary handoff.")
            return
        raise SetupError("No confirmation from GOLOVINEMA. Check the TV screen and app settings.")
    finally:
        # Always attempt to remove the private handoff, including partial write/launch failure.
        try:
            cleanup = adb_shell(adb, serial, "run-as", PACKAGE, "rm", "-f", KEY_FILE, RESULT_FILE, check=False, stage="cleaning up the private key handoff")
            if cleanup.returncode:
                print("Temporary handoff cleanup could not be confirmed. Reconnect and run setup again.", file=sys.stderr)
        except SetupError:
            print("TV disconnected before cleanup confirmation. Reconnect and launch GOLOVINEMA to consume the private handoff.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tv", default=os.environ.get("TV_ADB", ""), required="TV_ADB" not in os.environ, help="TV ADB address, e.g. 192.168.1.50:5555 (or set TV_ADB)")
    parser.add_argument("--install", action="store_true", help="install the built APK before setup")
    args = parser.parse_args()
    if not sys.stdin.isatty():
        raise SetupError("Run this script in Terminal; keys are entered at hidden prompts.")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    adb = sdk / "platform-tools/adb"
    serial = args.tv if ":" in args.tv else args.tv + ":5555"
    print(f"Checking TV connection: {serial}", flush=True)
    state = adb_run(adb, serial, "get-state", check=False, stage="checking the existing TV connection")
    if state.returncode or state.stdout.strip() != b"device":
        adb_run(adb, serial, "connect", serial, stage="connecting to the TV")
        state = adb_run(adb, serial, "get-state", check=False, stage="checking TV authorization")
    if state.returncode or state.stdout.strip() != b"device":
        raise SetupError("TV not authorized/reachable. Keep it awake, use the same network, and approve debugging on the TV.")
    if args.install:
        print("Installing GOLOVINEMA…")
        result = adb_run(adb, serial, "install", "-r", str(ROOT / "dist/cinema.apk"), timeout=90, stage="installing GOLOVINEMA")
        if b"Success" not in result.stdout:
            raise SetupError("APK installation was not confirmed.")
    adb_shell(adb, serial, "run-as", PACKAGE, "id", stage="checking GOLOVINEMA's private setup access")
    print("Enter keys privately. Press Enter to leave a service unchanged.")
    keys = {}
    omdb = key_input("OMDb API key: ")
    if omdb:
        verified, message = verify_omdb(omdb)
        print(message)
        if not verified and input("Save this key to the TV anyway? [y/N] ").strip().lower() != "y":
            raise SetupError("Setup stopped; no keys transferred.")
        keys["omdb"] = omdb
    jev = key_input("TypeSafe/Jev API key (optional): ")
    if jev:
        keys["jev"] = jev
    if keys:
        provision(adb, serial, keys)
    else:
        print("No keys changed.")
        adb_shell(adb, serial, "am", "start", "-n", PACKAGE + "/.CatalogActivity", stage="opening GOLOVINEMA")
    print("On the TV: open a title → IMDb rating → confirm the title and year.")
    if jev:
        print("Jev is paused. Enable it in ⋯ → Your taste after reviewing what search data is sent.")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print("\nSetup cancelled.", file=sys.stderr)
        sys.exit(1)
    except SetupError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
