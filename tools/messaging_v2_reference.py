#!/usr/bin/env python3
"""Messaging v2 (phase 1a): executable reference and test-vector generator.

Normative companion of docs/PROTOCOL.md "Messaging v2". Host tooling, not app code. It builds, from the
document's byte layouts alone, the content `BSM2`, the attachment manifest, the receipt body, the segment
keys, a complete version 3 class 4 (bulk) bundle and the BSF1 version 2 messages, and writes the byte-exact
vectors that the Android JVM test (`MessagingV2VectorTest`) replays against the Kotlin implementation.

    python -B tools/messaging_v2_reference.py                 print the vectors as JSON
    python -B tools/messaging_v2_reference.py --kotlin FILE   write the Kotlin fixture used by the JVM tests

Needs the third-party `cryptography` package (for AES-256-GCM). Everything is deterministic.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import struct
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CHUNK_CIPHERTEXT = 8192
TAG = 16
CHUNK_PLAINTEXT = CHUNK_CIPHERTEXT - TAG
MAX_PADDED = 256 * CHUNK_PLAINTEXT
MIN_PADDED = 1024
SEGMENT_DATA = 255 * 4096
SEGMENT_CONTEXT = b"BluetoothVideoMesh/StoreForward/Segment/1"
BSM2_HEADER = 70


def padme(length: int) -> int:
    if length < 2:
        return length
    e = length.bit_length() - 1
    s = e.bit_length()
    mask = (1 << (e - s)) - 1
    return (length + mask) & ~mask


def padded_length(inner: int) -> int:
    return max(MIN_PADDED, min(MAX_PADDED, padme(inner + 4)))


def chunk_count(padded: int) -> int:
    return (padded + CHUNK_PLAINTEXT - 1) // CHUNK_PLAINTEXT


def segment_padded(data_length: int) -> int:
    inner = BSM2_HEADER + 18 + data_length
    return inner + 4 if data_length == SEGMENT_DATA else padded_length(inner)


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, prev, counter = b"", b"", 1
    while len(out) < length:
        prev = hmac.new(prk, prev + info + bytes([counter]), hashlib.sha256).digest()
        out += prev
        counter += 1
    return out[:length]


def segment_key(file_key: bytes, file_id: bytes, index: int) -> bytes:
    return hkdf(file_key, file_id, SEGMENT_CONTEXT + struct.pack(">H", index), 32)


def bsm2(kind: int, flags: int, created_ms: int, message_id: bytes, conversation: bytes, reply: bytes | None,
         disappear: int, body: bytes) -> bytes:
    return (b"BSM2" + bytes([kind, flags]) + struct.pack(">q", created_ms) + message_id + conversation +
            (reply or bytes(16)) + struct.pack(">i", disappear) + struct.pack(">i", len(body)) + body)


def manifest(file_id: bytes, presentation: int, total: int, sha: bytes, file_key: bytes, name: bytes, mime: bytes,
             width: int, height: int, duration_ms: int, thumbnail: bytes, inline: bytes | None,
             segments: list[tuple[bytes, int]]) -> bytes:
    out = file_id + bytes([presentation]) + struct.pack(">q", total) + bytes([len(segments)]) + sha
    out += (bytes(32) if inline is not None else file_key)
    out += bytes([len(name)]) + name + bytes([len(mime)]) + mime
    out += struct.pack(">HHI", width, height, duration_ms) + struct.pack(">H", len(thumbnail)) + thumbnail
    if inline is not None:
        return out + inline
    for bundle_id, chunks in segments:
        out += bundle_id + struct.pack(">H", chunks)
    return out


def receipt(status: int, hour: int, ids: list[bytes]) -> bytes:
    return bytes([status, len(ids)]) + struct.pack(">i", hour) + b"".join(ids)


def chunk_aad(index: int, count: int) -> bytes:
    return struct.pack(">HH", index, count)


def seal_bulk(inner: bytes, padded: int, key: bytes, pair_tag: bytes, created_hour: int, ttl_hours: int) -> dict:
    plaintext = struct.pack(">I", len(inner)) + inner + bytes(padded - 4 - len(inner))
    count = chunk_count(padded)
    chunks = []
    for index in range(count):
        piece = plaintext[index * CHUNK_PLAINTEXT:(index + 1) * CHUNK_PLAINTEXT]
        nonce = bytes(4) + struct.pack(">q", index)
        chunks.append(AESGCM(key).encrypt(nonce, piece, chunk_aad(index, count)))
    hashes = [hashlib.sha256(c).digest()[:16] for c in chunks]
    header = (b"BSB1" + bytes([3]) + struct.pack(">ii", created_hour, created_hour + ttl_hours) + bytes([4]) +
              struct.pack(">iHB", padded, count, 0) + pair_tag + b"".join(hashes))
    return {"header": header, "bundle_id": hashlib.sha256(header).digest(), "chunks": chunks}


def wire(message_type: int, body: bytes) -> bytes:
    return b"BSF1" + bytes([message_type]) + struct.pack(">I", len(body)) + body


def vectors() -> dict:
    ident = lambda start, n: bytes((start + i) & 0xFF for i in range(n))
    message_id, conversation, reply = ident(1, 16), ident(0x20, 16), ident(0x90, 16)
    created = 1_790_000_000_123
    created_hour = created // 3_600_000
    file_id, file_key = ident(0xA0, 16), ident(0, 32)
    hello_world = b"hello world"
    out: dict[str, str] = {}
    out["bsm2_text"] = bsm2(1, 1, created, message_id, conversation, None, 0, b"hello v2").hex()
    out["bsm2_text_reply"] = bsm2(1, 0, created, message_id, conversation, reply, 3600, "café".encode()).hex()
    out["manifest_inline"] = manifest(file_id, 2, len(hello_world), hashlib.sha256(hello_world).digest(), file_key,
                                      b"note.m4a", b"audio/mp4", 0, 0, 4200, b"", hello_world, []).hex()
    total = 2_000_000
    sizes = [SEGMENT_DATA, total - SEGMENT_DATA]
    refs = [(ident(0x40 + 0x10 * i, 32), chunk_count(segment_padded(size))) for i, size in enumerate(sizes)]
    out["manifest_segmented"] = manifest(file_id, 0, total, hashlib.sha256(b"two segments").digest(), file_key,
                                         b"archive.zip", b"application/zip", 0, 0, 0, b"", None, refs).hex()
    out["manifest_segment_chunks"] = ",".join(str(c) for _, c in refs)
    out["manifest_segment_ids"] = ",".join(r[0].hex() for r in refs)
    out["receipt"] = receipt(3, 123_456, [ident(0x10, 32), ident(0x50, 32)]).hex()
    for index in (0, 1, 61):
        out[f"segment_key_{index}"] = segment_key(file_key, file_id, index).hex()
    # A complete class 4 bundle: the second (short) segment of a file, 5,000 data bytes.
    data = bytes((i * 7) & 0xFF for i in range(5000))
    segment_body = file_id + struct.pack(">H", 1) + data
    inner = bsm2(9, 0, created, ident(0x70, 16), bytes(16), None, 0, segment_body)
    padded = segment_padded(len(data))
    bundle = seal_bulk(inner, padded, segment_key(file_key, file_id, 1), ident(0xC0, 8), created_hour, 336)
    out["bulk_created_ms"] = str(created_hour * 3_600_000)
    out["bulk_data_length"] = str(len(data))
    out["bulk_inner"] = inner.hex()
    out["bulk_padded"] = str(padded)
    out["bulk_header"] = bundle["header"].hex()
    out["bulk_bundle_id"] = bundle["bundle_id"].hex()
    out["bulk_chunks"] = ",".join(c.hex() for c in bundle["chunks"])
    out["padded_samples"] = ",".join(f"{n}:{padded_length(n)}" for n in (0, 1, 70, 900, 1020, 1021, 5000, 8074, 100000, 1_044_568))
    out["segment_padded_samples"] = ",".join(f"{n}:{segment_padded(n)}" for n in (1, 5000, 1_000_000, SEGMENT_DATA))
    digest = bytes(range(0x30, 0x38))
    out["hello_v1"] = wire(1, bytes([1]) + struct.pack(">II", 0x01020304, 0x0A0B0C0D) + digest).hex()
    out["hello_v2"] = wire(1, bytes([2]) + struct.pack(">II", 5, 6) + digest + bytes([3, 7])).hex()
    out["cancel"] = wire(11, ident(0, 32)).hex()
    out["ping"] = wire(12, b"").hex()
    out["done_idle"] = wire(8, bytes([5])).hex()
    out["refuse_prekey_gone"] = wire(6, ident(0, 32) + bytes([9])).hex()
    return out


def kotlin(vs: dict) -> str:
    lines = [
        "package com.example.bluetoothtest",
        "",
        "/**",
        " * GENERATED by tools/messaging_v2_reference.py --kotlin (do not edit). Byte-exact Messaging v2 vectors; tools/tests/",
        " * test_messaging_v2_reference.py fails when this file differs from the generator's output.",
        " */",
        "internal object MessagingV2Vectors {",
    ]
    for key, value in vs.items():
        name = key.upper()
        lines.append(f'    const val {name} = "{value}"')
    lines.append("}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--kotlin", metavar="FILE", help="write the Kotlin fixture")
    args = parser.parse_args(argv)
    vs = vectors()
    if args.kotlin:
        with open(args.kotlin, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(kotlin(vs))
    else:
        print(json.dumps(vs, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
