"""Tie long-running helper processes (adb logcat, recorders) to the life of this Python process.

A capture child that writes straight to a file outlives a killed runner and keeps appending to case
evidence (lane 12, 2026-10-15: two orphaned `adb logcat` readers ran for hours after their runner died).
On Windows, guarded children join one job object that kills its members when this process exits, however
it exits. Silent breakaway is allowed, so a process the child starts (an adb server launched by an adb
client) stays outside the job and survives for the other runners. Elsewhere this is a no-op: callers keep
their own terminate/kill cleanup.
"""
import ctypes
import os
import subprocess
import sys

_JOB = None
_POPEN = subprocess.Popen  # bound at import: tests patch subprocess.Popen with doubles
JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK = 0x1000
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9


class _IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_ulonglong) for name in (
        "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]


class _BasicLimits(ctypes.Structure):
    _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                ("LimitFlags", ctypes.c_uint32), ("MinimumWorkingSetSize", ctypes.c_size_t),
                ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", ctypes.c_uint32),
                ("Affinity", ctypes.c_size_t), ("PriorityClass", ctypes.c_uint32),
                ("SchedulingClass", ctypes.c_uint32)]


class _ExtendedLimits(ctypes.Structure):
    _fields_ = [("BasicLimitInformation", _BasicLimits), ("IoInfo", _IoCounters),
                ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]


def _job():
    global _JOB
    if _JOB is None:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateJobObjectW.restype = ctypes.c_void_p
        job = kernel32.CreateJobObjectW(None, None)
        if not job:
            raise OSError(ctypes.get_last_error(), "CreateJobObjectW failed")
        limits = _ExtendedLimits()
        limits.BasicLimitInformation.LimitFlags = (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                                                   | JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK)
        if not kernel32.SetInformationJobObject(ctypes.c_void_p(job), JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                                                ctypes.byref(limits), ctypes.sizeof(limits)):
            raise OSError(ctypes.get_last_error(), "SetInformationJobObject failed")
        _JOB = (kernel32, job)  # never closed: the handle closes, and the job ends, when this process exits
    return _JOB


def guard(process):
    """Kill `process` (a subprocess.Popen) when this Python process exits. Returns `process`."""
    if os.name != "nt" or not isinstance(process, _POPEN):  # test doubles stay unguarded
        return process
    try:
        kernel32, job = _job()
        if not kernel32.AssignProcessToJobObject(ctypes.c_void_p(job), ctypes.c_void_p(int(process._handle))):
            raise OSError(ctypes.get_last_error(), "AssignProcessToJobObject failed")
    except OSError as error:  # the runner's own cleanup still applies; say the guard is missing
        print(f"child_process_guard: process {process.pid} not guarded: {error}", file=sys.stderr)
    return process
