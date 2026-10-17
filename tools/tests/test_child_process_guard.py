import os
import subprocess
import sys
import time
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from child_process_guard import guard  # noqa: E402


def alive(pid):
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
    return str(pid) in out


@unittest.skipUnless(os.name == "nt", "job objects are Windows-only")
class ChildProcessGuardTest(unittest.TestCase):
    def test_guarded_child_dies_with_a_hard_killed_parent(self):
        parent = subprocess.Popen([sys.executable, "-c",
            "import subprocess, sys, time; sys.path.insert(0, sys.argv[1]);"
            "from child_process_guard import guard;"
            "c = guard(subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)']));"
            "print(c.pid, flush=True); time.sleep(60)", str(TOOLS)], stdout=subprocess.PIPE, text=True)
        child = int(parent.stdout.readline())
        self.assertTrue(alive(child))
        parent.kill()  # TerminateProcess: no atexit, no finally
        parent.wait(10)
        deadline = time.monotonic() + 10
        while alive(child) and time.monotonic() < deadline:
            time.sleep(0.2)
        self.assertFalse(alive(child))

    def test_test_doubles_pass_through(self):
        double = object()
        self.assertIs(guard(double), double)


if __name__ == "__main__":
    unittest.main()
