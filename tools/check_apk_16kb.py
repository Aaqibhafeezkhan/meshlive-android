"""Check an APK's native libraries for 16 KB page-size compatibility.

Per developer.android.com/guide/practices/page-sizes, every .so needs PT_LOAD segments aligned to at
least 16384, a GNU_RELRO end where (VirtAddr + MemSiz) % 0x4000 == 0, and, when stored uncompressed,
a data offset that is a multiple of 16384 in the zip (what `zipalign -c -P 16` checks). Compressed
libraries (legacy packaging) are extracted at install, so only their ELF layout is checked. Pure Python:
no NDK needed. Usage: python -B tools/check_apk_16kb.py app.apk [more.apk]   (exit 1 on any failure)
"""
import struct
import sys
import zipfile

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PAGE = 16384


def program_headers(data):
    """Yield (p_type, p_vaddr, p_memsz, p_align) for a little-endian ELF32/ELF64 image."""
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("not a little-endian ELF file")
    if data[4] == 2:
        phoff, = struct.unpack_from("<Q", data, 0x20)
        entsize, count = struct.unpack_from("<HH", data, 0x36)
        for i in range(count):
            p_type, _flags, _off, vaddr, _paddr, _filesz, memsz, align = struct.unpack_from(
                "<IIQQQQQQ", data, phoff + i * entsize)
            yield p_type, vaddr, memsz, align
    else:
        phoff, = struct.unpack_from("<I", data, 0x1C)
        entsize, count = struct.unpack_from("<HH", data, 0x2A)
        for i in range(count):
            p_type, _off, vaddr, _paddr, _filesz, memsz, _flags, align = struct.unpack_from(
                "<IIIIIIII", data, phoff + i * entsize)
            yield p_type, vaddr, memsz, align


def check_elf(data):
    """Return (load_alignments, relro_end_remainders, ok) for one shared object."""
    headers = list(program_headers(data))
    aligns = sorted({align for t, _v, _m, align in headers if t == PT_LOAD})
    remainders = [(vaddr + memsz) % PAGE for t, vaddr, memsz, _a in headers if t == PT_GNU_RELRO]
    return aligns, remainders, all(a >= PAGE for a in aligns) and all(r == 0 for r in remainders)


def check_apk(path):
    """Return a list of per-library result dicts."""
    results = []
    with zipfile.ZipFile(path) as archive, open(path, "rb") as raw:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            aligns, remainders, elf_ok = check_elf(archive.read(info))
            raw.seek(info.header_offset)
            header = raw.read(30)
            name_len, extra_len = struct.unpack_from("<HH", header, 26)
            data_offset = info.header_offset + 30 + name_len + extra_len
            stored = info.compress_type == zipfile.ZIP_STORED
            zip_ok = (not stored) or data_offset % PAGE == 0
            results.append({
                "library": info.filename, "loadAlign": aligns, "relroEndRemainder": remainders,
                "stored": stored, "zipOffsetRemainder": data_offset % PAGE,
                "elfOk": elf_ok, "zipOk": zip_ok, "ok": elf_ok and zip_ok,
            })
    return results


def main(argv):
    failed = False
    for apk in argv:
        results = check_apk(apk)
        failed |= not results or not all(r["ok"] for r in results)
        for r in results:
            print(f"{apk}: {r['library']} loadAlign={r['loadAlign']} relroEndRemainder={r['relroEndRemainder']} "
                  f"stored={r['stored']} zipOffsetRemainder={r['zipOffsetRemainder']} -> {'OK' if r['ok'] else 'FAIL'}")
        if not results:
            print(f"{apk}: no native libraries found")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
