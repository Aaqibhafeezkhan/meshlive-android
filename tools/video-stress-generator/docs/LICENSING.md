# Licensing and inspected references

All pattern implementations and the original 5×7 bitmap font in `videostress/font.py` are project-authored MIT-licensed source assets. Generated fixtures contain no downloaded media, commercial test clips, font binaries or unlicensed assets. The project's [MIT license](../LICENSE) applies to its code/font. FFmpeg and dependency binaries are not bundled by this repository.

| Component | Inspected environment | License / redistribution record |
| --- | --- | --- |
| Python | 3.13.3 | Python Software Foundation licensing; supplied by the installed runtime |
| NumPy | 2.2.6 | BSD-3-Clause core; retain notices and bundled component licenses when redistributing its wheel |
| Pillow | 11.3.0 | Installed metadata declares MIT-CMU; retain its copyright/permission notice and any wheel component notices |
| pytest | 9.0.3 | MIT; test dependency |
| FFmpeg / ffprobe | 9.0 full Gyan build, GCC 16.1.0 | Installed `ffmpeg -L` declares GPL v3 or later. Build enables GPL/version3 and libx264; any binary redistribution must follow that actual build's licenses/source obligations |
| OpenCV / SciPy / scikit-image | 4.12.0.88 / 1.16.3 / 0.26.0 | Available optional libraries, unused by core generation. scikit-image is used only when installed for an independent SSIM test |

Actual tool paths, complete build flags, FFmpeg license output and encoder/filter inventories are recorded in the `environment.json` that every generation run writes beside its outputs. FFV1, libx264, SI/TI, PSNR, SSIM and libvmaf filters were present on the inspected machine. OpenCV is not required. No VMAF metric is implemented; its availability is not a result. Fractional FPS/HDR/high-bit-depth/other-chroma/alpha delivery and strict-CBR diagnostic adapters are unsupported.

Primary material inspected on October 4, 2026:

- [Aaron Parecki's stress-test catalogue](https://aaronpk.tv/stress-test/) supplied conceptual comparison context. Its clips/assets were not downloaded, copied or used as generated references. This is a separate custom engineering collection.
- [FFmpeg codec documentation](https://ffmpeg.org/ffmpeg-codecs.html) informed the explicit FFV1/reference and libx264 diagnostic settings. No lossy file is treated as ground truth.
- [FFmpeg filters documentation](https://ffmpeg.org/ffmpeg-filters.html) identifies `siti` as a legacy implementation of a superseded P.910 recommendation; the project declares its own algorithm/version and makes no current standard certification claim.
- [FFmpeg `vf_siti.c` primary source](https://www.ffmpeg.org/doxygen/trunk/vf__siti_8c_source.html) was inspected for Sobel/difference/range handling. VSG uses independently written NumPy formulas and keeps fractional range expansion; it does not copy the GPL implementation.
- [FFmpeg licensing guidance](https://ffmpeg.org/legal.html), [NumPy license](https://numpy.org/doc/stable/license.html) and [Pillow licensing](https://pillow.readthedocs.io/en/stable/about.html#license) were checked alongside installed package metadata. Online stable documentation can describe newer packages; the recorded environment versions are authoritative for generated artifacts.

The consumer binder (`tools/bind_benchmark_inputs.py` in the parent repository) and its requirements format were read without modifying the app. This project does not implement phone capture, transport faults, lifecycle tests, audio injection or an app acceptance ledger.
