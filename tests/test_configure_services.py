"""Exercise credential transport and failure cleanup without ADB or real credentials."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("setup", Path(__file__).resolve().parents[1] / "scripts/configure-services.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class ProvisionTests(unittest.TestCase):
    def run_fake(self, fail_launch=False, mismatch=False):
        calls, payload = [], {}
        sentinel = "fixture-credential-never-in-argv"

        def run(command, **options):
            nonlocal payload
            calls.append(command)
            self.assertNotIn(sentinel, " ".join(command))
            self.assertEqual(command[3:5], ["shell", "-T"])
            remote = shlex.split(command[-1])
            if options.get("input"):
                self.assertEqual(remote, ["run-as", setup.PACKAGE, "sh", "-c", setup.WRITE_KEYS])
                self.assertNotIn("-n", command[3:-1])
                payload = json.loads(options["input"])
                self.assertEqual(payload["keys"]["omdb"], sentinel)
            else:
                self.assertEqual(options["stdin"], subprocess.DEVNULL)
                self.assertIn("-n", command[3:-1])
            if "start" in remote and fail_launch:
                return subprocess.CompletedProcess(command, 1, b"", b"unavailable")
            output = b""
            if "cat" in remote:
                receipt = {"request": "wrong" if mismatch else payload["request"], "ok": True, "saved": ["omdb"], "pending_removed": True}
                output = json.dumps(receipt).encode()
            return subprocess.CompletedProcess(command, 0, output, b"")

        stream = io.StringIO()
        with patch.object(setup.subprocess, "run", side_effect=run), contextlib.redirect_stdout(stream):
            if fail_launch or mismatch:
                with self.assertRaises(setup.SetupError):
                    setup.provision("adb", "fixture:5555", {"omdb": sentinel})
            else:
                setup.provision("adb", "fixture:5555", {"omdb": sentinel})
                self.assertIn("confirmed encrypted storage", stream.getvalue())
        self.assertNotIn(sentinel, stream.getvalue())
        self.assertEqual(shlex.split(calls[-1][-1])[-4:], ["rm", "-f", setup.KEY_FILE, setup.RESULT_FILE])

    def test_stdin_and_matching_receipt(self):
        self.run_fake()

    def test_launch_failure_cleans_handoff(self):
        self.run_fake(fail_launch=True)

    def test_mismatched_receipt_not_success(self):
        self.run_fake(mismatch=True)

    def test_network_error_never_prints_url(self):
        with patch.object(setup.urllib.request, "build_opener", side_effect=ValueError("credential-in-url")):
            accepted, message = setup.verify_omdb("fixture")
            self.assertFalse(accepted)
            self.assertNotIn("credential-in-url", message)

    def test_open_parent_stdin_does_not_block_adb(self):
        # Real subprocess regression: leave the caller's stdin OPEN, as in Terminal.
        # The old input=None behavior passed it through and hung waiting for EOF.
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory) / "adb"
            fake.write_text("#!" + sys.executable + "\nimport sys\nassert sys.stdin.buffer.read() == b''\nprint('finished')\n")
            fake.chmod(0o700)
            code = (
                "import importlib.util\n"
                f"spec=importlib.util.spec_from_file_location('setup', {str(Path(setup.__file__))!r})\n"
                "setup=importlib.util.module_from_spec(spec); spec.loader.exec_module(setup)\n"
                f"result=setup.adb_run({str(fake)!r}, 'fixture:5555', 'exec-in', 'rm', timeout=1)\n"
                "assert result.stdout.strip() == b'finished'\n"
            )
            child = subprocess.Popen([sys.executable, "-c", code], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                child.wait(timeout=4)  # Do not close stdin before checking completion.
                stdout, stderr = child.communicate()
                self.assertEqual(child.returncode, 0, stderr.decode())
            finally:
                if child.poll() is None:
                    child.kill()
                    child.communicate()

    def test_timeout_identifies_stage_without_payload(self):
        with patch.object(setup.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 20, stderr=b"fixture-secret")):
            with self.assertRaises(setup.SetupError) as result:
                setup.adb_run("adb", "fixture", "shell", payload=b"fixture-secret", stage="transferring keys")
        self.assertIn("transferring keys", str(result.exception))
        self.assertNotIn("fixture-secret", str(result.exception))

    def test_remote_failure_and_missing_executable_are_distinct(self):
        with patch.object(setup.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"run-as: package not debuggable")):
            with self.assertRaises(setup.SetupError) as result:
                setup.adb_shell("adb", "fixture", "run-as", setup.PACKAGE, "id", stage="private setup access")
            self.assertIn("not debuggable", str(result.exception))
        with patch.object(setup.subprocess, "run", side_effect=FileNotFoundError()):
            with self.assertRaisesRegex(setup.SetupError, "executable not found"):
                setup.adb_run("/missing/adb", "fixture", "get-state")


if __name__ == "__main__":
    unittest.main()
