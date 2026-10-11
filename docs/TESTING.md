# Testing and benchmarks

*Engineering log and process for contributors; you do not need it to use or build the app.*

This is the contributor guide to testing MeshLive: what you can check without a phone, what the acceptance gates
mean, how to set up two or more Android phones of your own, how to run and read the benchmark suite, and how to
make a release. It is written for someone who owns a few phones and a computer, not a lab.

**What you need for what**

| You have | You can do |
| --- | --- |
| Only a computer | Quick checks (next section), compile and plan the benchmark inventory, bind supplied inputs, analyze recordings someone else made |
| Two or more Android phones | Everything above, plus run workflow checks and real streaming/call cases between your phones |
| A camera or microphone pointed at a screen and speaker | The optional independent measurements (picture timing, optical latency, acoustic cues) |

You do not need the six reference phones to contribute. Say which phones you used, test them against the same gates,
and keep your results labelled with their real scope. A passing run on two of your own phones is a real result for
those phones; it is not a pass for any other phone or build.

Terms used below:

- **Hop**: one Bluetooth link between two adjacent phones. A **relay chain** is source -> middle phone(s) -> receiver.
- **Case**: one exact, fully specified test request (who sends, who receives, which transport, encoder, bitrate,
  frame size and rate, buffers, lifecycle actions, input). Its **case ID** is derived from that request.
- **Transport**: `classic` = Bluetooth Classic RFCOMM, `ble` = LE L2CAP connection-oriented channel (CoC), `gatt` = LE
  GATT (the slow, widely supported attribute protocol, used as a byte stream).
- **Frozen APK**: one built APK, identified by its SHA-256, used for a whole comparison.

## Quick checks (no phone needed)

Run these from the repository root before opening a pull request.

```
python tools/fetch_native_sources.py
./gradlew :app:testDebugUnitTest :app:assembleGithubDebug
python -B -m pytest -q tools
```

- `python tools/fetch_native_sources.py` downloads the pinned libaom and libopus sources into the git-ignored
  `reference-repos/` folder. Run it first on a fresh clone; the native build needs them. `--verify-only` checks what is
  there without downloading.
- `./gradlew :app:testDebugUnitTest :app:assembleGithubDebug` runs the JVM unit tests and builds the debug APK (use
  `gradlew.bat` on Windows). The toolchain is JDK 21, the Android SDK with NDK 28.2 and CMake 3.22.1, and Perl (the
  libaom build uses it). Add `:app:compileGithubReleaseKotlin :app:compileFdroidReleaseKotlin` when you touch code that differs between debug and release or between the two distribution flavours (ARCHITECTURE "Build notes").
- `python -B -m pytest -q tools` runs the host-tool tests (Python 3.11 or newer). They use temporary directories and
  never contact a phone.
- Optional, for the C# desktop receiver and file sender (needs the .NET SDK, no Bluetooth):
  `dotnet run --project receiver/BluetoothVideoReceiver.csproj -c Release -- --self-test` and
  `dotnet run --project tools/BluetoothVideoFileSender/BluetoothVideoFileSender.csproj -c Release -- --self-test`.

What these checks prove: the stated unit or protocol check passes. JVM tests do not run Camera2, native phone codecs,
the radio, the display or the speaker, and they do not establish hardware maximums or optimal settings. Tests of
picker ceilings, priority thresholds, budgets and timeouts protect configured behaviour; they are not measurements.
Keep policy snapshots separate from failure regressions and metric validation. A change in native YUV plane copying
additionally needs `NativeYuvPlaneCopyTest` (an instrumentation test) on a phone of each affected family. Documentation-only
edits need no build.

When you fix a bug: reproduce it first, add only a useful focused regression, make a narrow fix, run the checks above,
freeze one APK and compare at the same settings (see "Changes and retesting").

## Scope: what a complete test covers

Every capable phone should act as sender, receiver and middle viewer/forwarder (or supported opaque relay).
Eligibility comes from each phone's exported capabilities and a current preflight, not from a registry entry. Encoding
and decoding are different capabilities: a phone that can decode VP9 but not encode it is a receiver for VP9, and you
must not request a missing encoder and count a fallback. Family compatibility protections stay in force, but any
requested versus effective difference (a lower rate, a different encoder, direct fallback) is reported and cannot
pass the original request.

The project's reference phones are listed in [../tools/benchmark_scope.json](../tools/benchmark_scope.json):
S24 (SM-S921B), S22 (SM-S901E), M31 (SM-M315F), ASUS (ASUS_I01WD), Xiaomi (Redmi 23053RN02I) and A55 (SM-A556E);
the A15s (OPPO CPH2179) is an additional measured phone. They are examples of the span of Android versions, SoCs and
Bluetooth stacks the app should handle. Your phones extend the same inventory; they do not replace anything.

### Roles and topologies

| Flow | Required proof |
| --- | --- |
| A -> B | Direct Camera2 plus audio, native recorded raw encoding and finite replay as separate inputs; startup, motion, controls, teardown and rejoin |
| A -> B and A -> C | Source fan-out; independent healthy and slow child queues, admission and delivery |
| A -> B -> C | B plays locally and forwards; C authenticates B as parent and A as the original source; score both hops |
| Longer linear chains | Actual ordered parents, source and stream identity, TTL, each middle phone's playback and child health |
| Direct viewer plus chain, or two branches | Local admission and healthy-branch isolation under measured overlapping media |
| A -> B -> (C, D) | A separate group/relay diagnostic; the normal viewer/forwarder admits one child |
| Diamond or alternative parents | One active parent, same-source recovery, hysteresis, duplicates, temporary cycles, TTL expiry |
| A <-> B | Simultaneous independent video and audio; both call setup roles and both outgoing profiles |
| A <-> relay B <-> C | An actual one-hop opaque call relay; both endpoint directions, adjacent liveness and queues |
| Disjoint calls or chains | Measured coexistence and media overlap; launching simultaneously is not enough |

No silent transport fallback. For N phones there are N x (N-1) directed pairs and N!/(N-K)! ordered K-phone chains,
each multiplied by the compatible transport and profile assignments. The inventory compiler therefore represents large
products by dimensions and counts. Unsupported mixed edges, branching or relay runners stay explicit obligations, not
executable-looking passes. A branch diagnostic does not approve normal branching in the UI. Recovery straight to the
source fails a requested chain: direct fallback is a failed requested chain.

### Settings coverage

| Dimension | Coverage |
| --- | --- |
| Codec | Every offered sender encoder and compatible receiver decoder; hardware, software and native paths distinguished |
| Allocation | Automatic, CBR, VBR and CBR-FD where actually offered; actual selected mode, bursts and keyframes, permitted dropping |
| Camera and geometry | Back and front, native dimensions and FPS, orientation and mirroring, preview and framing, flip, Surface recreation |
| Load | Low, high and boundary resolution, FPS and rate; 300/500/900/1000 kbps checkpoints where offered; picker maxima Classic 1500, CoC 1200, GATT 1100 kbps |
| Playback | Low 30 ms, Balanced 250 ms, High 500 ms; each receiver's choice independent from the sender; `auto` (adaptive 250-500 ms) is the app default |
| Audio | Normal mono 48 kHz Opus 32 kbps, permissions, mute, startup, drift, lip sync; audio-only and video-only are diagnostics |
| Thermal | Toggle on and off, observed legitimate states and restoration; a cold toggle does not measure real heat |
| Persistence and forwarding | Before-join forwarding, supported active changes, late or slow children, relaunch, role and call hand-over |
| UI | Stable discovery, Connecting and Recovering states, truthful settings, no stale or duplicate entries, no unwanted paired-device picker |

A back-camera capability export does not prove front-camera or flip eligibility. Advertised modes are not sustained
viability. Thermal tests verify the source-local policy (including supported Camera2 range differences, warnings,
critical stop and restoration) using safe policy fixtures or observed heat; never heat a phone deliberately to the
critical state.

At the time of writing the reference scope compiles to about 26,800 exact cases (7,075 broadcast, 17,910 call and 1,800
chain cases) plus 2,395 explicit 45-second settings-sweep diagnostics. Everything starts NOT RUN. The sweep covers
every individually feasible offered geometry, FPS and rate value and the declared boundary combinations; it does not
exhaust the residual product of all settings. High FPS never approves low FPS, because cadence, packet spacing and
burst behaviour change. Plan JSON reports selected and total requested media seconds, excluding setup, cleanup,
scoring, conflicts and reruns.

### Lifecycle and faults

For each supported flow, keep the planned commands and the actual authenticated acknowledgements in each phone's own
clock, with cumulative counters, PID or generation boundaries and ambiguous intervals.

Cover, for each supported flow: fresh permissions, Bluetooth off and on, discovery and list retention; select,
authenticate and negotiate through first surface or audio, with cancel and back at each stage; broadcast Start, Pause,
Resume and End (paused liveness, resumed keyframe, downstream acknowledgement); call Invite, Ring, Accept, Decline,
Cancel and Hang-up, with availability reusable at once; mute, video off and on, flip, orientation and rapid controls;
Home, lock and unlock, preview destruction, OS interruptions and process death (Home and return do not approve
reconstruction after process death); settings at supported boundaries; late join and rejoin, child, middle or source
loss, no-route intervals beyond 15 s, slot freeing and cooldown expiry; slow-child isolation, bounded queues,
authenticated withdrawal and no reconnect or scan loops; decoder, keyframe and codec-config recovery, bursts and
controlled malformed or failing authentication that fail closed; call and broadcast hand-over with nothing stranded;
and, separately, matched, randomized, role-reversed 15-30 minute endurance, real heat and controlled range or
interference.

Required security and relay work includes wrong-identity, unauthorized, replayed and tampered controls, branch key
changes and expiry, and relay endpoint versus adjacent identity separation. Future product and platform prerequisites
are tracked in [PLAN.md](PLAN.md) and stay unmeasured here until implemented and executed.

## Common acceptance gates

The same gates apply to every phone and every supported role. Capability- or family-specific implementation and
focused bug regressions may exist when evidence supports them; they never create a different acceptance standard for
one phone. Never silently change a requested bitrate, FPS, resolution, allocation, encoder, audio, transport or buffer
to earn a pass. Missing or invalid measurements stay UNMEASURED, and every failed, interrupted, pending, missing or
unmeasured case stays visible in the results.

Two acceptance profiles exist. Each result records the profile it was scored under (`thresholds_version`) and is never
re-labelled. `device-run-v3` is the default profile of the earliest results. `device-run-v4-user` is the user-centred
profile adopted on 2026-10-09 and selected by `acceptance_profile` in
[../tools/benchmark_scope.json](../tools/benchmark_scope.json).

### `device-run-v3` (engineering profile)

Startup is at most 3000 ms and discovery at most 1500 ms (tightened on 2026-10-06; earlier results keep the thresholds
recorded with them).

| Measurement | Target |
| --- | --- |
| Actual frame intervals | p95 at most 1.25 frame periods; p99 at most 2 periods |
| Encoder and visible FPS | At least 95% of the selected FPS |
| Unexpected loss and freezes | Zero; keep input, transport, forwarder, audio, decoder and queue causes separate |
| Startup and recovery | At most 3000 ms; full fault-to-first-surface time, including the search gap |
| Discovery | At most 1500 ms |
| Offered load | Clean-window median encoded payload at least 90% of the selected bitrate |
| Aligned full-source picture | VMAF at least 90, SSIM at least 0.98, PSNR at least 35 dB |

### `device-run-v4-user` (user-centred profile)

People judge how old the picture and sound are, how long they stop, and whether speech is intact; they do not judge
VMAF or an FPS fraction. The v4 gates measure the former. The initial targets are to be revised from measured
distributions, never lowered to make a failure pass.

| Measurement | Decides | Target and evidence |
| --- | --- | --- |
| Longest stall | Every link | Calls at most 500 ms, streams at most 1 s. The largest gap between rendered surface updates from the receiver's rolling 256-gap report window; PASS needs every report interval to add at most 256 gaps, otherwise UNMEASURED. The tail after the last frame is not included |
| Frozen-time share | Every link | At most 1% of scored rendered time in unexpected freezes (gaps beyond the freeze threshold, less one frame period each); planned actions overlapping a freeze make it UNMEASURED |
| Effective-profile cadence | Every link | Slowest clean report interval at least 95% of the FPS in force (from the received stream header, time-weighted across a change; the requested FPS before any header). Requested-profile shortfall and share of time degraded are diagnostics |
| Audio continuity | Every link | Zero audio drops. Speech-gap metrics need an accepted-PCM adapter and stay UNMEASURED (`acoustic`) |
| Media age | Every link | Capture to release or surface acknowledgement of the same source PTS across two phones, only through measured clock anchors (`score_pipeline_trace.py` `media_age` plan; compared as a value plus clock uncertainty). No target is decided yet, so the value is reported and the gate stays UNMEASURED. Local queue ages are proxies, never media age |
| Unexpected freezes, loss counters, p95/p99 frame intervals | Healthy link only | Zero, and the v3 targets. On an impaired link they are DIAGNOSTIC |
| Task success, speech comprehension | None yet | Explicit UNMEASURED families: no human or acoustic adapter exists. A required full case cannot PASS while they are UNMEASURED |

A link is *healthy* unless the request carries `media_impairment(s)` or the manifest declares
`acceptance.link_class: "impaired"` (for example offered load above the measured link capacity). It is never inferred
from the result. Startup, discovery, identity, requested-profile faithfulness and unexpected-event gates are
unchanged. VMAF, SSIM, PSNR, the visible-FPS fraction, encoder FPS and offered load are DIAGNOSTIC rows: reported with
their v3 verdict as `measured_status`, never deciding a gate, family or case. Screen framing keeps its own gates. Call
limits apply to private-call receivers; stream limits to broadcast receivers.

The inventory writes the profile and its `required_families` into every bundle group (v4 drops `picture` from
the settings-sweep diagnostics and adds `media_age`, `task_success` and `speech_comprehension` to full cases). The
profile is not part of a request, so no case ID changes. To rescore retained evidence under another profile:

```
python -B tools/run_benchmark_suite.py analyze --profile device-run-v4-user --manifest PATH/manifest.json --output benchmarks/runs/RESCORE_NEW
```

`tools/score_device_run.py --profile` scores a single manifest.

### Rules that apply to every gate

These are project engineering targets, not perceptual certification. A scoped pass does not approve another request,
build or input, or the whole experience. The unified report requires all measurement families by default; narrowing
`required_families` produces a diagnostic, not full acceptance. Changing a numerical target needs a recorded reason
and a reviewed scope and inventory change, never a lower target picked to pass a failure.

- **Separate measurements.** Picture fidelity, callback jitter and lag, frame timing, lifecycle, Camera2 behaviour,
  acoustic quality and optical (glass-to-glass) latency are separate measurements. Decoder and Texture timestamps and
  notification rates do not prove unique physical frames, screen pixels, optical blur or latency. Do not invent
  missing clock anchors; keep unknown or repeated identities and every PID, session and generation boundary.
- **Whole timeline.** Score every planned active source-clock slot and phase, including initial gaps, missing-frame
  holds and terminal tails. Prefix-only fidelity is supplemental. A planned kill does not waive an earlier freeze. Keep
  original scores and make justified attribution corrections in named revisions.
- **Offered load is measured, not assumed.** Measure the real offered load, both directions of a private call, each
  chain hop and the full planned lifecycle, and prove the authenticated original source and the requested parent.
- **Input demand.** Characterize natural and synthetic inputs at the tested geometry and FPS with both spatial and
  temporal complexity (SI/TI), keep lower-complexity controls and distinct content workloads, and measure the selected
  encoder's frame, 100 ms and 1 s bursts: an easy scene that produces fewer bits does not cover a high selected rate.
  Keep CBR preservation and explicit VBR burst experiments separate.
- **Same-input comparison.** Fixed raw or reference hashes support same-input comparisons; Camera2 without repeatable
  pixels supports only request-level comparison.

### Screen framing and resolved detail

Use a deterministic native-size calibration input with declared square pixels, distinct orientation landmarks, known
border and interior coordinates, a circle and a square. Bind its exact reference hash and the expected
rotation and mirroring. Record the actual video viewport, its screen coordinates and the selected FIT or FILL mode.
After the declared orientation, FIT uses the smaller viewport-to-content scale and preserves every edge; FILL uses the
larger uniform scale and crops the excess. Measure rotation, mirroring, horizontal and vertical scale, crop, bars and
landmark error independently against that mapping. Predeclare tolerances and validate the detector before assigning
acceptance.

Screenshots are captured output, not the reference. A screenshot's pixel dimensions do not establish the encoded
resolution or the resolved picture detail, and static framing does not measure sharpness, smoothness or latency;
native line-pair and detail charts need separate measurement. Repeat framing after orientation, video off and on, and
Surface or lifecycle changes in each eligible display role. The raster calibration scorer and the DEBUG viewport trace
exist; framing acceptance still needs the supplied native calibration chart, a captured viewport with the intended
mapping, and validated detector tolerances. Until then it remains UNMEASURED.

## Setting up your phones

### On the computer

- Android platform-tools (`adb`) on your `PATH`. The tools find `adb` on `PATH`, or under `ANDROID_HOME` or
  `ANDROID_SDK_ROOT`; pass `--adb PATH_TO_ADB` to choose another.
- Python 3.11 or newer. For scoring: `python -m pip install -r tools/requirements-measurements.txt`, and FFmpeg and
  ffprobe on your `PATH` (full reference fidelity uses libvmaf, SSIM and PSNR).
- A USB cable per phone. Wi-Fi debugging works, but USB is more predictable for long runs.

### On each phone

1. Enable Developer options and USB debugging, then authorize the computer when the prompt appears.
2. Install the debug APK you are testing and open the app once. Grant the permissions it asks for: camera,
   microphone, notifications, and nearby devices (Bluetooth scan, connect, advertise) on Android 12 and newer, or
   location on older versions. The suite preflight blocks a phone whose camera, microphone or Bluetooth permissions
   are denied.
3. Turn Bluetooth on. Keep the screen awake and unlocked for the whole run (Developer options "Stay awake" while
   charging is fine). Preflight needs explicit unlocked evidence, not only an awake screen.
4. Check the Wi-Fi band. 2.4 GHz Wi-Fi roughly halved Bluetooth throughput on one reference phone (M31 GATT about 0.4
   Mbps against about 0.9 Mbps with Wi-Fi off). Each preflight records the participants' Wi-Fi band as a radio
   condition; report it with your results and check it before blaming the hardware.
5. Close other apps that use the camera, microphone or Bluetooth, and make sure no stale MeshLive process is running
   on a phone you are not testing (isolated cases check this for every registered phone).
6. Do not attach photos or screenshots of other people to results. For live Camera2 tests point the camera at a
   controlled, non-private target such as a chart or a moving object.

### The device registry

The registry maps your aliases to adb serials and public node prefixes. It is local and Git ignores it.

```
cp tools/benchmark_devices.example.json tools/benchmark_devices.json
```

Edit the copy: one entry per phone under `devices`, keyed by an alias (lower case, for example `pixel_a`), with the
phone's adb `serial` (`adb devices`; the mDNS name for wireless debugging) and its `node_prefix`, the first 12 hex digits
of the phone's MeshLive node ID. The app shows the node ID and logs it as `BENCHMARK call available: node=...`; the
capability export below records `node_prefix` too. The example's values are placeholders. Aliases must match the
scope (`tools/benchmark_scope.json`) and `tools/device_policy_registry.json`, so add your own alias to both when you add a
phone. Reinstalling with data cleared creates a new identity, so refresh the prefix after an uninstall or reset. A registry entry is never proof that a phone is available; preflight checks it every run.

### Capability export

The suite plans cases from each phone's exported capabilities (encoders, decoders, camera modes, bitrate and
transport limits, playback options). Build and run the instrumentation test on one idle phone at a time:

```
./gradlew :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class com.example.bluetoothtest.DeviceBenchmarkCapabilitiesTest io.github.meshlive.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL pull /sdcard/Android/data/io.github.meshlive.app/files/benchmark-capabilities.json benchmarks/catalog/capabilities/ALIAS.json
```

(The package name is the application ID, `io.github.meshlive.app`; test classes keep the
`com.example.bluetoothtest` Kotlin package.) Add the file to a copy of `tools/benchmark_scope.json`
under `capabilities` and add the alias to `required_phones`, then compile your inventory from that copy with
`--scope`. Keep the old capability file when you refresh one: capability refreshes keep their original evidence and
need a current eligibility check. For a phone pair you can also generate a settings matrix with
`tools/generate_device_matrix.py`.

### Verify the build and identity before every run

A phone is ready only when all of these are proven for the current run, and the read-only preflight of
`run_benchmark_suite.py` checks them for you:

- adb state is `device` and the alias maps to the serial you expect;
- the authenticated public node ID prefix reported by the app equals the registry's `node_prefix`;
- the installed APK's SHA-256 equals the frozen candidate. Compute yours with `sha256sum app-debug.apk` (or
  `certutil -hashfile app-debug.apk SHA256` on Windows). Preflight also pulls the installed base APK and rehashes it;
- the capability export's build fingerprint equals the phone's `ro.build.fingerprint`;
- Bluetooth is on, the screen is awake and unlocked, and the camera, microphone and Bluetooth permissions are granted;
- the offered capabilities include what the case requests.

`python -B tools/deploy_build.py --phones ALIAS1,ALIAS2 --apk PATH --adb PATH_TO_ADB` copies the built APK to
`benchmarks/builds/<first 16 hex of SHA-256>/` and installs it on the phones in parallel. A normal reinstall and an
uninstall or reset have different identity and persistence consequences; check the node prefix and installed hash
again after any retry.

## The benchmark workflow

All phone-facing orchestration is `tools/run_benchmark_suite.py` with five commands: `plan`, `run`, `analyze`,
`review` and `finish-analysis`. Compiling the inventory, binding supplied inputs, joining case state, planning,
analyzing saved recordings and reviewing outputs are all offline. Only `run` (and the focused diagnostics later in this
document) touches a phone.

### Where things live

Four groups, all under `benchmarks/`:

```text
benchmarks/
  inputs/INPUT_ID/                 reusable native media, declarations and licences
  builds/APK_SHA_PREFIX/           frozen APK, its verification, logs and change impacts
  catalog/                         inventory, case state, plans and capability provenance
  runs/EXECUTION_OR_MEASUREMENT/   results, manifests, raw evidence and analysis together
```

Git tracks source, documents, tools and small benchmark definitions (capability exports, the workflow example).
`benchmarks/runs/`, `benchmarks/builds/` and large `benchmarks/inputs/` content stay local and are git-ignored. Report
numbers, phone aliases and models, and short build hashes in your pull request; do not commit raw captures, traces or
media.

Each suite writes one directory `benchmarks/runs/EXECUTION_ID/` with `suite-result.json` (authoritative results,
family verdicts, limits and evidence), `report.html` and the cases' physical evidence: `manifest.json` (actual
identities, build, settings, input, actions, clocks and PID scopes), per-case `analysis.json`, CSV samples, raw logs
and any captured media. `state.json` and `remaining.json` in the catalog hold exact completion and retest state. A
single score file cannot approve a whole case. A fresh directory identifies each distinct input, build, execution or
measurement, and every capture or helper writes to an explicit output it owns, never to loose files at the project
root. Host scratch and review worksheets belong in the operating system's temporary directory. Keep final results,
manifests, request, input and build identities, licences and useful decisions; keep extra files only while a named
calculation, unresolved failure or comparison needs them.

### Step 1: compile the inventory

```
python -B tools/generate_benchmark_inventory.py --scope tools/benchmark_scope.json --output benchmarks/catalog/INVENTORY_NEW
```

This reads the capability exports named in the scope and the device registry, performs no device I/O, and writes
`benchmark-bundle.json`, the suites, `inventory.json`, `summary.json`, `settings-sweep-bundle.json` and a native
`input_requirements.json` (the media variants the cases need). A full compile of the reference scope writes about 27
MB and takes under a minute. Check the profile and assignment coverage, blocked reasons and the capability path, hash,
fingerprint and node of each phone. `--phones a,b` limits which cases are *executable* while keeping the omitted
assignments and their reasons; it is a selection, not live availability. Missing capability evidence, incompatibility
and unavailability are distinct reasons.

A case keeps every part of its request explicit: ordered sender, receiver and middle assignments, both call setup
roles, both outgoing profiles, transport, encoder and allocation, geometry, FPS and rate, buffers, lifecycle and input
type. Large residual products are represented by dimensions and counts and stay UNMEASURED; a generated subset does
not complete them. Lifecycle templates (pause and resume, background and foreground, receiver or middle kill and
rejoin, call controls, decline and cancel) run for at least 120 s and do not cover every profile-by-fault
combination, front camera, real heat or endurance. Generated suites default to Camera2; raw-media cases are separate
requests (see Inputs).

### Step 2: join the case state

```
python -B tools/benchmark_case_state.py state --suite benchmarks/catalog/INVENTORY_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --apk-sha256 APK_SHA256 --output benchmarks/catalog/STATE_NEW
```

State joins the inventory with any finished reports and recorded changes and writes `state.json` and `remaining.json`
(the output directory must be new). `--phones` names the phones you will use; availability never deletes scope, so
cases that need other phones stay in the state as NOT RUN. Repeat `--reports` with earlier `suite-result.json` files
and `--changes` with change-impact files to carry history forward. Exact keys, participant identities, referenced
manifest hashes, APK and PID scopes guard reuse; resemblance is not enough.

Case states:

| State | Meaning |
| --- | --- |
| NOT RUN | No qualifying exact execution; schedule when eligible |
| PASS / FAIL | A verified execution met or failed the gates on its recorded build |
| NEEDS RETEST | A later change affects an executed case; the old result, build, evidence and change reason are kept |
| UNMEASURED | Required evidence is missing or invalid; the missing adapter is named |
| BLOCKED | A concrete prerequisite failed; retry explicitly after new proof |
| INTERRUPTED | Execution or scoring stopped; partial evidence is kept and a fresh output is used |
| RUNNING / ANALYZING | Transient checkpoints, not completion |

Last measured result, freshness and availability are independent. A blocked retry keeps the earlier result and its
retest requirement; an untested affected case stays NOT RUN. A pass on a prior build is never labelled a pass on the
candidate build. A generated case, a handshake, a first frame, a fallback, changed settings or a partial prefix is
never a PASS.

### Step 3: plan

```
python -B tools/run_benchmark_suite.py plan --suite benchmarks/catalog/INVENTORY_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --state benchmarks/catalog/STATE_NEW/state.json --apk-sha256 APK_SHA256 --parallel 1 --output benchmarks/catalog/PLAN_NEW
```

`plan.json` lists the selected cases, jobs and batches, and the requested media seconds. Selection puts failures and
NEEDS RETEST first, then untested and unmeasured work, and favours less-used phones and roles so coverage rotates
across transports, loads and topologies. Read it for gaps before you run. An empty `--phones=` preserves every case
and selects none; it is not completion. State filtering never replaces live preflight.

### Step 4: run

```
python -B tools/run_benchmark_suite.py run --suite BUNDLE --devices tools/benchmark_devices.json --owner YOUR_NAME --phones ALIAS1,ALIAS2 --state benchmarks/catalog/STATE_NEW/state.json --apk-sha256 APK_SHA256 --adb PATH_TO_ADB --parallel 1 --output benchmarks/runs/RUN_NEW
```

`run` repeats the preflight, launches the participants, drives the planned actions, collects traces, stops the
participants during cleanup and independently verifies that no app process remains before it releases the phones.
Offline, erroring or still-live processes keep the phones' leases quarantined until you resolve that exact lease. Omit
`--case` to take the assigned queue, or repeat `--case CASE_ID` for a focused comparison or an explicit retry of a
blocked case. A bundle can select original case IDs without copying them:

```json
{"suites":[{"path":"PATH_TO_SUITE.json","case_ids":["EXACT_CASE_ID"],"execution":"isolated"}]}
```

An explicitly empty `case_ids` selects nothing and blocks an empty run. Generated bundles mark cases concurrent;
causal bundles declare them isolated. Isolated cases also check that every registered non-participant is reachable and
has no MeshLive process; an unknown quiet state blocks.

- `--defer-analysis` does the phone work, capture and cleanup, then exits with each case marked `PENDING_ANALYSIS`
  (neither scored nor passed). Start the next suite on the freed phones and score in the background with
  `run_benchmark_suite.py finish-analysis --output benchmarks/runs/RUN_NEW [--parallel N]`. Picture scoring uses
  `BENCH_SCORE_THREADS` threads (default 4); scores are identical at any thread count.
- `--hci-snoop {off,auto,on-failure,required}` (default `auto`) pulls each participant's HCI snoop log for the case
  window after cleanup. It is diagnosis evidence, never a pass or fail, and only `required` blocks a phone whose snoop
  is disabled. Snoop files hold every Bluetooth packet in the window, including from unrelated devices: keep them only
  for a named failure diagnosis. `--hci-snoop-scratch DIR` (or `HCI_SNOOP_SCRATCH`) holds the large bug-report zips.
- A failed broadcast run tries End only on its recorded live source process before pulling traces. Every run has a
  unique trace session ID.
- If a maintainer asks you to stop or pause physical testing, stop all device activity, keep the interrupted evidence
  and continue offline.

### Step 5: analyze

```
python -B tools/run_benchmark_suite.py analyze --manifest benchmarks/runs/ORIGINAL_RUN/CASE/physical/manifest.json --output benchmarks/runs/ANALYSIS_NEW
```

Repeat `--manifest` for more recordings; add `--baseline PATH/suite-result.json` to compare, `--measurements FILE` to
attach observation adapters, and `--profile` to score under another acceptance profile. Analysis reads originals
without a phone and never rewrites saved evidence. The unified output holds the plan, selection and execution
records, `suite-result.json`, `coverage.json`, `report.html` and per-case evidence. Read each family's gates and
reasons: picture, smoothness, connection, transport, lifecycle, topology, camera, acoustic, physical latency and
endurance. Missing Camera2, acoustic, optical, endurance and radio-impairment evidence stays in the report; adding an
adapter cannot erase another family's failure. A call needs both setup roles and both media directions; a chain needs
every edge.

### Step 6: purpose review of a new output

When all writers stop, a run or analysis freezes a review of its fresh files inside `suite-result.json`. Result and
identity files are protected; unreferenced logs, XML, command output, CSV, media and empty files start PENDING instead
of being kept forever. Read each pending file, then consume its exact relative path or name a remaining purpose:

```
python -B tools/run_benchmark_suite.py review --output benchmarks/runs/RUN_NEW --consume CASE/setup.log --purpose 'Setup inspected; no remaining use'
python -B tools/run_benchmark_suite.py review --output benchmarks/runs/RUN_NEW --retain CASE/comparison.json --purpose 'Compare unresolved decoder failure at the same input and settings'
python -B tools/run_benchmark_suite.py review --output benchmarks/runs/RUN_NEW
```

Take paths from `artifact_review.files`. Changed bytes, traversal, links and evidence that something still needs block
consumption before deletion. A PENDING review exits non-zero; COMPLETE describes storage review, not a phone pass.
Empty files can be meaningful failure evidence, so neither zero bytes nor a file extension decides retention.

### Step 7: checkpoint (when you keep a shared ledger)

The project's maintainers keep a local machine ledger (`benchmarks/catalog/ledger.json`, not in Git) and a derived
readable view, `benchmarks/index.html`. A fresh clone has neither, and `plan`, `run` and `analyze` do not need
them. If you keep your own, update it after each finalized, reviewed execution:

```
python -B tools/benchmark_checkpoint.py update --phones ALIAS1,ALIAS2 --apk-sha256 APK_SHA256 --report benchmarks/runs/LATEST_RUN/suite-result.json --changes PATH/change-impact.json
python -B tools/benchmark_results.py
```

`benchmark_checkpoint.py` verifies the complete matching state and the evidence before it replaces the state, the
remaining selection, the ledger and the index, and it rejects dropped measured history, invented or stale passes and
incomplete purpose reviews. `check` verifies without writing. `benchmark_results.py` refreshes `index.html`, which
checks the ledger's hashes and counts; it executes nothing, rescores nothing and is a derived view, not a second
results ledger. Do not keep a second Markdown status ledger.

### Changes and retesting

Before scheduling preservation tests for a behaviour change, record its ID, the actual time with a UTC offset, the
changed files, the reason and the affected scope:

```json
{"changes":[{"id":"CHANGE_ID","at":"ACTUAL_ISO8601_TIME_WITH_OFFSET","files":["EXACT_CHANGED_FILE"],"reason":"Changed behaviour, measured basis and preservation scope"}]}
```

Without `affects`, every earlier executed case conservatively needs a retest. A narrower selector uses non-empty exact
lists for `transports`, `phones` or `encoders` (all given dimensions must match; phones match any participant; encoders
match the outgoing profiles), and the `reason` must give the evidence for excluding other combinations. A change to a
shared stage (receive, decrypt, decoder, controls) affects every combination using that stage, not only the failing
pair. Rescoring old recordings is not an execution after a fix. Pass the file to state joining or the checkpoint with
`--changes`; a duplicate ID with a different definition is rejected.

Adding a phone extends the registry and scope and preserves unrelated case IDs. Changing settings, topology, controls,
duration or input creates a new case with no inherited pass. Retire obsolete requirements explicitly in the scope's
`retired_requirements` with an ID and reason, and keep meaningful old evidence.

A gate change (a new acceptance profile) changes no request, so it retires gate requirements rather than case IDs:
set `acceptance_profile` in the scope, regenerate into a fresh catalog directory and review the diff (case IDs
unchanged); record a change with `"acceptance_profile":"device-run-v4-user"`, naming the retired targets in its
reason; each affected case becomes NEEDS RETEST until a result scored under the new profile exists, keeping its old
result, build and evidence (an offline `analyze --profile` of the retained manifest resolves it without a phone);
then checkpoint with the rescore report. A v3 PASS is never shown as a v4 PASS. Write the measured reason in
[DECISIONS.md](DECISIONS.md).

## Example: the small workflow check

[../benchmarks/catalog/workflow-smoke/](../benchmarks/catalog/workflow-smoke/) is a tiny, committed example you can
plan without running the full inventory. It contains six workflow-only cases (`workflow_only: true`): five 12-second
broadcasts that pause at 3 s and resume at 5 s (S24 -> M31, M31 -> ASUS and ASUS -> S24 over Classic; S24 -> M31 and
M31 -> ASUS over GATT), and one 16-second Classic call between S24 and ASUS with mute and video-off controls. They check that the plumbing works (launch,
authentication, controls, teardown, trace collection), not that quality passes. The input and suite paths inside it are
relative placeholders: the raw fixtures are not committed, so generate or bind your own (see Inputs) and edit the
paths and the phone aliases to match your registry before you run it.

```
python -B tools/run_benchmark_suite.py plan --suite benchmarks/catalog/workflow-smoke/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --parallel 1 --output benchmarks/catalog/WORKFLOW_PLAN_NEW
```

Cases whose participants are not all in `--phones` are left out of the selection but stay visible. Use the plan to
confirm your aliases and fixture paths, then `run` with an exact `--apk-sha256`. A workflow check passing means the
harness worked on your phones; it is never a PASS under the acceptance gates.

## Inputs: supplied raw video and audio

New source workloads use declared generated raw media, licensed natural footage or a controlled non-private scene.
Keep synthetic, natural-stress and live Camera2 coverage separate: generated or raw playback does not exercise Camera2
sensor, stride or exposure behaviour. The input table below says what each input can and cannot measure.

| Input | Can measure | Separate requirements |
| --- | --- | --- |
| Synthetic raw | Selected encoder, delivery and decoder, declared stress and events | Natural demand, Camera2, physical pixels, optics, acoustics |
| Natural recorded raw | Repeatable real encoding and natural demand | Camera2 controls, physical blur and delay, acoustic recording |
| Finite encoded IVF replay | Transport and decoder isolation | Live encoding, Camera2 and microphone |
| Live Camera2 and microphone | Capture, control and the A/V pipeline | Aligned reference, physical delay, blur and acoustic quality |

Raw can loop with declared seams and frame maps; encoded replay cannot, so it must outlast setup plus measurement.
Recorded raw and replay orientation is distinct from sensor and display rotation. Native 50 -> 30 FPS sampling
produces non-uniform content steps; integer 120 -> 30 sampling is different, so keep those transformations when you
compare motion. Invalid dimensions or an exhausted replay cannot pass quality or freeze gates. Private calls reject
replay (their runner has none) and never silently fall back to Camera2.

### The raw-media contract

Current raw phone input is tightly packed 8-bit I420 with even geometry and an integer profile FPS: `width * height *
3 / 2 * frame_count` bytes. Broadcasts use `raw_fixture_file`, calls use `raw_fixture_files` keyed by each endpoint, and
chains bind the original source. Other formats or fractional FPS need a declared conversion and transformed hashes;
they are not reinterpreted. A correct file size alone does not validate colour, geometry or display. Each variant
needs:

- a stable pattern ID, generator and version, seed, parameters, provenance and licence, and a reproducible schedule;
- a lossless native reference with the exact frame count, byte layout and SHA-256 of every file (declare any
  conversion, crop, scale, rotation or subsampling);
- width, height, rational FPS and timebase, plane order and stride, chroma location, bit depth and the original colour
  range, primaries, matrix and transfer (or an explicit `UNSPECIFIED`); no hidden interpolation or speed change;
- per-frame IDs and event sidecars for motion, cuts, flashes, audio and loop boundaries;
- robust visible markers with checksum and redundancy, pixel bounds, the exact marked reference and a content ROI
  declared in advance (never change the ROI after seeing a bad score);
- for audio, the reference sample rate, channels, count, hash and event sample indices. A reference does not prove
  injection, routing, clock alignment, echo, lip sync or acoustic quality.

A `raw-fixtures.json` declares your files. Relative paths resolve beside the manifest:

```json
{
  "schema_version": 1,
  "fixtures": [{
    "width": 1280, "height": 720, "fps": 30,
    "format": "I420", "bit_depth": 8,
    "path": "native-1280x720-30.yuv", "sha256": "EXACT_RAW_SHA256", "frame_count": 150,
    "color": {"range": "limited", "primaries": "bt709", "matrix": "bt709", "transfer": "bt709", "chroma_location": "left"},
    "sidecars": {"frames": {"path": "frame-ids.csv", "sha256": "EXACT_SIDECAR_SHA256"}}
  }]
}
```

Replace every example value with real evidence. Sidecars are optional for integrity binding, but the full measurement
contract still needs the appropriate frame, event and audio evidence. Declare a lossless reference as
`sidecars.reference` with its hash. Include both geometry/FPS variants for an asymmetric call. Duplicate variants are
ambiguous; bind different clips separately.

### Binding inputs to cases

```
python -B tools/bind_benchmark_inputs.py --suite BUNDLE --case EXACT_CAMERA2_CASE_ID --fixtures benchmarks/inputs/MY_INPUT/raw-fixtures.json --devices tools/benchmark_devices.json --output benchmarks/catalog/BOUND_NEW
python -B tools/benchmark_case_state.py state --suite benchmarks/catalog/BOUND_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --apk-sha256 APK_SHA256 --output benchmarks/catalog/BOUND_NEW
python -B tools/run_benchmark_suite.py plan --suite benchmarks/catalog/BOUND_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --state benchmarks/catalog/BOUND_NEW/state.json --apk-sha256 APK_SHA256 --parallel 1 --output benchmarks/catalog/BOUND_NEW
```

The binder accepts explicitly selected, unbound Camera2 cases whose native geometry and FPS match (repeat `--case` for
more). It checks the file SHA, whole-frame bytes, frame count, layout and non-empty original colour declarations, and
writes a raw suite and bundle, a binding manifest and an exact `source-fixtures.json` copy. It does not generate,
resize or convert media, change FPS, or validate marker, ROI, frame-ID, audio or encoder colour behaviour. New raw case
IDs start NOT RUN and are separate coverage from the original Camera2 cases, which stay intact. Replay, camera-flip,
mixed and already-bound requests are rejected, and raw hashes are rechecked before app launch. Add
`--with-reference-audio` only to create separate A/V reference cases: it needs `sidecars.reference_audio`, and the
runner stages and rehashes native mono PCM16 at 48 kHz with no mixing, resampling, gain change or microphone fallback;
the WAV length must equal the raw-video loop in whole samples.

### The test-video generator

`tools/video-stress-generator/` (MIT licensed, Python package `videostress`) deterministically renders native-size
stress video with exact RGB and I420 references, per-frame IDs, events, optional PCM audio and SI/TI measurements. It
verifies source media only; encoding, transport and acceptance belong to this app's tools. It needs Python 3.11 or
newer, NumPy and Pillow (FFmpeg is optional).

```
cd tools/video-stress-generator
python -m pip install -e ".[test]"
python -m videostress --help
python -m videostress dry-run --config examples/sustained.json --mode 720x480@30
python -m videostress generate --config examples/sustained.json --mode 720x480@30 --output OUTPUT_NEW
python -m videostress validate OUTPUT_NEW/raw-fixtures.json
```

`dry-run` estimates bytes, memory and work first (a 60 s 720x480 at 30 FPS I420 reference is about 0.93 GB);
`generate` needs an unused output directory; `validate` re-checks hashes, the file contract, reference pixels, sidecars
and audio. `--mode` takes `WIDTHxHEIGHT@FPS` (even sizes, integer FPS) or a preset and can be repeated. Generate only
the modes your next comparison needs: one native source serves many phone roles, bitrates and encoders. Recipes in
`examples/`: `sustained` (60 s in six 10-second sections from quiet to an entropy burst), `mosaic` (60 s, four native
competing panels), `framing` (12 s calibration chart), `quick-mosaic` and `coverage` (20 s). Sustained and mosaic carry
twenty audiovisual cues and 40 s of seeded PCM activity. A duration that the selected FPS cannot represent exactly is
rejected, never rounded.

`tools/prepare_native_workload.py` wraps the generator for the benchmark workflow: it freezes the generator source and
recipe hashes (and refuses a generator that differs from the frozen implementation), checks the byte estimate before
writing, then generates and validates one selected mode. It uses `tools/video-stress-generator/` by default; set the
`VIDEO_STRESS_GENERATOR` environment variable (or pass `--producer`) to use a checkout elsewhere.

```
python -B tools/prepare_native_workload.py plan --recipe sustained --mode 720x480@30
python -B tools/prepare_native_workload.py prepare --recipe sustained --mode 720x480@30 --max-bytes 1900000000 --output benchmarks/inputs/SUSTAINED_INPUT_NEW
```

Planning writes nothing and opens no hardware; the estimate is not a disk quota. Reuse one input for several phone,
codec and rate cases. A 45 s settings sweep cannot cover a 60 s programme, so pick a planned active window that does.
Changing duration or settings creates a new case; loop seams stay scored and longer endurance is separate.

### Speech reference audio (speech-v1)

The synthetic loop (sines, chirps, noise) stays the timing and gap reference. `speech-v1` is a separate generated input
of openly licensed recorded speech for Opus at speech rates and decoded-PCM quality: a 60 s and a 20 s file, PCM16
mono 48 kHz, described in [../benchmarks/inputs/speech-v1/README.md](../benchmarks/inputs/speech-v1/README.md). The
audio and its sources are committed with their licences (attribution in
[../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)). Sources: LibriSpeech test-clean (15 voices; CC BY 4.0, Copyright 2014
Vassil Panayotov, https://www.openslr.org/12/ -- keep this attribution with any copy or derived file), LibriVox German
and Spanish readings (public domain) and a Kimiko Ishizaka Goldberg Variations clip (CC0). URLs, hashes, transcripts, the
mixing recipe and the per-segment map are in `speech-v1.provenance.json`.

```
python -m pip install -r tools/requirements-audio.txt
python -B tools/build_speech_reference.py fetch --output benchmarks/inputs/speech-v1-sources
python -B tools/build_speech_reference.py build --sources benchmarks/inputs/speech-v1-sources --output benchmarks/inputs/speech-v1-local
python -B tools/build_speech_reference.py verify --sources benchmarks/inputs/speech-v1-sources --build benchmarks/inputs/speech-v1-local
```

`fetch` needs the network (it streams a 347 MB archive once and keeps about 2.4 MB). `build` is deterministic from the
cached sources, and `verify` rebuilds in temporary scratch and compares every hash with the provenance. The tools write
only to new directories.

Loop rule: the runner needs reference-audio samples to equal the raw-video loop in whole samples. Pair the 60 s file
with 60 s native fixtures and the 20 s file with 20 s fixtures; nothing is padded, trimmed or tiled, so other loop
lengths (such as the 12 s framing recipe) have no speech reference. `build_speech_reference.py attach --fixtures
GENERATOR_OUTPUT/raw-fixtures.json --speech DIR --output NEW_DIR` points `sidecars.reference_audio` at the matching WAV
(excluded fixtures are listed); then bind with `--with-reference-audio`. Speech cases carry no cue schedule, so they
measure audio quality and dropouts, not A/V sync.

`tools/score_audio_quality.py` scores one finalized receiver PCM trace against the exact reference: gap events and
share, speech-frame dropout share, level error, the loudness-step response, and per-item STOI/ESTOI and PESQ. There are
no validated acceptance tolerances, so results are diagnostics, and speaker, microphone and air-path quality stays
UNMEASURED. ViSQOL runs only through `--visqol-binary` with an external build.

**PESQ warning (opt-in).** PESQ lives in a separate file, `tools/requirements-audio-pesq.txt`. The `pesq` package wraps
the ITU-T P.862 reference code, which carries ITU-T licence restrictions (read them: they limit modification,
commercial use and redistribution) that differ from this project's GPL-3.0-or-later licence. It is not part of the app
and `tools/requirements-audio.txt` does not install it. Run `python -m pip install -r tools/requirements-audio-pesq.txt`
only if you accept those terms; without `pesq` the PESQ score is reported as UNMEASURED.

## Measurement adapters and joining observations

The observation tools turn recordings and traces into hash-bound measurements that join a run. Install the
dependencies with `python -m pip install -r tools/requirements-measurements.txt`; FFmpeg and ffprobe must be on your
`PATH`. All inputs and specs are hash-bound and outputs must be fresh. Host regressions use temporary directories and
never add phone passes.

| Tool | Input and result |
| --- | --- |
| `score_input_complexity.py` | Unmarked native I420 reference and candidate with equal declarations; per-frame Sobel SI and temporal-difference TI (median, p95, maximum, CSVs). A numeric comparison cannot approve natural demand, encoder load or a phone |
| `score_visual_markers.py` | Native frames with frame, event and layout sidecars; decodes the fixed marker cells with checksum and ambiguity handling; counts missing, repeated and out-of-order frames, pacing, freezes and recovery. Screen sampling needs an independent calibrated mapping |
| `extract_measurement_capture.py` | A hashed recording becomes RGB24, original frame timestamps, a native PCM16 48 kHz WAV and `capture.json`; multichannel input needs an explicit `channel_index`; no resize, frame-rate synthesis or autorotation |
| `prepare_audio_track_observation.py` | The actual receiver WAV, chunk CSV and summary with run, device, PID, session and clock anchors. Missing, reset or stale timestamps forbid interpolation; AudioTrack estimates need recorded clock brackets, never an invented zero-time anchor |
| `score_audio_sync.py` | Injected reference WAV and events against a captured WAV with bounded cue searches; reports missing or ambiguous cues, clipping, A/V offset and content versus clock drift. Acoustic quality stays separate |
| `score_screen_geometry.py` | Declared calibration landmarks, an actual raster capture, an independent viewport and FIT or FILL mapping; measures orientation, aspect, crop and borders. Acceptance needs retained detector-validation evidence |
| `score_reference_regions.py` | Run by whole-phase fidelity when native sidecars exist: per-slot scores with the fixed content mask and per-panel PSNR and SSIM for a mosaic; supplemental only |
| `capture_benchmark_observation.py`, `score_physical_av.py` | Independent camera and microphone recording and its physical A/V observation (original PTS, two native ROIs, predeclared detector, search and interval). Generated, proxy or unregistered evidence cannot pass physical gates |
| `score_pipeline_trace.py` | Capture, encode, queue, write, receive, decode and release spans, load windows, lifecycle recovery, resource coverage, media overlap and optional cross-phone `media_age`. Unanchored clocks, duplicate PTS and unfinalized scopes keep it UNMEASURED |
| `analyze_perfetto_trace.py`, `score_impairment_trace.py` | A retained scheduler trace with a hash-frozen trace processor (never an RF or socket cause); a controlled-impairment log recomputed against its declared config (a harness result, not streaming performance) |

Observation scorers take `--spec SPEC.json --output OUTPUT_NEW.json`; a spec belongs beside the evidence it measures.
Imported recordings need their actual capture provenance recorded separately, because the importer cannot prove which
phone or session produced a file.

### Attaching adapters to a run

Prepare explicit bindings so you do not copy run, case and APK hashes by hand:

```json
{"schema":"benchmark-measurement-bindings-v1","adapters":[{"id":"receiver-markers","kind":"visual_markers","spec":"markers-spec.json","extraction":"capture/capture.json","scope":{"device":"RECEIVER_ALIAS","source":"SOURCE_ALIAS","pid":123,"session":"ACTUAL_TRACE_SESSION"}}]}
```

```
python -B tools/prepare_benchmark_measurements.py --manifest benchmarks/runs/ORIGINAL/CASE/physical/manifest.json --bindings benchmarks/runs/ORIGINAL/measurements/bindings.json --output benchmarks/runs/ORIGINAL/analysis/bindings
python -B tools/run_benchmark_suite.py analyze --manifest benchmarks/runs/ORIGINAL/CASE/physical/manifest.json --measurements benchmarks/runs/ORIGINAL/analysis/bindings/measurements.json --output benchmarks/runs/ANALYSIS_NEW
```

The helper validates the execution and capture scope and writes `measurements.json` and `preparation.json`; it never
infers source, PID, session, clocks, mapping or tolerances. READY means a valid binding, not a measured gate; INVALID
adapters stay visible and exit non-zero. `kind` is `visual_markers`, `audio_sync`, `screen_geometry`, `physical_av`,
`pipeline` or `impairment`. Adapter failures stay UNMEASURED with reasons, and measured values without validated
tolerances stay diagnostics. Native received IVFs and WAVs bind through finalized receiver `media_traces`. For another
recording or image, record `manifest.measurement_captures` (path, SHA-256, origin `DEVICE_CAPTURE` or
`EXTERNAL_RECORDING`, device, source, direction, PID, session, capture method, original clock CSV, and for framing the
viewport and frozen `requested_mapping`) before finalizing the run. Source files and generated charts are not captures.

Timing rules: captured access units use stage `offline_decode` and their source PTS can measure missing content but
never pass screen pacing; derived visual timing keeps the registered original capture clock; a video off and on starts
a new raw-video phase while audio continues, and the sound reference is never restarted to make a lifecycle test look
aligned. Cross-lane overlap needs `benchmark-active-media-intervals-v1` and measured `benchmark-clock-anchors-v1`
pairs; nominal job overlap stays UNMEASURED.

### Independent camera and microphone observation (optional)

A second device that watches the screens and speakers is the only evidence for picture timing and optical latency. A
bundle group may add `observation_capture:{path,sha256}`, a frozen `benchmark-observation-capture-v1` spec declaring
`controlled_nonprivate_target: true`, an independent `recorder` and container `clock`, hashed `ffmpeg` and `ffprobe`,
a native `video` and optional `audio` input, and per-direction `observation_plan` (ROI, search and interval controls,
frozen before recording and never narrowed after seeing a result). Device names are explicit DirectShow names.
`python -B tools/capture_benchmark_observation.py --spec CAPTURE_SPEC.json` validates the plan offline. A normal run
leases the camera and microphone with the phones, starts recording before the runner and stops and registers it before
release; host brackets are not camera or phone clock anchors. A failed recording stays INTERRUPTED with its bytes. A
recorder whose exit cannot be confirmed keeps its leases quarantined and blocks finalization: verify the actual process
stopped, resolve that exact lease and use a fresh execution.

A `physical_av` spec declares the hashed recording, `source_roi`, `receiver_roi`, `delay_search`, `detector` thresholds
and the `planned` interval; the source interval includes every intended brightness edge and the delay tail. Physical
acceptance needs hashed `benchmark-physical-av-validation-v1` evidence (calibration recordings, detector controls,
measured exposure, rolling shutter and acquisition A/V skew). Source timestamps, AudioTrack estimates, decoded WAVs,
generated checks and unregistered captures cannot supply it. The default cue patch repeats every 3 s with no unique ID,
so predeclare appropriately spaced optical events, and use absolute thresholds for short flashes.

For a quick optical estimate, film the source events and receiver pixels with a 120 or 240 FPS camera, use at least 20
matched events after startup and repeat at 1, 10 and 30 minutes for drift:

```
python -B tools/analyze_optical_latency.py --video OPTICAL_RUN/recording.mp4 --source-roi X,Y,W,H --receiver-roi X,Y,W,H --minimum-ms 0 --maximum-ms 6000 --output OPTICAL_RUN/optical-result.json
```

It uses the original per-frame container timestamps, reports each pair's sampling bounds and leaves repeated or
contested events ambiguous instead of pairing them greedily. Exposure, rolling shutter and thresholds still need
evidence, and scheduled playout is never physical latency.

### Controlled impairment

A controlled application impairment is an explicit new request: a broadcast `media_impairment={path,sha256}`, or call
`media_impairments` keyed by outgoing endpoint, with `media_trace=true`. The config is `benchmark-media-impairment-v1`
with a seed, `max_wait_ms` and ordered, non-overlapping phases `{id,start_ms,end_ms,delay_ms,jitter_ms,rate_bps,drop_every,burst_every,burst_length}`.
It changes the declared application load before encryption, not the radio conditions or normal settings, and it
validates its declared simulation separately from streaming performance.

## Tunables, link capacity and focused diagnostics

A case may carry `tunables`, exact debug launch extras applied to every participant: `stream_buffer_kib`,
`stream_flush`, `playback_delay_ms`, `decoder_low_latency`, `release_video_at_playout`, `source_gatt_priority`,
`forwarder_gatt_priority` and `decoder`. Unknown names fail the launch, and different tunables are different case IDs.
The LE link tunables that the switch register ([SWITCHES.md](SWITCHES.md)) describes (`coc_sequenced_phy`, `gatt_request_mtu`,
`gatt_aligned_writes`, `ble_phy`, `link_pacer_peer_seed`) work the same way. Every LE link logs `BENCHMARK le_conn_params`
(interval, latency, supervision timeout, status and payload bounds) whenever the stack reports a connection update; read
it before attributing an LE throughput result to a phone. Experimental voice calls and push-to-talk (default off) use
the tunables `voice_calls`, `push_to_talk` and `ptt_mode`; a call case with `call_type: "voice"` measures no video
gate, proves zero video packets in both directions and scores audio continuity in whole 2 s report windows. Acoustic
quality and gaps under one report window stay UNMEASURED.

Rotating-ID test P3 needs mutual verified contacts, which normally take two people scanning QR codes. On DEBUG builds `python -B tools/debug_verify_contacts.py --phones A,B` reads each phone's `BENCHMARK identity fingerprint=` log line (the apps must have been launched once), writes a one-shot token file plus the peer's fingerprint into each app's private storage with `run-as` (no other phone action), and prints the `--es test_verify_contact <token>` launch extras; the owning runner starts each phone with them and checks `BENCHMARK contact_verified source=debug` before the call. `--forget` prepares `test_forget_contact` for the Forget and Reset cases. The day roll uses the tunable `presence_day_offset` (extra `test_presence_day_offset`, same value on both phones, cold start only): it shifts the discovery wall clock by whole days instead of waiting for 00:00 UTC, so record it as a simulated clock, not a natural roll, and clear app data afterwards. ARCHITECTURE register row "Debug contact verification and day-roll clock" has the details.

Rotating-ID boundary tests can shorten the epoch: launch every phone of the run with `--ei test_rotid_epoch_s 60` (tunable `rotid_epoch_s`, 60-900, DEBUG builds only; the same value on all phones, otherwise rotating adverts fail the two-epoch check as stale) so three boundaries take about 3 minutes, and run `rotid_tracker.py --epoch-s 60`. Check `BENCHMARK rotid_epoch_s: ... applied=true` on each phone first; the extra is ignored in a process whose rotating discovery already started, so force-stop before the launch. It tests the boundary and restart machinery, not 900 s timing (Android's own 7-15 minute address timer does not scale, so the same-address-inside-an-epoch information is moot; the tracker's grace and early offsets default to a third and a sixth of the epoch). Results from short epochs are labelled with the value and never replace the 900 s cases. Afterwards a normal launch restarts the epoch counter by itself.

`python -B tools/link_capacity.py probe|relay|report` measures integrity-checked link maxima (GATT write and notify, CoC
primed, Classic), two-link relay airtime and each phone's learned link capacity. Probes restart the app on the
participating phones. `tools/deploy_build.py` freezes and installs a build (above).

These diagnostics support a failed stage; they are not competing acceptance entry points. They need exclusive access
to the phones they use and do not replace suite eligibility, frozen-build checks or run-bound observations.

- [../tools/run_device_benchmark.py](../tools/run_device_benchmark.py) and
  [../tools/run_private_call_benchmark.py](../tools/run_private_call_benchmark.py) define the supported actions; the
  latter also runs opaque relayed calls (case field `relays`, in hop order, up to four relays). An active call without
  every requested join is a failed shortcut or direct fallback. The app logs no relay forwarding counters or queues.
- Prefer recorded raw input when operator motion is not needed; otherwise announce the phones and duration, cue the
  source with a beep or vibration, signal unexpected loss and label planned kills. Input rejection is a terminal
  failure: requested raw geometry and FPS are never reinterpreted using negotiated dimensions.
- Trace the request and completion, read, decrypt and dispatch, writer and queue, audio lateness, decoder offer and
  output, and controls, with monotonic clocks and attempt, session, PID and generation identities, before claiming a
  cause. Capture Perfetto only during proven media; sleeping threads or HCI packet masks do not prove an RF, lock or
  socket cause, and observer overhead must be compared at fixed input.
- Single-run offline scorers: `score_device_run.py`, `score_motion_timing.py`, `score_presentation_timing.py` and
  `score_media_delivery.py --run-manifest RUN/manifest.json --receiver ALIAS --output-directory RUN/full-quality`
  (add `--sender ALIAS` for call fidelity). Codec-only diagnostics: `tools/run_android_encoder_case.ps1`,
  `tools/score_encoded_file.py`, `tools/calculate_bd_rate.py`.
- Resource and endurance runs retain the DEBUG extras (`--ez test_session_metrics true --es test_session_name
  CASE_PHONE --ei test_metrics_warmup_seconds 60 --ei test_metrics_duration_seconds 1800 --ei
  test_metrics_interval_seconds 1`), then `tools/pull_session_metrics.ps1` and `tools/collect_connected_cpu.ps1`. Use
  matched scene, brightness, ambient and start state and a randomized, role-reversed 30-minute A/B/A. USB charging or
  nominal Low Energy does not approve unplugged efficiency or endurance.
- Diagnostic payload and write-buffer overrides do not change acceptance; larger buffers or delays need explicit
  latency evidence.

## Running several people or sessions in parallel

Two testers (or one tester with a script) can use disjoint phones at the same time. The rules keep the evidence honest.

- **Exclusive ownership.** Each phone has exactly one owner at a time. Only the owner installs, instruments,
  launches, controls, force-stops or captures it. Write down who owns which aliases, cases and APK hash, and do not
  touch someone else's phone, adb sessions, pairing keys or router settings.
- **One frozen APK per comparison.** Record its SHA-256. If the APK changes, the comparison restarts; a pass on an
  older build never carries over.
- **Isolated A/B versus concurrent radio.** Parallel independent sessions share radio conditions, so record the
  actual first and last media intervals, participants and transports in comparable clocks. A scenario name or an
  intended overlap proves nothing, and `actual_media_overlap` stays UNMEASURED until an adapter measures it. During an
  isolated A/B (A/B/B/A order, only the declared variable changing), everyone else stays off the radio and does
  offline work such as scoring.
- **The runner's lanes.** `--parallel N` bounds active jobs. Disjoint concurrent cases refill a free lane only after
  cleanup and lease release; the first waiting isolated case is a barrier that runs alone. Leases reject aliases that
  name the same physical phone but do not control manual sessions; inspect a stale lease's owner and PID instead of
  clearing it. Hand a phone over only after the previous owner saved evidence, stopped the participants and released
  the leases, then re-plan and preflight.
- Do not repeat an unchanged failure or a missing-adapter case; change something, record why, and retest.

## Temporary debug images

Use [../tools/debug_image_lifecycle.py](../tools/debug_image_lifecycle.py) for temporary screenshots and inspection
sheets. Each output has one exclusive owner. Reserve a simple image name before the capture, register the finished
bytes, review them, then consume:

```
python -B tools/debug_image_lifecycle.py reserve --output benchmarks/runs/RUN_NEW/CASE --name ui-check.png --purpose 'Inspect connecting text'
python -B tools/debug_image_lifecycle.py register --output benchmarks/runs/RUN_NEW/CASE --name ui-check.png
python -B tools/debug_image_lifecycle.py consume --output benchmarks/runs/RUN_NEW/CASE --name ui-check.png
```

Write the capture to the printed `.debug-images` path between `reserve` and `register`. Python readers can use
`with DebugImages(output).review(name) as path:`; a successful return deletes the image, while a failed or interrupted
review keeps it PENDING. If a measurement still needs the pixels, `retain --purpose 'SPECIFIC_USE'` keeps them until
explicitly consumed. Changed bytes need another register and review. Never use the helper on original evidence or
reference inputs: the suite finalizes only its own fresh outputs and never sweeps source folders.

## Release checklist

Publishing is separate from acceptance: it changes no gate, and release notes must say which benchmark build IDs the
release was measured on. Signing happens only on the maintainer's machine (a keystore properties file outside the
repository); CI holds no signing key.

1. Everything for the release is committed and the tree is clean; `versionCode` is bumped in `app/build.gradle.kts`
   (`make_release.py` refuses to overwrite an existing `release/<versionName>-<versionCode>/`).
2. `./gradlew :app:testDebugUnitTest :app:compileGithubReleaseKotlin :app:compileFdroidReleaseKotlin :app:processGithubReleaseManifest :app:processFdroidReleaseManifest`, `MESHLIVE_REQUIRE_FLAVOUR_MANIFESTS=1 python -B -m pytest -q tools/tests/test_flavour_manifests.py` (the F-Droid manifest must have no install permission; without the variable the test skips when no manifest was merged) and `python -B -m unittest discover -s tools/tests` pass, and `python tools/fetch_native_sources.py --verify-only` reports libaom and libopus OK (without `--verify-only` it downloads and verifies the pinned sources; `make_release.py` refuses a release otherwise).
2b. Before a release that changes Gradle files, native code or toolchain pins, run `python -B tools/check_reproducible_build.py build ...` (ARCHITECTURE "Distribution and releases", reproducible builds) in two clean worktrees at different paths; any differing entry must be explained or fixed. Never during a phone window or next to another Gradle build.
2c. On every APK that goes to a phone or a release (frozen benchmark build, `make_release.py` output), run `python -B tools/dex_api_scan.py APK`: it reads the dex against the SDK's api-versions.xml and must report 0 unreviewed `java/`/`javax/` calls above minSdk (an `--allow` file lists reviewed ones; `android/` candidates are informational and must sit behind `SDK_INT` checks). The unit tests run on a newer Java than the phones, and a missing platform method is an `Error` that ends the app process (DECISIONS "Relayed calls: first phone probe"). `ApiLevelGuardTest` is the cheap source-level guard.
3. `python tools/make_release.py`. Publish the release as **Latest** (not a pre-release): the in-app update check reads `releases/latest`, which ignores pre-releases and drafts, and it needs the per-ABI APKs plus `SHA256SUMS` (README "Updates inside the app"). It builds the split `assembleGithubRelease` (the github flavour: opt-in updater and install permission; the F-Droid recipe builds the `fdroid` flavour) with `--no-daemon`, verifies every APK (single signer, expected certificate, native ABIs, 16 KB check on 64-bit libraries, versionCode = base * 10 + ABI digit, fastlane changelog present), and writes the per-ABI APKs, `MeshLive.apk` (a copy of the arm64-v8a APK; the README download button links `releases/latest/download/MeshLive.apk`), `SHA256SUMS`, `apksigner-certs.txt`, `signing-cert.sha256`, `SOURCE-MANIFEST.txt` (with the native-source pins) and a `RELEASE-NOTES.md` template to `release/<versionName>-<versionCode>/` (gitignored). Finish the notes (changes, benchmark build ids, known limits) by hand.
4. Install that exact APK on one idle phone and check that the About screen's signing-certificate fingerprint equals
   `signing-cert.sha256`, and that it matches the fingerprint published in the USER_GUIDE.md section "Verifying a release". Do
   no phone work during someone else's test window.
5. Tag `v<versionName>`, create the release as a draft, upload the APKs and `SHA256SUMS` first, then publish.
   `release-attest.yml` recomputes the manifest, attests it and attests the uploaded APK by digest. Publishing with no
   APK uploaded attests only the manifest.
6. Check the workflow run, then `gh attestation verify SOURCE-MANIFEST.txt --repo OWNER/REPO` and the same for the APK;
   paste the certificate fingerprint into the release notes.
7. Never put the keystore, its properties file or passwords in a commit, a release asset, CI secrets or a log. If the
   key may be exposed, stop releasing and rotate with a new application-signing plan (Android requires the same key
   for updates, so rotation needs Play key rotation or a new package).

The application ID is `io.github.meshlive.app`; the harness follows it and `MESHLIVE_APP_ID` overrides it for a
build with another ID. Never change it in a feature branch: a new ID cannot update installs of the old one.
