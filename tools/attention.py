"""Get the operator's attention before an on-phone action is needed (user request 2026-10-16).

  python -B tools/attention.py "Tap Install on the Xiaomi" --serial cb0e29be7d73 [--serial ...]

Beeps the host (three short tones), wakes each named phone's screen and vibrates it. Vibration only:
phone volume stays as the user set it (never raised). Exit code 0 also when a phone is unreachable,
so a caller can continue and wait for the action itself.
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_tools import default_adb  # noqa: E402


def beep_host():
    try:
        import winsound
        for _ in range(3):
            winsound.Beep(1200, 250)
            time.sleep(0.15)
    except Exception:
        sys.stdout.write("\a\a\a")
        sys.stdout.flush()


def nudge_phone(adb, serial):
    commands = (
        ["shell", "input", "keyevent", "KEYCODE_WAKEUP"],
        # Android 12+: cmd vibrator_manager; older phones fall back to the legacy vibrator service.
        ["shell", "cmd vibrator_manager synced -f oneshot 600 || cmd vibrator vibrate 600"],
    )
    for args in commands:
        try:
            subprocess.run([adb, "-s", serial, *args], capture_output=True, timeout=10)
        except Exception:
            return False
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("message")
    parser.add_argument("--serial", action="append", default=[])
    parser.add_argument("--adb", default=default_adb())
    args = parser.parse_args(argv)
    print(f"ATTENTION: {args.message}", flush=True)
    beep_host()
    for serial in args.serial:
        if not nudge_phone(args.adb, serial):
            print(f"{serial}: could not wake/vibrate", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
