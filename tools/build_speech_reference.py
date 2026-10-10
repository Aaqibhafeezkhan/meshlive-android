"""Build the speech-v1 reference audio from openly licensed recordings; offline once sources are cached.

Subcommands (none touches a phone):
  fetch   download the declared sources into a new directory and verify them (network)
  build   mix the cached sources into the reference WAVs plus a provenance sidecar (offline, deterministic)
  verify  rebuild into OS temporary scratch and compare with an existing build
  attach  copy a native raw-fixtures manifest, replacing reference_audio with the speech WAV whose length
          equals each fixture's exact raw-video loop (whole samples); fixtures without a match are excluded

The output is PCM16 mono 48000 Hz with the plain 44-byte WAV header the app's ReferencePcmInput requires.
There is no randomness except fixed-seed PCG64 bits; Gaussian values use Box-Muller. The final WAV hashes recorded
in the provenance are authoritative; floating-point libraries could in principle change a rounded sample, which
`verify` would report instead of hiding.
"""

import argparse
import hashlib
import io
import json
import math
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import wave

import numpy as np
from scipy.signal import resample_poly

from benchmark_layout import ROOT, output_path, resolve as resolve_input

RATE = 48000
SEED = 20261009
FADE_MS = 10
SPEECH_ACTIVE_DBFS = -26.0   # active-RMS-v1 level of every speech source before mixing
MUSIC_RMS_DBFS = -24.0
ROOM_TONE_DBFS = -65.0       # RMS of the low-level pink noise used for "silence" segments
PEAK_LIMIT = 0.9             # refuse to build rather than clip silently
SCHEMA = "benchmark-speech-reference-v1"
DEFAULT_SOURCES = ROOT / "benchmarks" / "inputs" / "speech-v1" / "sources"

LIBRISPEECH = {
    "url": "https://www.openslr.org/resources/12/test-clean.tar.gz",
    "bytes": 346663984,
    "sha256": "39fde525e59672dc6d1551919b1478f724438a95aa55f874b576be21967e6c23",
    "openslr_md5": "32fa31d27d2e1cad72775fee3f4849a9",
    "licence": "CC BY 4.0",
    "licence_url": "https://creativecommons.org/licenses/by/4.0/",
    "attribution": "LibriSpeech (c) 2014 by Vassil Panayotov; V. Panayotov, G. Chen, D. Povey, S. Khudanpur, "
                   "'LibriSpeech: an ASR corpus based on public domain audio books', ICASSP 2015. Readers are LibriVox volunteers.",
    "metadata": "LibriSpeech/SPEAKERS.TXT (sex field) from https://www.openslr.org/resources/12/raw-metadata.tar.gz",
}
LIBRIVOX_LICENCE = {
    "licence": "Public domain (LibriVox recordings are dedicated to the public domain; Internet Archive licenseurl "
               "creativecommons.org/licenses/publicdomain/ or /publicdomain/mark/1.0/)",
    "licence_url": "https://librivox.org/pages/public-domain/",
}


def _ls(utterance, sex, transcript, role):
    speaker, chapter, number = utterance.split("-")
    return {"id": "ls-" + utterance, "file": f"ls-{utterance}.flac", "kind": "librispeech", "language": "en",
            "speaker_id": speaker, "speaker_sex": sex, "role": role, "transcript": transcript,
            "fetch": {"type": "tar_member", "member": f"LibriSpeech/test-clean/{speaker}/{chapter}/{utterance}.flac"},
            **{k: LIBRISPEECH[k] for k in ("licence", "licence_url", "attribution")},
            "origin_url": LIBRISPEECH["url"]}


def _ia(identifier, name, byte_range, language, sex, role, window, sex_basis, licenceurl, raw_sha256, extra=None):
    return {"file": None, "kind": "librivox", "language": language, "speaker_sex": sex, "speaker_sex_basis": sex_basis,
            "role": role, "origin_url": f"https://archive.org/download/{identifier}/{name}",
            "item_url": f"https://archive.org/details/{identifier}", "archive_licenseurl": licenceurl,
            "fetch": {"type": "http_range_mp3", "range": byte_range, "range_sha256": raw_sha256, "window_s": window},
            **LIBRIVOX_LICENCE, **(extra or {})}


def _sources():
    rows = [
        _ls("1995-1836-0001", "F", "AT LAST THE COTTON COMBINE WAS TO ALL APPEARANCES AN ASSURED", "clean voice, quick-loop clean voice"),
        _ls("2300-131720-0036", "M", "THE METER CONTINUED IN GENERAL SERVICE DURING EIGHTEEN NINET", "clean voice; double-talk is not used"),
        _ls("2961-960-0012", "F", "MANY IF NOT ALL THE ELEMENTS OF THE PRE SOCRATIC PHILOSOPHY", "loudness step"),
        _ls("7127-75947-0029", "M", "THE YOUNG GIRLS HAD INDEED MADE THEMSELVES SMALL INDEED INVI", "loudness step"),
        _ls("3729-6852-0036", "F", "WHEN THE KING COMES TO PARIS EVERYBODY CALLS OUT VIVE LE ROI", "loudness step"),
        _ls("4077-13754-0004", "M", "ALREADY A NORTH AND A SOUTH WERE TALKED OF WHY NOT SET UP AL", "loudness step"),
        _ls("8463-287645-0003", "F", "OF THIS PARTY EDWARD A BOY OF SEVENTEEN CALLED FORTH MUCH SY", "loudness step"),
        _ls("3570-5694-0001", "F", "THE UTILITY OF CONSUMPTION AS AN EVIDENCE OF WEALTH IS TO BE", "double-talk voice A"),
        _ls("1188-133604-0001", "M", "THEY UNITE EVERY QUALITY AND SOMETIMES YOU WILL FIND ME REFE", "double-talk voice B"),
        _ls("4446-2271-0001", "F", "HE HAD PRECONCEIVED IDEAS ABOUT EVERYTHING AND HIS IDEA ABOU", "speech over babble"),
        _ls("5105-28241-0000", "M", "HER SEA GOING QUALITIES WERE EXCELLENT AND WOULD HAVE AMPLY", "speech over noise"),
        _ls("4507-16021-0022", "F", "THERE IS THE SLANG OF THE AFFECTED LADY AS WELL AS OF THE PR", "babble talker"),
        _ls("8555-292519-0005", "F", "WHILE THE OLD GOLD AND THE MARBLE STAYS FOREVER GLEAMING ITS", "babble talker"),
        _ls("61-70968-0011", "M", "HE GAVE WAY TO THE OTHERS VERY READILY AND RETREATED UNPERCE", "babble talker"),
        _ls("7176-92135-0018", "M", "IN THE MODERN WELL CONSTRUCTED PLAY HE SIMPLY RINGS UP AN IM", "babble talker"),
    ]
    de = _ia("kleinzaches_1309_librivox", "kleinzaches_03_hoffmann.mp3", [0, 500000], "de", "M", "German clean voice",
             [2.25, 6.37], "median f0 about 95 Hz (librosa pyin); LibriVox does not publish reader sex in the item",
             "http://creativecommons.org/publicdomain/mark/1.0/", "3e7d6e80f9f41905381861a227adeb140c022afeca16c5558a2fae3336ff556e",
             {"title": "Klein Zaches, genannt Zinnober (E.T.A. Hoffmann), chapter 3, LibriVox"})
    de.update(id="lv-de-kleinzaches-03", file="lv-de-kleinzaches-03.flac")
    es = _ia("don_quijote_vol1_0706_librivox", "quijote_vol1_03_cervantes.mp3", [0, 500000], "es", "F", "Spanish clean voice",
             [14.5, 19.62], "median f0 about 210 Hz (librosa pyin); LibriVox does not publish reader sex in the item",
             "http://creativecommons.org/licenses/publicdomain/", "6b6ab576d9f239c0d5fd6a719325106ec27ad46a5c65a3fc0ab196d0bdd6efa3",
             {"title": "Don Quijote, Volume 1 (Cervantes), chapter 3, LibriVox"})
    es.update(id="lv-es-quijote-03", file="lv-es-quijote-03.flac")
    music = _ia("OpenGoldbergVariations", "Kimiko Ishizaka - J.S. Bach- -Open- Goldberg Variations, BWV 988 (Piano) - 02 Variatio 1 a 1 Clav..mp3".replace(" ", "%20").replace(",", "%2C").replace("(", "%28").replace(")", "%29"),
                [0, 799999], "-", "-", "solo piano music", [4.0, 11.0], "n/a (instrumental)",
                "http://creativecommons.org/publicdomain/zero/1.0/", "1f7221105a54dca6b252e3f7e18b1d68da8645ed2ffd2bc8830b519fce7e4793",
                {"title": "J.S. Bach, Goldberg Variations BWV 988, Variatio 1, performed by Kimiko Ishizaka (The Open Goldberg Variations)",
                 "licence": "CC0 1.0 Universal (public domain dedication)",
                 "licence_url": "https://creativecommons.org/publicdomain/zero/1.0/"})
    music.update(id="music-goldberg-var1", file="music-goldberg-var1.flac", kind="music")
    return {row["id"]: row for row in rows + [de, es, music]}


# SHA256 of each cached lossless source file; the build refuses any other bytes.
FLAC_SHA256 = {
    "ls-1188-133604-0001": "3022b442af790536b60f18abe88b5376418a208f2774547b8d9700d4ef00bb03",
    "ls-1995-1836-0001": "d8b59effae19763adf3bdf9448aa8bb91e63ac7f8f07dc5aeea0cc2b39e83785",
    "ls-2300-131720-0036": "b194c182b01d7785f4687ac02e4fbb03f4fc74e16af57534923f11036a72a379",
    "ls-2961-960-0012": "f826af04b7df4abba8fa174409ac319b5792d787066060042653cf38fa877f4c",
    "ls-3570-5694-0001": "83d42febda01d467c35c0b90dc7bb5d5d80b18ed95d65748a831afca53f2e3cd",
    "ls-3729-6852-0036": "3a90450a2b4bf0b20a380212655a2b15f63d2ce07837d53d91f32f1ba7c77229",
    "ls-4077-13754-0004": "ed453fc3dc0b7188a1eac2c97ac977180685baf2766626bc4d381545c29ca99d",
    "ls-4446-2271-0001": "0469768ab489b6855389d9ac8532b7dde006891820d8137913d7789965b07c89",
    "ls-4507-16021-0022": "584a65245392a2f94850a9973a2f17b62c40970d0cb749ebfe23ac0a9001d610",
    "ls-5105-28241-0000": "f706e407dd4c7716e6e7b8cb9c359e37201122a802f47684e9bbd083f2d29297",
    "ls-61-70968-0011": "670ebf7f5252665a373e35486d5604c916f4d4ca68441009e458a23f91f60a22",
    "ls-7127-75947-0029": "771b8d91ed3b60e853b370b2e2633dbb418832fcb1c27be13037031d8c132918",
    "ls-7176-92135-0018": "383cfcbb7e5e785ba590aa3acfe038ad2bc57886abd5c15d43b1732b7735f6a6",
    "ls-8463-287645-0003": "224879b9c8804034671dc7f765e8a301d03be9a407727a82458dc3349a0de6c4",
    "ls-8555-292519-0005": "449adbd1a60f05b3841f3b76c8544c7fcd5a91f2746498523173b341894b57a4",
    "lv-de-kleinzaches-03": "5ab6166a9ccdeed525c62933e3f8535fa3b87119d152c8f250cf2488bfb1919a",
    "lv-es-quijote-03": "66df34771be2a2612c5f45c0458517f021bebbf581c79e21a431107bdd400e5b",
    "music-goldberg-var1": "ce9ae6e51899154cf35175c3791849ded42e2106ea082bd3b313306945c1cc0e",
}

SOURCES = _sources()
for _name, _row in SOURCES.items():
    _row["sha256"] = FLAC_SHA256[_name]

# Timeline specs. Excerpt times are integer milliseconds of the named source file (at its own start).
def _clip(source, start_ms, end_ms):
    return {"source": source, "start_ms": start_ms, "end_ms": end_ms}


def _babble():
    return [{"source": "ls-4507-16021-0022", "start_ms": 100}, {"source": "ls-8555-292519-0005", "start_ms": 500},
            {"source": "ls-61-70968-0011", "start_ms": 100}, {"source": "ls-7176-92135-0018", "start_ms": 600}]


MASTER = [
    ("guard_start", "digital_silence", {"ms": 500}),
    ("clean_f_en", "speech_clean", {"clip": _clip("ls-1995-1836-0001", 195, 5755)}),
    ("silence_1s", "room_tone", {"ms": 1000}),
    ("clean_m_en", "speech_clean", {"clip": _clip("ls-2300-131720-0036", 205, 6900)}),
    ("silence_0_5s_a", "room_tone", {"ms": 500}),
    ("step_1", "level_step", {"clip": _clip("ls-2961-960-0012", 1220, 2825), "peak_dbfs": -30}),
    ("step_gap_1", "room_tone", {"ms": 200}),
    ("step_2", "level_step", {"clip": _clip("ls-7127-75947-0029", 350, 1705), "peak_dbfs": -24}),
    ("step_gap_2", "room_tone", {"ms": 200}),
    ("step_3", "level_step", {"clip": _clip("ls-3729-6852-0036", 380, 2000), "peak_dbfs": -18}),
    ("step_gap_3", "room_tone", {"ms": 200}),
    ("step_4", "level_step", {"clip": _clip("ls-4077-13754-0004", 15, 1920), "peak_dbfs": -12}),
    ("step_gap_4", "room_tone", {"ms": 200}),
    ("step_5", "level_step", {"clip": _clip("ls-8463-287645-0003", 460, 2345), "peak_dbfs": -6}),
    ("silence_3s", "room_tone", {"ms": 3000}),
    ("clean_m_de", "speech_clean", {"clip": _clip("lv-de-kleinzaches-03", 0, 4120)}),
    ("silence_0_5s_b", "room_tone", {"ms": 500}),
    ("clean_f_es", "speech_clean", {"clip": _clip("lv-es-quijote-03", 0, 5120)}),
    ("silence_0_5s_c", "room_tone", {"ms": 500}),
    ("double_talk", "double_talk", {"a": _clip("ls-3570-5694-0001", 415, 5390), "b": _clip("ls-1188-133604-0001", 420, 4965), "b_offset_ms": 1000}),
    ("silence_0_5s_d", "room_tone", {"ms": 500}),
    ("babble_snr10", "speech_babble", {"clip": _clip("ls-4446-2271-0001", 345, 5590), "snr_db": 10, "talkers": _babble()}),
    ("silence_0_5s_e", "room_tone", {"ms": 500}),
    ("noise_snr15", "speech_noise", {"clip": _clip("ls-5105-28241-0000", 250, 4155), "snr_db": 15, "seed_offset": 101}),
    ("silence_0_5s_f", "room_tone", {"ms": 500}),
    ("music_piano", "music", {"clip": _clip("music-goldberg-var1", 0, 6500)}),
    ("guard_end", "digital_silence", {"fill": True}),
]

QUICK = [
    ("guard_start", "digital_silence", {"ms": 250}),
    ("clean_f_en", "speech_clean", {"clip": _clip("ls-1995-1836-0001", 195, 3100)}),
    ("silence_0_5s_a", "room_tone", {"ms": 500}),
    ("step_1", "level_step", {"clip": _clip("ls-2961-960-0012", 1220, 2475), "peak_dbfs": -30}),
    ("step_gap_1", "room_tone", {"ms": 100}),
    ("step_2", "level_step", {"clip": _clip("ls-7127-75947-0029", 350, 1365), "peak_dbfs": -24}),
    ("step_gap_2", "room_tone", {"ms": 100}),
    ("step_3", "level_step", {"clip": _clip("ls-3729-6852-0036", 380, 1230), "peak_dbfs": -18}),
    ("step_gap_3", "room_tone", {"ms": 100}),
    ("step_4", "level_step", {"clip": _clip("ls-4077-13754-0004", 15, 1150), "peak_dbfs": -12}),
    ("step_gap_4", "room_tone", {"ms": 100}),
    ("step_5", "level_step", {"clip": _clip("ls-8463-287645-0003", 460, 1580), "peak_dbfs": -6}),
    ("silence_1s", "room_tone", {"ms": 1000}),
    ("double_talk", "double_talk", {"a": _clip("ls-3570-5694-0001", 415, 3400), "b": _clip("ls-1188-133604-0001", 420, 2900), "b_offset_ms": 500}),
    ("silence_0_5s_b", "room_tone", {"ms": 500}),
    ("babble_snr10", "speech_babble", {"clip": _clip("ls-4446-2271-0001", 345, 3000), "snr_db": 10, "talkers": _babble()}),
    ("silence_0_5s_c", "room_tone", {"ms": 500}),
    ("noise_snr15", "speech_noise", {"clip": _clip("ls-5105-28241-0000", 250, 2900), "snr_db": 15, "seed_offset": 101}),
    ("guard_end", "digital_silence", {"fill": True}),
]

VARIANTS = {"speech-v1": {"duration_ms": 60000, "timeline": MASTER},
            "speech-v1-20s": {"duration_ms": 20000, "timeline": QUICK}}
SCORING_CLASS = {"digital_silence": "silence", "room_tone": "silence", "speech_clean": "speech", "level_step": "speech",
                 "double_talk": "speech", "speech_babble": "speech", "speech_noise": "speech", "music": "music"}
LOOP_RULE = ("The app plays the reference WAV as an endless loop and the runner demands WAV samples == raw-video loop samples "
             "(frames * 48000 / fps, whole samples). Pair the 60 s variant with 60 s native fixtures and speech-v1-20s with 20 s "
             "fixtures; no padding, trimming or tiling is done, and other lengths have no speech reference.")


# ---------------------------------------------------------------- helpers
def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def recipe_document():
    return {"schema": SCHEMA, "rate": RATE, "seed": SEED, "fade_ms": FADE_MS, "speech_active_dbfs": SPEECH_ACTIVE_DBFS,
            "music_rms_dbfs": MUSIC_RMS_DBFS, "room_tone_dbfs": ROOM_TONE_DBFS,
            "variants": {name: {"duration_ms": v["duration_ms"], "timeline": [[i, k, p] for i, k, p in v["timeline"]]}
                         for name, v in VARIANTS.items()},
            "sources": {name: {k: v for k, v in row.items() if k != "bytes"} for name, row in sorted(SOURCES.items())}}


def read_flac(path):
    import soundfile
    data, rate = soundfile.read(str(path), dtype="int16", always_2d=True)
    if data.shape[1] != 1:
        raise ValueError(f"{path}: cached sources are mono")
    return data[:, 0], rate


def to_rate(samples, rate):
    """Float64 samples in [-1, 1) at 48 kHz with a fixed polyphase Kaiser resampler."""
    x = samples.astype(np.float64) / 32768.0
    if rate == RATE:
        return x
    divisor = math.gcd(RATE, rate)
    return resample_poly(x, RATE // divisor, rate // divisor)


def db(value):
    return 20 * math.log10(max(value, 1e-12))


def active_rms_dbfs(x):
    """active-RMS-v1: mean power of the non-overlapping 20 ms frames within 30 dB of the loudest frame."""
    frame = RATE // 50
    count = len(x) // frame
    if count == 0:
        return -120.0
    power = (x[:count * frame].reshape(count, frame) ** 2).mean(axis=1)
    active = power[power >= power.max() * 1e-3]
    return 10 * math.log10(max(float(active.mean()), 1e-24))


def rms_dbfs(x):
    return 10 * math.log10(max(float(np.mean(x ** 2)), 1e-24)) if len(x) else -120.0


def peak_dbfs(x):
    return db(float(np.max(np.abs(x)))) if len(x) else -120.0


def fade(x, ms=FADE_MS):
    n = min(RATE * ms // 1000, len(x) // 2)
    if n:
        ramp = 0.5 - 0.5 * np.cos(np.pi * (np.arange(n) + 0.5) / n)
        x = x.copy()
        x[:n] *= ramp
        x[-n:] *= ramp[::-1]
    return x


def gaussian(seed, count):
    raw = np.random.PCG64(seed).random_raw(2 * ((count + 1) // 2))
    uniform = ((raw >> np.uint64(11)).astype(np.float64) + 0.5) * 2.0 ** -53
    radius = np.sqrt(-2.0 * np.log(uniform[0::2]))
    angle = 2.0 * np.pi * uniform[1::2]
    out = np.empty(2 * len(radius))
    out[0::2] = radius * np.cos(angle)
    out[1::2] = radius * np.sin(angle)
    return out[:count]


def pink(seed, count, rms_db):
    white = gaussian(seed, count)
    spectrum = np.fft.rfft(white)
    freq = np.arange(len(spectrum), dtype=np.float64)
    freq[0] = 1.0
    shaped = np.fft.irfft(spectrum / np.sqrt(freq), n=count)
    shaped -= shaped.mean()
    return shaped * (10 ** (rms_db / 20) / math.sqrt(float(np.mean(shaped ** 2))))


class Sources:
    """Verified cached sources at 48 kHz, level-normalised once (speech active-RMS, music RMS)."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.cache = {}
        self.files = {}

    def get(self, name):
        if name not in self.cache:
            row = SOURCES[name]
            path = self.directory / row["file"]
            expected = row.get("sha256")
            if not expected or sha256_file(path) != expected:
                raise ValueError(f"Source {name} is missing or differs from its recorded SHA256: {path}")
            samples, rate = read_flac(path)
            x = to_rate(samples, rate)
            if row["kind"] == "music":
                x = x * 10 ** ((MUSIC_RMS_DBFS - rms_dbfs(x)) / 20)
            else:
                x = x * 10 ** ((SPEECH_ACTIVE_DBFS - active_rms_dbfs(x)) / 20)
            self.cache[name] = x
            self.files[name] = {"file": row["file"], "sha256": expected, "bytes": path.stat().st_size,
                                "native_rate": rate, "native_samples": int(len(samples))}
        return self.cache[name]

    def clip(self, spec):
        x = self.get(spec["source"])
        a, b = spec["start_ms"] * RATE // 1000, spec["end_ms"] * RATE // 1000
        if not 0 <= a < b <= len(x):
            raise ValueError(f"Clip {spec} lies outside its {len(x) / RATE:.3f} s source")
        return x[a:b]


def render(kind, params, sources, index, seed):
    """Return one segment's float samples and the metadata that explains it."""
    info = {}
    if kind == "digital_silence":
        return np.zeros(params["ms"] * RATE // 1000), info
    if kind == "room_tone":
        count = params["ms"] * RATE // 1000
        info["room_tone_rms_dbfs"] = ROOM_TONE_DBFS
        return pink(seed + 7919 * (index + 1), count, ROOM_TONE_DBFS), info
    if kind == "speech_clean":
        info["sources"] = [params["clip"]["source"]]
        return fade(sources.clip(params["clip"])), info
    if kind == "level_step":
        x = fade(sources.clip(params["clip"]))
        info.update(sources=[params["clip"]["source"]], target_peak_dbfs=params["peak_dbfs"])
        return x * (10 ** (params["peak_dbfs"] / 20) / float(np.max(np.abs(x)))), info
    if kind == "double_talk":
        a, b = fade(sources.clip(params["a"])), fade(sources.clip(params["b"]))
        offset = params["b_offset_ms"] * RATE // 1000
        out = np.zeros(max(len(a), offset + len(b)))
        out[:len(a)] += a
        out[offset:offset + len(b)] += b
        info.update(sources=[params["a"]["source"], params["b"]["source"]], b_offset_ms=params["b_offset_ms"])
        return out, info
    if kind in ("speech_babble", "speech_noise"):
        speech = fade(sources.clip(params["clip"]))
        if kind == "speech_babble":
            noise = np.zeros(len(speech))
            for talker in params["talkers"]:
                a = talker["start_ms"] * RATE // 1000
                piece = sources.get(talker["source"])[a:a + len(speech)]
                if len(piece) != len(speech):
                    raise ValueError(f"Babble talker {talker['source']} is shorter than the foreground clip")
                noise += fade(piece, 50)
            info["babble_talkers"] = [t["source"] for t in params["talkers"]]
        else:
            noise = pink(seed + params["seed_offset"], len(speech), 0.0)
            info["noise"] = "pink (1/f power), fixed-seed PCG64"
        target = active_rms_dbfs(speech) - params["snr_db"]
        noise = noise * 10 ** ((target - rms_dbfs(noise)) / 20)
        info.update(sources=[params["clip"]["source"]], snr_db=params["snr_db"],
                    snr_definition="foreground active-RMS-v1 level minus whole-segment RMS of the added noise/babble")
        return speech + noise, info
    if kind == "music":
        info["sources"] = [params["clip"]["source"]]
        return fade(sources.clip(params["clip"]), 50), info
    raise ValueError(f"Unknown segment kind {kind}")


def build_variant(name, sources):
    spec = VARIANTS[name]
    total = spec["duration_ms"] * RATE // 1000
    pieces, segments, cursor = [], [], 0
    fixed = 0
    timeline = spec["timeline"]
    for segment_id, kind, params in timeline:
        if not params.get("fill"):
            fixed += len(render(kind, params, sources, 0, SEED)[0])
    remainder = total - fixed
    for index, (segment_id, kind, params) in enumerate(timeline):
        if params.get("fill"):
            if remainder < RATE // 4:
                raise ValueError(f"{name}: timeline leaves {remainder / RATE:.3f} s for {segment_id}; shorten an earlier segment")
            x, info = np.zeros(remainder), {}
        else:
            x, info = render(kind, params, sources, index, SEED)
        pieces.append(x)
        segments.append({"id": segment_id, "kind": kind, "scoring": SCORING_CLASS[kind],
                         "start_sample": cursor, "end_sample": cursor + len(x),
                         "peak_dbfs": round(peak_dbfs(x), 2), "rms_dbfs": round(rms_dbfs(x), 2),
                         "active_rms_dbfs": round(active_rms_dbfs(x), 2), **info})
        language = [SOURCES[s]["language"] for s in info.get("sources", [])]
        if language and kind != "music":
            segments[-1]["language"] = language[0]
        if kind == "level_step":
            segments[-1]["group"] = "loudness_steps"
        cursor += len(x)
    mix = np.concatenate(pieces)
    if len(mix) != total:
        raise ValueError(f"{name}: assembled {len(mix)} samples instead of {total}")
    if float(np.max(np.abs(mix))) > PEAK_LIMIT:
        raise ValueError(f"{name}: mix peak {peak_dbfs(mix):.2f} dBFS exceeds the {db(PEAK_LIMIT):.2f} dBFS limit; no silent clipping")
    pcm = np.round(mix * 32768.0).astype("<i2")
    return pcm, segments, fixed, remainder


def write_wav(path, pcm):
    with wave.open(str(path), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(RATE)
        target.writeframes(pcm.astype("<i2").tobytes())
    if Path(path).stat().st_size != 44 + 2 * len(pcm):
        raise ValueError("WAV header is not the plain 44-byte form the app requires")


def build(sources_directory, output):
    sources = Sources(sources_directory)
    output = Path(output)
    targets = [f"{name}.wav" for name in VARIANTS] + ["speech-v1.provenance.json"]
    if any((output / name).exists() for name in targets):
        raise FileExistsError(f"{output} already holds a speech build; use a fresh directory")
    output.mkdir(parents=True, exist_ok=True)
    variants = {}
    for name in VARIANTS:
        pcm, segments, fixed, remainder = build_variant(name, sources)
        path = output / f"{name}.wav"
        write_wav(path, pcm)
        variants[name] = {"file": path.name, "sha256": sha256_file(path), "bytes": path.stat().st_size,
                          "sample_rate": RATE, "channels": 1, "format": "PCM16", "sample_count": int(len(pcm)),
                          "duration_s": len(pcm) / RATE, "sample_peak_dbfs": round(peak_dbfs(pcm / 32768.0), 2),
                          "rms_dbfs": round(rms_dbfs(pcm / 32768.0), 2),
                          "active_rms_dbfs": round(active_rms_dbfs(pcm / 32768.0), 2),
                          "fixed_samples_before_fill": fixed, "final_digital_silence_samples": remainder,
                          "segments": segments}
    import scipy
    import soundfile
    provenance = {
        "schema": SCHEMA, "name": "speech-v1", "state": "NOT RUN",
        "purpose": "Speech-like reference for raw-input call/stream cases and decoded-PCM quality scoring",
        "recipe_sha256": hashlib.sha256(canonical(recipe_document())).hexdigest(), "recipe": recipe_document(),
        "tool": "tools/build_speech_reference.py",
        "environment": {"python": sys.version.split()[0], "numpy": np.__version__, "scipy": scipy.__version__,
                        "soundfile": soundfile.__version__},
        "determinism": "No randomness beyond fixed-seed PCG64 bits; sources are hash-checked cached lossless FLAC; resampling is "
                       "scipy.signal.resample_poly; `verify` rebuilds and compares every output hash.",
        "loop_rule": LOOP_RULE, "level_definitions": {
            "active-RMS-v1": "mean power of non-overlapping 20 ms frames within 30 dB of the loudest frame (dBFS, 48 kHz)",
            "step peak": "sample peak of the faded excerpt in dBFS", "silence": "digital zero (guards) or -65 dBFS RMS pink room tone"},
        "variants": variants, "sources": {},
        "librispeech_archive": LIBRISPEECH,
        "scope": "Generated reference input only. It supplies the actual Opus input; microphone, speaker, acoustic and A/V-cue "
                 "timing are not exercised, and this file is not a pass.",
    }
    for name, used in sorted(sources.files.items()):
        provenance["sources"][name] = {**{k: v for k, v in SOURCES[name].items()}, **used}
    unused = sorted(set(SOURCES) - set(sources.files))
    if unused:
        raise ValueError(f"Recipe sources are not used by any variant: {unused}")
    with (output / "speech-v1.provenance.json").open("w", encoding="utf-8", newline="\n") as target:
        target.write(json.dumps(provenance, indent=2, allow_nan=False) + "\n")
    return provenance


def verify(sources_directory, build_directory):
    build_directory = resolve_input(build_directory)
    recorded = json.loads((build_directory / "speech-v1.provenance.json").read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="speech-verify-") as scratch:
        rebuilt = build(sources_directory, Path(scratch) / "rebuild")
    report = {"state": "VERIFIED", "variants": {}}
    if rebuilt["recipe_sha256"] != recorded.get("recipe_sha256"):
        report["state"] = "RECIPE DIFFERS"
    for name, row in rebuilt["variants"].items():
        recorded_row = recorded["variants"].get(name, {})
        path = build_directory / row["file"]
        actual = sha256_file(path) if path.exists() else None
        report["variants"][name] = {"rebuilt_sha256": row["sha256"], "recorded_sha256": recorded_row.get("sha256"),
                                    "file_sha256": actual, "match": row["sha256"] == recorded_row.get("sha256") == actual}
        if not report["variants"][name]["match"]:
            report["state"] = "MISMATCH"
    return report


# ---------------------------------------------------------------- fetch
def _open(url, headers=None):
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=120)


class _Hashing(io.RawIOBase):
    def __init__(self, response):
        self.response, self.digest, self.count = response, hashlib.sha256(), 0

    def readable(self):
        return True

    def readinto(self, buffer):
        data = self.response.read(len(buffer))
        buffer[:len(data)] = data
        self.digest.update(data)
        self.count += len(data)
        return len(data)


def fetch(output, urlopen=_open):
    """Stream the LibriSpeech archive once (kept out of the repo), extract only the declared members, decode the
    LibriVox/music byte ranges to mono 16-bit FLAC windows, and print the hashes that belong in the recipe."""
    import soundfile
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    members = {row["fetch"]["member"]: name for name, row in SOURCES.items() if row["fetch"]["type"] == "tar_member"}
    found, hashes = {}, {}
    stream = _Hashing(urlopen(LIBRISPEECH["url"]))
    with tarfile.open(fileobj=io.BufferedReader(stream, 1 << 20), mode="r|gz") as archive:
        for member in archive:
            if member.isfile() and member.name in members:
                data = archive.extractfile(member).read()
                name = members[member.name]
                (output / SOURCES[name]["file"]).write_bytes(data)
                found[name] = hashlib.sha256(data).hexdigest()
    while stream.readinto(bytearray(1 << 20)):
        pass
    if (stream.count, stream.digest.hexdigest()) != (LIBRISPEECH["bytes"], LIBRISPEECH["sha256"]):
        raise ValueError(f"LibriSpeech archive differs from the recorded size/SHA256: {stream.count} {stream.digest.hexdigest()}")
    missing = sorted(set(members.values()) - set(found))
    if missing:
        raise ValueError(f"Archive lacks declared members: {missing}")
    hashes.update(found)
    for name, row in SOURCES.items():
        spec = row["fetch"]
        if spec["type"] != "http_range_mp3":
            continue
        first, last = spec["range"]
        data = urlopen(row["origin_url"], {"Range": f"bytes={first}-{last}"}).read()
        raw = hashlib.sha256(data).hexdigest()
        if not spec["range_sha256"].startswith("RANGE_") and raw != spec["range_sha256"]:
            raise ValueError(f"{name}: downloaded byte range differs from the recorded SHA256 ({raw})")
        samples, rate = soundfile.read(io.BytesIO(data), dtype="float64", always_2d=True)
        mono = samples.mean(axis=1)
        a, b = (int(round(t * rate)) for t in spec["window_s"])
        window = np.round(np.clip(mono[a:b], -1.0, 32767 / 32768) * 32768.0).astype("<i2")
        soundfile.write(str(output / row["file"]), window, rate, format="FLAC", subtype="PCM_16")
        hashes[name] = sha256_file(output / row["file"])
        hashes[name + ":range_sha256"] = raw
    return hashes


# ---------------------------------------------------------------- attach
def attach(fixtures_path, speech_directory, output):
    """Copy a native raw-fixtures manifest, swapping in speech references whose whole-sample length equals the video loop."""
    fixtures_path = resolve_input(fixtures_path)
    speech_directory = Path(speech_directory)
    provenance_path = speech_directory / "speech-v1.provenance.json"
    provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    by_length = {}
    for name, row in provenance["variants"].items():
        path = speech_directory / row["file"]
        if sha256_file(path) != row["sha256"]:
            raise ValueError(f"{path} differs from its recorded SHA256")
        by_length[row["sample_count"]] = (name, path, row["sha256"])
    manifest = json.loads(fixtures_path.read_text(encoding="utf-8-sig"))
    if manifest.get("schema_version") != 1 or not isinstance(manifest.get("fixtures"), list):
        raise ValueError("Raw fixture manifest needs schema_version 1 and fixtures")
    output = output_path(output, "inputs")
    if output.exists():
        raise FileExistsError(output)
    kept, excluded = [], []
    for fixture in manifest["fixtures"]:
        numerator = fixture["frame_count"] * RATE
        fps = fixture["fps"]
        samples = numerator // fps if numerator % fps == 0 else None
        match = by_length.get(samples)
        label = f"{fixture['width']}x{fixture['height']}@{fps}"
        if match is None:
            excluded.append({"fixture": label, "frame_count": fixture["frame_count"],
                             "loop_samples": samples if samples is not None else f"fractional ({numerator}/{fps})",
                             "reason": "no speech variant has exactly this loop length; no padding, trimming or tiling"})
            continue
        name, path, digest = match
        row = json.loads(json.dumps(fixture))
        sidecars = row.setdefault("sidecars", {})
        if "reference_audio" in sidecars:
            sidecars["generator_reference_audio"] = sidecars["reference_audio"]
        relative = os.path.relpath(path, output) if path.drive == output.drive else str(path)
        sidecars["reference_audio"] = {"path": relative.replace("\\", "/"), "sha256": digest}
        provenance_relative = os.path.relpath(provenance_path, output) if path.drive == output.drive else str(provenance_path)
        sidecars["speech_provenance"] = {"path": provenance_relative.replace("\\", "/"), "sha256": sha256_file(provenance_path)}
        row["reference_audio_variant"] = name
        for key in ("path",):
            if not os.path.isabs(row[key]):
                row[key] = os.path.relpath(fixtures_path.parent / row[key], output).replace("\\", "/")
        for sidecar in sidecars.values():
            if not os.path.isabs(sidecar["path"]) and sidecar not in (sidecars.get("reference_audio"), sidecars.get("speech_provenance")):
                sidecar["path"] = os.path.relpath(fixtures_path.parent / sidecar["path"], output).replace("\\", "/")
        kept.append(row)
    if not kept:
        raise ValueError("No fixture loop equals a speech variant length; nothing to attach")
    output.mkdir(parents=True, exist_ok=False)
    result = {**{k: v for k, v in manifest.items() if k != "fixtures"}, "fixtures": kept}
    with (output / "raw-fixtures.json").open("w", encoding="utf-8", newline="\n") as target:
        target.write(json.dumps(result, indent=2, allow_nan=False) + "\n")
    report = {"schema": "benchmark-speech-attachment-v1", "state": "NOT RUN", "source_manifest": str(fixtures_path),
              "source_manifest_sha256": sha256_file(fixtures_path), "speech_provenance_sha256": sha256_file(provenance_path),
              "attached": [{"fixture": f"{f['width']}x{f['height']}@{f['fps']}", "variant": f["reference_audio_variant"]} for f in kept],
              "excluded": excluded, "loop_rule": LOOP_RULE,
              "scope": "Input declarations only; binding verifies hashes/length, and no A/V-cue sync or microphone path is implied"}
    with (output / "attachment.json").open("w", encoding="utf-8", newline="\n") as target:
        target.write(json.dumps(report, indent=2, allow_nan=False) + "\n")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    f = commands.add_parser("fetch")
    f.add_argument("--output", type=Path, required=True, help="new directory under the OS temporary directory or benchmarks/inputs")
    b = commands.add_parser("build")
    b.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    b.add_argument("--output", type=Path, required=True, help="new directory (benchmarks/inputs/speech-v1 or OS temporary scratch)")
    v = commands.add_parser("verify")
    v.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    v.add_argument("--build", type=Path, required=True)
    a = commands.add_parser("attach")
    a.add_argument("--fixtures", type=Path, required=True)
    a.add_argument("--speech", type=Path, required=True, help="directory holding a speech-v1 build")
    a.add_argument("--output", type=Path, required=True, help="new benchmarks/inputs directory")
    args = parser.parse_args(argv)
    try:
        if args.command == "fetch":
            output = output_path(args.output, "inputs")
            print(json.dumps(fetch(output), indent=2))
        elif args.command == "build":
            target = output_path(args.output, "inputs")
            provenance = build(args.sources, target)
            print(json.dumps({"state": "BUILT", "output": str(target), "recipe_sha256": provenance["recipe_sha256"],
                              "variants": {k: {"sha256": v["sha256"], "samples": v["sample_count"], "bytes": v["bytes"]}
                                           for k, v in provenance["variants"].items()}}, indent=2))
        elif args.command == "verify":
            report = verify(args.sources, args.build)
            print(json.dumps(report, indent=2))
            return 0 if report["state"] == "VERIFIED" else 1
        else:
            print(json.dumps(attach(args.fixtures, args.speech, args.output), indent=2))
        return 0
    except (ValueError, OSError, KeyError, wave.Error) as error:
        parser.exit(2, f"Speech reference blocked: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
