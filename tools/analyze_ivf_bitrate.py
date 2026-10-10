import argparse
import json
import struct
import sys
from collections import deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark_layout import resolve


def read_ivf(path: Path) -> tuple[float, list[tuple[float, int]]]:
    path = resolve(path)
    with path.open("rb") as stream:
        header = stream.read(32)
        if len(header) != 32 or header[:4] != b"DKIF":
            raise ValueError(f"{path} is not an IVF file")
        rate, scale = struct.unpack_from("<II", header, 16)
        if rate <= 0 or scale <= 0:
            raise ValueError("Invalid IVF time base")
        packets = []
        while frame_header := stream.read(12):
            if len(frame_header) != 12:
                raise ValueError("Truncated IVF frame header")
            size, timestamp = struct.unpack("<IQ", frame_header)
            if len(stream.read(size)) != size:
                raise ValueError("Truncated IVF frame payload")
            packets.append((timestamp * scale / rate, size))
    return rate / scale, packets


def peak_window_kbps(packets: list[tuple[float, int]], window_seconds: float) -> float:
    window: deque[tuple[float, int]] = deque()
    window_bytes = 0
    peak_bytes = 0
    for timestamp, size in packets:
        window.append((timestamp, size))
        window_bytes += size
        while window and timestamp - window[0][0] >= window_seconds:
            window_bytes -= window.popleft()[1]
        peak_bytes = max(peak_bytes, window_bytes)
    return peak_bytes * 8 / window_seconds / 1_000


def max_backlog_bytes(packets: list[tuple[float, int]], drain_kbps: float) -> float:
    backlog_bits = 0.0
    max_bits = 0.0
    previous_timestamp = packets[0][0] if packets else 0.0
    for timestamp, size in packets:
        backlog_bits = max(
            0.0,
            backlog_bits - (timestamp - previous_timestamp) * drain_kbps * 1_000,
        )
        backlog_bits += size * 8
        max_bits = max(max_bits, backlog_bits)
        previous_timestamp = timestamp
    return max_bits / 8


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("encoded", type=Path)
    parser.add_argument("--drain-kbps", type=float, default=450)
    args = parser.parse_args()
    fps, packets = read_ivf(args.encoded)
    if not packets:
        raise ValueError("IVF contains no frames")
    duration_seconds = len(packets) / fps
    payload_bytes = sum(size for _, size in packets)
    result = {
        "frames": len(packets),
        "duration_seconds": duration_seconds,
        "average_payload_kbps": payload_bytes * 8 / duration_seconds / 1_000,
        "peak_100ms_kbps": peak_window_kbps(packets, 0.1),
        "peak_500ms_kbps": peak_window_kbps(packets, 0.5),
        "peak_1000ms_kbps": peak_window_kbps(packets, 1.0),
        "largest_frame_bytes": max(size for _, size in packets),
        "drain_kbps": args.drain_kbps,
        "max_nominal_backlog_bytes": max_backlog_bytes(packets, args.drain_kbps),
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
