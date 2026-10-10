"""Supervised live `adb logcat` capture and the end-of-case merge of a device log dump.

The live stream is not a reliable record on every phone. On the Oppo A15s (Android 10/ColorOS) it ran
4-21 s behind the app (voice-speech-s24a15s-51cc8028) and then kept only 20-40 app lines per case
(xpair-asusa15s-915d7390b) although logd held thousands. The capture is therefore supervised:
a stalled or dead reader is restarted from its last timestamp with exact-line de-duplication, and at the
end of a case the device buffer is merged in so the scorer sees every line the app logged. Nothing is
invented: merged lines come from `logcat -d --pid=<this PID>` inside the capture's own time window.
"""
import collections
import re
import subprocess
import threading
import time

THREADTIME = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s")
HEADER = "---------"


def line_time(line):
    """Seconds within the year (the year is absent from logcat; midnight wrap is not handled) or None."""
    match = THREADTIME.match(line)
    if not match:
        return None
    month_day, clock = match[1].split(" ")
    hours, minutes, seconds = clock.split(":")
    month, day = month_day.split("-")
    return ((int(month) * 31 + int(day)) * 86400) + int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def merge_dump(live_lines, dump_lines, pid, start=None, end=None):
    """Return (merged lines, added lines). Dump lines already in the live capture (exact multiset match)
    are dropped; the rest are inserted in timestamp order without reordering the live lines. Only lines
    of `pid` inside [start, end] (logcat time strings, either optional) are accepted."""
    lower = line_time(start + " ") if start else None
    upper = line_time(end + " ") if end else None
    live_count = collections.Counter(live_lines)
    added = []
    for line in dump_lines:
        stamp = line_time(line)
        if stamp is None:
            continue
        fields = line.split(None, 5)
        if len(fields) < 6 or not fields[2].isdigit() or int(fields[2]) != int(pid):
            continue
        if (lower is not None and stamp < lower) or (upper is not None and stamp > upper):
            continue
        if live_count[line] > 0:
            live_count[line] -= 1
            continue
        added.append((stamp, line))
    added.sort(key=lambda item: item[0])
    merged, position = [], 0
    stamps = [line_time(line) for line in live_lines]
    for stamp, line in added:
        while position < len(live_lines) and (stamps[position] is None or stamps[position] <= stamp):
            merged.append(live_lines[position])
            position += 1
        merged.append(line)
    merged.extend(live_lines[position:])
    return merged, [line for _, line in added]


class LogCapture:
    """`adb logcat` reader thread appending to a log file. Quacks like the Popen/handle pair that the
    runners keep in `captures`: poll() is None until stop(), terminate()/wait()/kill()/close() stop it."""

    STALL_S = 10.0
    MIN_RESTART_GAP_S = 5.0
    MAX_RESTARTS = 20

    def __init__(self, adb_path, serial, pid, since, path, filters=()):
        self.adb_path, self.serial, self.pid, self.since = adb_path, serial, str(pid), since
        self.path, self.filters = path, list(filters)
        self.restarts = []
        self.lines = 0
        self.closed = False
        self.returncode = None
        self.lock = threading.Lock()
        self.skip = collections.Counter()
        self.last_ts = None
        self.last_restart = float("-inf")
        self.handle = path.open("w", encoding="utf-8", newline="\n")
        self.last_line = time.perf_counter()
        self.process = None
        self.spawn(since)

    def command(self, since):
        return [self.adb_path, "-s", self.serial, "logcat", f"--pid={self.pid}", "-T", since, "-v", "threadtime",
                *self.filters]

    def spawn(self, since):
        # stderr is not read: an unread PIPE blocks adb once it fills.
        self.process = subprocess.Popen(self.command(since), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.thread = threading.Thread(target=self.pump, args=(self.process,), daemon=True)
        self.thread.start()

    def pump(self, process):
        for raw in iter(process.stdout.readline, b""):
            if not isinstance(raw, (bytes, bytearray)):
                return
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            with self.lock:
                if process is not self.process or self.closed:
                    return
                if self.skip[line] > 0:  # re-emitted by a restart at the boundary timestamp
                    self.skip[line] -= 1
                    continue
                self.handle.write(line + "\n")
                self.handle.flush()
                self.lines += 1
                self.last_line = time.perf_counter()
                match = THREADTIME.match(line)
                if match:
                    self.last_ts = match[1]

    def silent_s(self):
        return time.perf_counter() - self.last_line

    def supervise(self, app_alive):
        """Restart a dead or silent reader while the app lives. Returns the restart record or None."""
        if self.closed:
            return None
        now = time.perf_counter()
        exit_code = self.process.poll()
        silent = now - self.last_line
        if exit_code is None and silent <= self.STALL_S:
            return None
        if now - self.last_restart < self.MIN_RESTART_GAP_S or len(self.restarts) >= self.MAX_RESTARTS:
            return None
        if not app_alive():
            return None
        with self.lock:
            old, self.process = self.process, None
            try:
                old.kill()
            except OSError:
                pass
            self.handle.flush()
            since = self.last_ts or self.since
            # `-T since` re-emits lines stamped `since` (and the buffer headers): drop exactly those.
            self.skip = collections.Counter()
            for line in self.path.read_text(encoding="utf-8", errors="replace").splitlines():
                match = THREADTIME.match(line)
                if line.startswith(HEADER):
                    self.skip[line] = 1
                elif match and match[1] == self.last_ts:
                    self.skip[line] += 1
            self.spawn(since)
            self.last_restart = self.last_line = time.perf_counter()
        record = {"reason": f"reader exited with {exit_code}" if exit_code is not None
                  else f"no line for {silent:.0f} s while the app was alive",
                  "since": since, "restart": len(self.restarts) + 1}
        self.restarts.append(record)
        return record

    def stop(self):
        with self.lock:
            self.closed = True
            process = self.process
        if process is not None:
            try:
                process.kill()
            except OSError:
                pass
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                pass
            self.returncode = process.returncode
            if process.stdout:
                process.stdout.close()
        self.thread.join(timeout=2)
        with self.lock:
            self.handle.close()

    # Popen/handle compatibility for the runners' capture lists.
    def poll(self):
        return self.returncode if self.closed else None

    def terminate(self):
        self.stop()

    def kill(self):
        self.stop()

    def wait(self, timeout=None):
        return self.returncode

    def close(self):
        if not self.closed:
            self.stop()
