# VideoStressGenerator (MeshLive test-video generator)

Generate original, deterministic SDR video stress inputs at their final native size, with exact RGB/I420 references, frame IDs, events, optional PCM audio and measurements. This project verifies source media. Android encoding, Bluetooth transport, phone orchestration, capture analysis and streaming acceptance belong to the MeshLive app and its harness in the parent repository (see `../../docs/TESTING.md`).

Python 3.11+ is required. NumPy and Pillow provide the native renderer; FFmpeg/ffprobe provide the optional FFV1 reference container and diagnostic H.264 encode. No downloaded clips, installed fonts or network services are used by generation.

```sh
python -m pip install -e ".[test]"
python -m videostress list
python -m videostress dry-run --config examples/mixed.json
python -m videostress generate --config examples/mixed.json --output outputs/mixed_NEW
python -m videostress validate outputs/mixed_NEW/raw-fixtures.json
python -m pytest -q
```

`vsg` is the installed equivalent of `python -m videostress`. Every output/report destination must be fresh. Generation estimates native raw/reference disk bytes, working-frame memory and pixel/particle work before writing; stderr reports progress. Ctrl+C/SIGTERM removes registered unfinished files only. A user-created file in that directory is preserved. Forced process termination cannot run cleanup.

Generated outputs (raw I420/RGB references, sidecars, reports and audit evidence) are not committed to the repository; regenerate them locally with the commands above into a fresh `outputs/<name>_NEW` folder. `python -m tools.verify_catalogue` (below) re-runs the catalogue audit of all forty pattern implementations, native exports, lossless round-trips and complex-pattern controls. Generated media establishes no Android performance result.

## Select a bounded input

```sh
python -m videostress list --group detail
python -m videostress generate --pattern p14 --mode 176x144@7 --frames 14 --seed 123 --output outputs/checker_NEW
```

`--mode` accepts any positive even width/height and positive integer FPS, including square/nonstandard dimensions and low rates. Presets are `tiny`, `sd`, `720p`, `1080p`, `4k`; explicit modes use `WIDTHxHEIGHT@FPS`. Repeat `--mode` to generate only those modes. The raw renderer has no scale/crop/interpolation/letterboxing stage. Codec/container resources and implementation limits can reject a selected optional encode; no format or mode is substituted. Set `lossless:false` in a timeline for exact raw references without FFmpeg.

Single-pattern parameters use a JSON object; PowerShell can supply it through a configuration file to avoid native-command quoting differences. `examples/detail.json` demonstrates one-/two-pixel cells; `examples/complex.json` selects only the six complex-motion patterns. Pattern parameters and defaults are available through `list`. Unknown fields/parameters, noninteger frame counts, invalid seeds, duplicate modes/segment IDs and unsupported pixel formats are rejected.

Timelines contain ordered segments with integer `frames`, optional `repeat`, `seed`, `params` and stable `id`. Each repetition starts the same source-frame sequence. File-frame IDs remain unique within a finite output; consumer file looping repeats those IDs. The consuming harness owns stream generation and loop iteration. PTS and durations use exact fractions; no accumulated floating-point timing is exported. Motion calculations evaluate the source index directly, allowing stateless seeks.

Segments may instead declare `seconds` as a positive integer, decimal or fraction string. The selected FPS must represent the duration exactly in whole frames; impossible durations are rejected, never rounded. `frames` and `seconds` are mutually exclusive. A multi-FPS export resolves its own segment starts/counts for every mode and retains the same declared seconds. Existing frame-indexed pattern parameters retain their original meaning; changing FPS does not rewrite parameters or implicitly slow an animation. Long segments evaluate the actual source index rather than repeating their first half-second. Explicit `repeat` still declares a content reset.

## Sustained, composite and framing workloads

The short catalogue remains a contract/pattern check. These bounded recipes provide different source behaviours without requiring operator motion:

| Recipe | Duration | Declared purpose |
| --- | --- | --- |
| [sustained](examples/sustained.json) | 60 seconds, six 10-second sections | Quiet, native detail, independent motion, complex motion, entropy burst and quiet draining; all cuts remain in the timeline |
| [mosaic](examples/mosaic.json) | 60 seconds | Four native panels with independent seeds/motion; compare quality distribution against focused full-screen workloads |
| [framing](examples/framing.json) | 12 seconds | Native border, four distinct orientation landmarks, a square and a filled circle, plus audiovisual cues |

```sh
python -B -m videostress dry-run --config examples/sustained.json --mode 720x480@30
python -B -m videostress generate --config examples/sustained.json --mode 720x480@30 --output outputs/sustained_NEW
python -B -m videostress generate --config examples/mosaic.json --mode 640x480@30 --output outputs/mosaic_NEW
python -B -m videostress generate --config examples/framing.json --mode 720x480@30 --output outputs/framing_NEW
python -B -m videostress validate outputs/sustained_NEW/raw-fixtures.json
```

Use dry-run to inspect selected frame counts/bytes before generating only the modes needed by the next benchmark. The 60-second 720x480/30 native recipe uses 933,120,000 bytes per I420 reference, two references with markers, plus 5,760,044 bytes for its PCM WAV and sidecars. One native source can serve different phone roles/bitrates/encoders; no raw copy is needed per setting. These durations and patterns are practical starting workloads, not proof of sufficient load or endurance. Compare actual selected-encoder processing time, payload/bursts and queue behaviour in the consumer. Lower FPS remains a separate request.

A mosaic segment supplies `grid:[columns,rows]` and `panels:[{id,pattern,seed,params}]`. Four, eight or ten panels require no separate renderer. The grid partitions the native even-pixel/chroma lattice; uneven divisions distribute whole two-pixel cells without resizing any pattern. Alternatively declare each panel's even native `bounds:[x,y,width,height]`; overlap/out-of-frame bounds are rejected. Explicit bounds are validated at every selected mode. The hashed `panels.json` records actual coordinates, IDs, complete parameters/seeds, timeline windows and fixed exclusions. More panels do not increase total frame dimensions or automatically establish more encoder stress. Per-panel diagnostics remain supplemental to complete-frame quality.

`reference_mode:"native"` retains marked primary I420, unmarked I420, per-frame RGB/I420 hashes, markers/events/audio and measurements without full RGB duplicates. The default `full` mode remains compatible with previous exports. A framing export retains one exact marked `calibration.rgb24` frame plus `calibration.json`; `sidecars.calibration_reference` binds its pixel hash and `sidecars.calibration` binds its geometry. The chart requires at least 160x144 native pixels. Its marker uses a declared top-centre inset and cannot overwrite the border/landmarks/shapes. This reference supplies geometry; consumer detector tolerances and actual captured screen validation remain separate prerequisites.

The recipes declare a small bottom-right white/off-black cue patch, two on-frames every three seconds. A 60-second recipe has twenty IDs with distinct deterministic 100 ms chirps; 12-second framing has four. `events.json` carries each visible onset/ROI/on-frame count and matching audio sample/PTS/frequency template. The same predeclared cue track appears in `marker-layout.json`, and its entire fixed ROI is excluded from content statistics. Complete-frame quality still includes these pixels. Visual duration is explicitly frame based and changes with selected FPS; audio duration is explicitly sample based. Source-file cues are expected input events, not observed dispatch, physical source preview, screen or speaker timing. The execution harness must bind actual generations/loops and independent observations.

Sustained and mosaic recipes also carry seeded noise from 10 through 50 seconds at amplitude 0.12 with the declared sine-squared envelope. The 40-second PCM interval stays 1,920,000 samples at every FPS; chirps remain separately identified. Quiet lead/drain intervals contain only the brief cues. This adds sustained audio activity for queue/dropout diagnosis; it does not measure speech quality, echo or microphone routing. Framing retains cues alone.

| Group | Patterns | Purpose |
| --- | --- | --- |
| calibration | p01–p04, p06–p07 | Colors, steps, gradients, low contrast and fades |
| detail | p08–p15 | Wedges, frequency rings, zone plate, lines, checkerboards and grids |
| motion | p05, p16–p21 | Gradients, breathing circles, zoom, polygons, bars, balls and crossing objects |
| entropy | p22–p26 | Monochrome/color noise, noise tracks, moving boundary and grain/edges |
| complex | p27–p32 | Ink, clouds, snow/rain, confetti, dust explosion and rotating particles/trails |
| transitions | p33–p36 | Rectangles, hard cuts, labelled luminance transitions and dissolves/fades |
| timing | p37–p40 | Scrolling text, readable counter, visual/audio cue and machine sync track |

Ink is procedural multi-vortex interference; clouds are analytic advected multiscale wave density. Neither is a physical fluid simulation. Precipitation, confetti, dust and free particles have separate independent trajectory mechanisms. All six expose `density`, `speed`, `detail`, `entropy`. Audit measurements prove pixel/statistic effects at declared test settings; SI/TI need not rise monotonically with a control because sampling/aliasing matters. Random noise is an extreme entropy input, distinct from ordinary viewing quality.

Pattern p35 is labelled FLASHING. Its default changes a central patch. High-rate full-screen transitions above three per second require explicit `full_screen:true` and `allow_high_rate_flash:true`; dry-run rejects them without the opt-in. The bitmap font is original and MIT-licensed, supports A–Z, 0–9 and ` .:-/_@`, and draws native blocks without font substitutions. Tiny sizes can clip/alias visual details and text; a rendered frame does not prove legibility or distinguishability there.

## Import consumer requirements read-only

```sh
python -m videostress list --requirements PATH/input_requirements.json
python -m videostress dry-run --config examples/mixed.json --requirements PATH/input_requirements.json
python -m videostress generate --config examples/mixed.json --requirements PATH/input_requirements.json --output outputs/consumer_NEW
```

The importer reads `benchmark-input-requirements-v1`, validates dimensions/FPS/frame bytes/timebase and checks selected modes. It ignores phone names as generation policy. A newer JSON can be supplied without code changes. Requirements do not automatically schedule every row. The catalogue audit records the hashes of the requirements file it read. Verification executes only the binder's three pure contract functions in memory; it never imports app modules, writes app bytecode, runs the binder's main function or operates devices.

`raw-fixtures.json` follows consumer schema 1: one native I420 row per `(width,height,fps)`, actual SHA256/count/color, relative raw path and hashed sidecars. Different content at the same mode belongs in a separate variant manifest. Existing benchmark sources are never replaced. The app's binder creates any new request IDs; supplying files does not authorize or perform binding.

## References, color, markers and audio

Generated RGB24 is full-range nonlinear BT.709 signal-space R'G'B'. The documented integer matrix converts it to limited-range BT.709 I420, with 2×2 box-averaged centered chroma. Output is planar Y/U/V with no padding, 8-bit, square pixels and unchanged geometry. Raw byte size is `width*height*3/2*frame_count`. Original RGB is a separate higher-fidelity reference. Marked and unmarked RGB/I420 are retained exactly. Optional FFV1 v3 in NUT must decode to identical primary I420 bytes/count and exact native PTS. NUT/FFV1 can omit stream color tags; use its hashed provenance sidecar for authoritative color/conversion declarations. A lossy diagnostic MP4 is always a derivative.

The VSG1 marker is a 26×25 cell grid with fixed bounds, triplicate payload bits and CRC32. With default two-pixel cells it needs at least 52×50 native pixels. The payload contains frame index and reduced rational timestamp. The reference decoder checks border orientation, luminance separation, majority redundancy and checksum. Ambiguous cells/corruption are rejected. The native RGB/I420 self-test establishes no robustness claim for a phone display, camera recording or lossy codec.

Marker area and limitations are explicit. At minimum size the content ROI is empty and content metrics remain UNMEASURED. All marker exclusions, including intrinsic p40 tracks, are fixed before generation for the entire file. SI excludes any Sobel stencil touching them; SSIM excludes any Gaussian window touching them. Human source IDs in p38 are displayed as a prefix; `frames.csv` retains the complete ID. Changing the mask after observing scores is unsupported.

Optional audio always includes a 48 kHz mono signed PCM16 WAV reference; separately requested mono/stereo 8–192 kHz originals have their own hashes/clocks. Stereo originals duplicate the same reference signal on both channels. Beep, linear chirp/sweep and seeded counter-based noise events have explicit identity, amplitude and duration in 48 kHz samples. Cue starts use `floor(frame*48000/fps)` and preserve the exact sub-sample timing error. p39 schedules matching visual/audio events when audio is enabled. WAV sample counts use integer duration, reference samples are reproducible across chunk sizes, and possible clipping is rejected. Standard RIFF WAV files above 4 GiB are unsupported. Audio generation does not inject audio into the app or measure acoustic synchronization.

User audio events declare exactly one `frame` or `seconds` anchor. Seconds must be nonnegative and exactly representable in whole frames at the selected FPS; invalid fractions are rejected without rounding. Duration remains an explicit 48 kHz sample count.

`role:"workload"` explicitly identifies sustained audio activity; short timing events use `role:"cue"` or omit the role. Consumers can correlate bounded cues without treating the 40-second signal as one timing template. Cue correlation does not approve the complete waveform between cues.

## Measurements and optional analysis

Every output has per-frame and per-segment SI/TI with median, p95 and peak. `vsg-siti-v1` uses luma expanded from limited code values to floating 0…255, an unnormalized 3×3 Sobel magnitude population standard deviation for SI, and signed frame differences for TI. Edges and ROI stencils are explicit. Included/excluded cut/seam TI are retained separately; the conditional end-to-start loop seam is a temporal sample and does not duplicate a spatial sample. This is a documented engineering statistic, not a current P.910 certification claim. FFmpeg's legacy `siti` differs in integer range-expansion rounding.

```sh
python -m videostress analyze outputs/mixed_NEW/raw-fixtures.json --mode 160x96@5 --codec libx264 --allocation capped --bitrate 180000 --buffer-bits 180000 --output outputs/diagnostic_NEW
```

The optional adapter supports explicit libx264 `abr` or `capped` allocation, one thread, no B frames, unchanged FPS/geometry and no filler padding. `capped` is a target with VBV maximum/buffer; strict CBR and other diagnostic codecs are unsupported. It verifies exact frame count/PTS, then reports actual packet payload, per-frame/100 ms/1 s demand distributions/peaks and aligned full-frame/content PSNR/SSIM. Empty bitrate bins are retained, and partial final bins use their actual duration. Low FPS can therefore yield empty 100 ms bins. PSNR uses 255 as the 8-bit code peak; luma SSIM uses valid 11×11 Gaussian windows, sigma 1.5, population covariance. Too-small/empty SSIM regions stay UNMEASURED. VMAF availability is recorded; no VMAF adapter/model is implemented.

```sh
python -m videostress analyze outputs/mixed_NEW/raw-fixtures.json --mode 160x96@5 --natural-reference PATH/recorded-native.mkv --natural-range limited --natural-cuts PATH/cuts.json --output outputs/natural_NEW
```

Supplied natural references must already decode as 8-bit I420 at exactly matching geometry/FPS/count/PTS. Other imported formats and automatic conversion are unsupported. Tags are read from the source; missing tags remain UNSPECIFIED. Supply `--natural-range` only from source evidence; unknown luma range leaves SI/TI UNMEASURED. Cuts JSON is `{"schema":"vsg-natural-cuts-v1","cut_frames":[12,30]}`. Without cut classification, sustained TI shortfalls stay UNMEASURED. Matched analysis reports spatial AND temporal statistic shortfalls across median/p95/peak. No camera recording was supplied for this delivery; natural-footage equivalence remains UNMEASURED.

## Reproduce the audit

```sh
python -m tools.verify_catalogue --output outputs/catalogue_NEW --consumer ../bind_benchmark_inputs.py --requirements PATH/input_requirements.json
```

This explicit audit exports 18 frames per pattern at 160×96/7 FPS into private temporary storage, validates every reference and optional audio, measures complex controls and retains the results. Requirements modes are dry-run only. Tests also cover native 2×2 output, seed boundaries, low rates, square geometry, conversion primaries, exact cell layouts, event/sample alignment, marker corruption/ambiguity, wrong geometry/FPS/layout/depth, truncation, invalid/rehashed sidecars, duplicate rows, optional-tool absence and confined cancellation cleanup. They are source-media tests.

Byte-identical source pixels are checked by sequential generation and out-of-order re-render on the recorded environment. Different Python/NumPy/Pillow/platform versions can change floating-point/raster results; provenance records those versions and package source hashes. Container file bytes are not promised identical across FFmpeg builds. Use the retained source version when validating an older variant; the validator refuses to claim exact re-render success with different generator code.

Sidecar schemas are specified in [docs/FORMATS.md](docs/FORMATS.md); implementation/dependency/asset licensing and inspected primary references are in [docs/LICENSING.md](docs/LICENSING.md).
