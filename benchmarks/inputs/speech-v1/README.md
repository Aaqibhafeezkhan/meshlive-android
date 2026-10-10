# speech-v1 reference audio

Generated, openly licensed speech input for raw-input call and stream cases (PCM16 mono 48 kHz, no header extras).
Not a pass: it supplies the actual Opus input; microphone, speaker and acoustic quality stay UNMEASURED.

The sources and both WAVs are committed (about 10 MB) with their licences: see "Sources and licences" below and
`THIRD_PARTY_NOTICES.md`. Keep the LibriSpeech attribution (CC BY 4.0) with any copy or derived file.

| File | Samples | Pairs with |
| --- | --- | --- |
| `speech-v1.wav` | 2,880,000 (60 s) | 60 s native raw-video loops (`sustained`, `mosaic` recipes) |
| `speech-v1-20s.wav` | 960,000 (20 s) | 20 s native loops (`quick-mosaic`, `coverage`) |

The runner requires WAV samples == raw-video loop samples (`frames * 48000 / fps`); nothing is padded, trimmed or tiled, so
other lengths (12 s framing) have no speech reference. Both files start and end with digital silence, so the loop seam is quiet.

## 60 s timeline (the 20 s file is the same recipe family, shorter)

guard 0.5 s - clean EN female 5.6 s - silence 1 s - clean EN male 6.7 s - silence 0.5 s - five loudness steps at -30, -24,
-18, -12, -6 dBFS sample peak (female/male alternating, 0.2 s room tone between) - silence 3 s - clean DE male 4.1 s -
silence 0.5 s - clean ES female 5.1 s - silence 0.5 s - double-talk F+M (second voice enters 1 s later) 5.5 s - silence
0.5 s - speech over 4-talker babble at 10 dB SNR 5.2 s - silence 0.5 s - speech over pink noise at 15 dB SNR 3.9 s - silence
0.5 s - solo piano 6.5 s - digital silence to 60 s. Speech sources are normalised to -26 dBFS active-RMS before mixing,
music to -24 dBFS RMS, "silence" is -65 dBFS RMS pink room tone except the digital-zero guards. Exact sample ranges, levels,
voices and sexes are in `speech-v1.provenance.json` (`variants.*.segments`).

## Sources and licences (all files in `sources/`, hashes in the provenance)

- 15 LibriSpeech test-clean utterances (CC BY 4.0, (c) 2014 Vassil Panayotov; https://www.openslr.org/12/, archive SHA256
  `39fde525e59672dc6d1551919b1478f724438a95aa55f874b576be21967e6c23`). Files are unmodified FLAC from the archive.
  `sources/LICENSE-LibriSpeech.txt` is the corpus licence notice.
- LibriVox German (`kleinzaches_1309_librivox`, chapter 3) and Spanish (`don_quijote_vol1_0706_librivox`, chapter 3):
  public domain. Cached as mono 16-bit FLAC windows decoded from the first 500 001 bytes of the item MP3.
- Open Goldberg Variations, Variatio 1 (Kimiko Ishizaka), CC0: 7 s window from the first 800 000 bytes of the MP3.
- Total sources 2.4 MB; the two WAVs 7.7 MB.

## Commands (repository root; needs `python -m pip install -r tools/requirements-audio.txt`)

```sh
# rebuild into a scratch directory and compare every output hash with this directory's provenance
python -B tools/build_speech_reference.py verify --sources benchmarks/inputs/speech-v1/sources --build benchmarks/inputs/speech-v1
# re-download the sources (streams the 347 MB archive once; run only to refresh provenance)
python -B tools/build_speech_reference.py fetch --output benchmarks/inputs/speech-v1-sources-NEW
# pair with a generator fixture manifest (fresh output directory), then bind per docs/TESTING.md
python -B tools/build_speech_reference.py attach --fixtures benchmarks/inputs/GENERATOR_OUTPUT/raw-fixtures.json --speech benchmarks/inputs/speech-v1 --output benchmarks/inputs/GENERATOR_OUTPUT-speech
python -B tools/bind_benchmark_inputs.py --suite $benchmarkBundle --case EXACT_CAMERA2_CASE --fixtures benchmarks/inputs/GENERATOR_OUTPUT-speech/raw-fixtures.json --devices $benchmarkRegistry --output benchmarks/catalog/SPEECH_BOUND_NEW --with-reference-audio
# score a finalized receiver trace pulled from a run
python -B tools/score_audio_quality.py --reference benchmarks/inputs/speech-v1/speech-v1.wav --provenance benchmarks/inputs/speech-v1/speech-v1.provenance.json --trace RUN/media-traces/PHASE --expect-sha256 reference=SHA --output benchmarks/runs/ANALYSIS_NEW/audio-quality.json
```
