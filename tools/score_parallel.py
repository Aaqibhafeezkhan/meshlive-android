"""Shared worker-thread policy for the offline scorers (picture metrics and ffmpeg passes)."""

import os

THREADS_ENV = "BENCH_SCORE_THREADS"
DEFAULT_THREADS = 4


def score_threads(value=None):
    """Worker threads for pure per-item scoring; 1 keeps the original serial path.

    The default stays modest (4) so phone runners keep CPU. Thread count only changes wall time:
    each frame/metric runs the identical computation and results are joined in timeline order.
    """
    if value is None:
        value = os.environ.get(THREADS_ENV)
    try:
        count = int(value) if value not in (None, "") else DEFAULT_THREADS
    except ValueError:
        count = DEFAULT_THREADS
    return max(1, min(count, os.cpu_count() or 1))
