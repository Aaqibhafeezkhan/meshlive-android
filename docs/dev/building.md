# Building from source

For developers: how to build MeshLive and run its tests. Flavours, toolchain pins and dependency updates are in the [build notes](architecture.md#build-notes); releases and signing are under [Distribution and releases](architecture.md#distribution-and-releases).

## Requirements

- JDK 21
- The Android SDK with NDK `28.2.13676358` and CMake `3.22.1`
- Perl and Python 3.11 or newer
- The .NET SDK, only for the C# desktop receiver in `receiver/`

## Build and test

```bash
python tools/fetch_native_sources.py                      # downloads and verifies the pinned libaom, libvpx and libopus (not stored in Git)
./gradlew :app:testDebugUnitTest :app:assembleGithubDebug # gradlew.bat on Windows
python -B -m pytest -q tools                              # host tool tests
```

The APK is `app/build/outputs/apk/github/debug/app-github-debug.apk`.

## Test on phones

Streaming, Bluetooth and codec behaviour cannot be proven on an emulator. To test on your own phones, copy `tools/benchmark_devices.example.json` to `tools/benchmark_devices.json` (Git ignores it) and list your phones. Scope, acceptance gates and the workflow are in [testing.md](testing.md). You do not need every reference phone; say which ones you used. Published test results are in [MeshLive/meshlive-benchmarks](https://github.com/MeshLive/meshlive-benchmarks).
