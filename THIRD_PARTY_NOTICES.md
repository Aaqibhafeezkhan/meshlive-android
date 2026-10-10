# Third-party notices

MeshLive is Copyright (C) 2026 The MeshLive Authors and is licensed under the GNU General Public License,
version 3 or (at your option) any later version (see `LICENSE`). This file reproduces the licences and notices of
third-party code and data that MeshLive includes, ports, or uses. The native sources (libaom, libopus) are not
stored in this repository: `tools/fetch_native_sources.py` downloads the pinned versions and verifies them by
SHA-256 before a build.

## Alliance for Open Media libaom

The optional native AV1 encoder statically links libaom built from commit
`0006e96cdb595ff88950a2c664115335ca5517bf` of https://aomedia.googlesource.com/aom (a main-branch commit after
the v3.14.1 release). `tools/fetch_native_sources.py` checks the commit and a SHA-256 manifest of its tree.

### libaom licence (BSD-2-Clause)

```text
Copyright (c) 2016, Alliance for Open Media. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in
   the documentation and/or other materials provided with the
   distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

### Alliance for Open Media Patent License 1.0 (libaom PATENTS)

```text
Alliance for Open Media Patent License 1.0

1. License Terms.

1.1. Patent License. Subject to the terms and conditions of this License, each
     Licensor, on behalf of itself and successors in interest and assigns,
     grants Licensee a non-sublicensable, perpetual, worldwide, non-exclusive,
     no-charge, royalty-free, irrevocable (except as expressly stated in this
     License) patent license to its Necessary Claims to make, use, sell, offer
     for sale, import or distribute any Implementation.

1.2. Conditions.

1.2.1. Availability. As a condition to the grant of rights to Licensee to make,
       sell, offer for sale, import or distribute an Implementation under
       Section 1.1, Licensee must make its Necessary Claims available under
       this License, and must reproduce this License with any Implementation
       as follows:

       a. For distribution in source code, by including this License in the
          root directory of the source code with its Implementation.

       b. For distribution in any other form (including binary, object form,
          and/or hardware description code (e.g., HDL, RTL, Gate Level Netlist,
          GDSII, etc.)), by including this License in the documentation, legal
          notices, and/or other written materials provided with the
          Implementation.

1.2.2. Additional Conditions. This license is directly from Licensor to
       Licensee.  Licensee acknowledges as a condition of benefiting from it
       that no rights from Licensor are received from suppliers, distributors,
       or otherwise in connection with this License.

1.3. Defensive Termination. If any Licensee, its Affiliates, or its agents
     initiates patent litigation or files, maintains, or voluntarily
     participates in a lawsuit against another entity or any person asserting
     that any Implementation infringes Necessary Claims, any patent licenses
     granted under this License directly to the Licensee are immediately
     terminated as of the date of the initiation of action unless 1) that suit
     was in response to a corresponding suit regarding an Implementation first
     brought against an initiating entity, or 2) that suit was brought to
     enforce the terms of this License (including intervention in a third-party
     action by a Licensee).

1.4. Disclaimers. The Reference Implementation and Specification are provided
     "AS IS" and without warranty. The entire risk as to implementing or
     otherwise using the Reference Implementation or Specification is assumed
     by the implementer and user. Licensor expressly disclaims any warranties
     (express, implied, or otherwise), including implied warranties of
     merchantability, non-infringement, fitness for a particular purpose, or
     title, related to the material. IN NO EVENT WILL LICENSOR BE LIABLE TO
     ANY OTHER PARTY FOR LOST PROFITS OR ANY FORM OF INDIRECT, SPECIAL,
     INCIDENTAL, OR CONSEQUENTIAL DAMAGES OF ANY CHARACTER FROM ANY CAUSES OF
     ACTION OF ANY KIND WITH RESPECT TO THIS LICENSE, WHETHER BASED ON BREACH
     OF CONTRACT, TORT (INCLUDING NEGLIGENCE), OR OTHERWISE, AND WHETHER OR
     NOT THE OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

2. Definitions.

2.1. Affiliate.  "Affiliate" means an entity that directly or indirectly
     Controls, is Controlled by, or is under common Control of that party.

2.2. Control. "Control" means direct or indirect control of more than 50% of
     the voting power to elect directors of that corporation, or for any other
     entity, the power to direct management of such entity.

2.3. Decoder.  "Decoder" means any decoder that conforms fully with all
     non-optional portions of the Specification.

2.4. Encoder.  "Encoder" means any encoder that produces a bitstream that can
     be decoded by a Decoder only to the extent it produces such a bitstream.

2.5. Final Deliverable.  "Final Deliverable" means the final version of a
     deliverable approved by the Alliance for Open Media as a Final
     Deliverable.

2.6. Implementation.  "Implementation" means any implementation, including the
     Reference Implementation, that is an Encoder and/or a Decoder. An
     Implementation also includes components of an Implementation only to the
     extent they are used as part of an Implementation.

2.7. License. "License" means this license.

2.8. Licensee. "Licensee" means any person or entity who exercises patent
     rights granted under this License.

2.9. Licensor.  "Licensor" means (i) any Licensee that makes, sells, offers
     for sale, imports or distributes any Implementation, or (ii) a person
     or entity that has a licensing obligation to the Implementation as a
     result of its membership and/or participation in the Alliance for Open
     Media working group that developed the Specification.

2.10. Necessary Claims.  "Necessary Claims" means all claims of patents or
      patent applications, (a) that currently or at any time in the future,
      are owned or controlled by the Licensor, and (b) (i) would be an
      Essential Claim as defined by the W3C Policy as of February 5, 2004
      (https://www.w3.org/Consortium/Patent-Policy-20040205/#def-essential)
      as if the Specification was a W3C Recommendation; or (ii) are infringed
      by the Reference Implementation.

2.11. Reference Implementation. "Reference Implementation" means an Encoder
      and/or Decoder released by the Alliance for Open Media as a Final
      Deliverable.

2.12. Specification. "Specification" means the specification designated by
      the Alliance for Open Media as a Final Deliverable for which this
      License was issued.
```

## Xiph.Org libopus

The Experimental native call audio codec (DEBUG `test_call_native_opus`, default off) includes libopus 1.5.2
from the official release tarball `https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz`
(SHA-256 `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1`), statically linked into
`libbluetooth_video_native.so`. DRED, OSCE and deep-PLC (DNN models) are not built.

### libopus licence (BSD-3-Clause, libopus COPYING)

```text
Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic,
                    Jean-Marc Valin, Timothy B. Terriberry,
                    CSIRO, Gregory Maxwell, Mark Borgerding,
                    Erik de Castro Lopo, Mozilla, Amazon

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of Internet Society, IETF or IETF Trust, nor the
names of specific contributors, may be used to endorse or promote
products derived from this software without specific prior written
permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Opus is subject to the royalty-free patent licenses which are
specified at:

Xiph.Org Foundation:
https://datatracker.ietf.org/ipr/1524/

Microsoft Corporation:
https://datatracker.ietf.org/ipr/1914/

Broadcom Corporation:
https://datatracker.ietf.org/ipr/1526/
```

## WebRTC (algorithm ports)

Some classes port algorithms from the WebRTC project (https://webrtc.googlesource.com/src): EncoderBitrateAdjuster.kt (DynamicBitrateAdjuster), the experimental frame-rate adjuster arm in CameraVideoEncoder.kt, the experimental call audio concealment and silence-gated catch-up in AudioConcealment.kt (NetEq Expand/Merge/Accelerate ideas, simplified re-implementation; WebRTC revision a05204958f9d94aaa4488313ace4086c52a075f4), codec workarounds referenced in CodecPolicy.kt / DevicePolicyRegistry.kt, and the default singlecast resolution bitrate limits (rtc_base/experiments/encoder_info_settings.cc at a05204958f9d94aaa4488313ace4086c52a075f4) used by CallResolutionLadder.kt.

The ports are Kotlin re-implementations that follow the cited WebRTC code (and, for the resolution ladder, its
bitrate-limit table). They are covered by the WebRTC licence and additional IP rights grant reproduced in full below.

### WebRTC licence (BSD-3-Clause, WebRTC LICENSE)

```text
Copyright (c) 2011, The WebRTC project authors. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of Google nor the names of its contributors may
    be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### WebRTC additional IP rights grant (WebRTC PATENTS)

```text
Additional IP Rights Grant (Patents)

"This implementation" means the copyrightable works distributed by
Google as part of the WebRTC code package.

Google hereby grants to you a perpetual, worldwide, non-exclusive,
no-charge, irrevocable (except as stated in this section) patent
license to make, have made, use, offer to sell, sell, import,
transfer, and otherwise run, modify and propagate the contents of this
implementation of the WebRTC code package, where such license applies
only to those patent claims, both currently owned by Google and
acquired in the future, licensable by Google that are necessarily
infringed by this implementation of the WebRTC code package. This
grant does not include claims that would be infringed only as a
consequence of further modification of this implementation. If you or
your agent or exclusive licensee institute or order or agree to the
institution of patent litigation against any entity (including a
cross-claim or counterclaim in a lawsuit) alleging that this
implementation of the WebRTC code package or any code incorporated
within this implementation of the WebRTC code package constitutes
direct or contributory patent infringement, or inducement of patent
infringement, then any patent rights granted to you under this License
for this implementation of the WebRTC code package shall terminate as
of the date such litigation is filed.
```

## ZXing Android Embedded and ZXing core

The verification QR screen uses `com.journeyapps:zxing-android-embedded` 4.3.0
(https://github.com/journeyapps/zxing-android-embedded), which includes ZXing core
`com.google.zxing:core` 3.4.1 (https://github.com/zxing/zxing). Both run fully offline.

Copyright (C) 2012-2022 ZXing authors, Journey Mobile.
Copyright (C) 2007-2020 ZXing authors.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these files except in compliance with the License. You may obtain a copy of the
License at http://www.apache.org/licenses/LICENSE-2.0 (full text in "Apache License 2.0" below)

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

## Speech reference audio (benchmarks/inputs/speech-v1)

The benchmark speech reference (`benchmarks/inputs/speech-v1/`: 18 source FLAC files and the two mixed WAVs built from
them by `tools/build_speech_reference.py`, all included in this repository) mixes excerpts of these recordings (recipe, hashes and exact URLs in
`benchmarks/inputs/speech-v1/speech-v1.provenance.json`):

- LibriSpeech test-clean, Copyright (c) 2014 Vassil Panayotov, licensed CC BY 4.0
  (https://creativecommons.org/licenses/by/4.0/); V. Panayotov, G. Chen, D. Povey and S. Khudanpur, "LibriSpeech: an ASR
  corpus based on public domain audio books", ICASSP 2015. Source: https://www.openslr.org/12/. Excerpts are resampled,
  level-normalised, faded and mixed; this is an adapted work.
- LibriVox recordings of Klein Zaches (E.T.A. Hoffmann, German) and Don Quijote (Cervantes, Spanish): public domain,
  from the Internet Archive items `kleinzaches_1309_librivox` and `don_quijote_vol1_0706_librivox`.
- Kimiko Ishizaka, J.S. Bach Goldberg Variations BWV 988 (The Open Goldberg Variations), CC0 1.0, Internet Archive item
  `OpenGoldbergVariations`.

The LibriSpeech excerpts are licensed under the Creative Commons Attribution 4.0 International licence
(https://creativecommons.org/licenses/by/4.0/legalcode); the corpus notice is in
`benchmarks/inputs/speech-v1/sources/LICENSE-LibriSpeech.txt`. The source FLAC files are unmodified excerpts from the
corpus archive; the mixed WAVs are adapted works (resampled, level-normalised, faded and mixed), offered under the same
CC BY 4.0 terms. Per-file speaker IDs, transcripts, source URLs and SHA-256 hashes are in
`benchmarks/inputs/speech-v1/speech-v1.provenance.json`. The LibriVox and Open Goldberg Variations excerpts are in the
public domain (CC0 for the Goldberg recording). No warranty is given for any of these recordings.

## Test-video generator (tools/video-stress-generator)

The deterministic test-video generator in `tools/video-stress-generator/` is part of this project and is licensed
under the MIT licence (`tools/video-stress-generator/LICENSE`), Copyright (c) 2026 The MeshLive Authors. It is a
host tool and is not built into the app.

## Optional host tools with restricted licences (not part of the app)

`tools/requirements-audio-pesq.txt` installs the `pesq` PyPI package (ITU-T P.862.2 wideband PESQ) for
`tools/score_audio_quality.py`. Its Python wrapper is MIT-licensed, but it bundles the ITU-T P.862 reference
implementation, whose terms restrict modification, redistribution and commercial use
(https://github.com/ludlows/PESQ, header of `pesq/pesq.h`; https://www.itu.int/rec/T-REC-P.862). It is opt-in,
is never distributed with MeshLive, and is not covered by MeshLive's licence. Without it the scorer reports PESQ as
UNMEASURED.

## Runtime dependency licences (release APK)

Everything shipped in the release APK is free software; there is no Google Play Services, Firebase, analytics, ads or other proprietary SDK. Resolved versions are locked in `app/gradle.lockfile` (`releaseRuntimeClasspath`) and checksummed in `gradle/verification-metadata.xml`. All licences below are compatible with GPL-3.0-or-later (Apache-2.0 is compatible with GPLv3 only, which the project's "or later" terms satisfy).

| Component | Version | Licence |
| --- | --- | --- |
| AndroidX libraries (`androidx.*`: core, core-ktx, appcompat, activity, fragment, lifecycle, constraintlayout, recyclerview, viewpager(2), transition, emoji2, startup, profileinstaller, tracing, camera viewfinder, graphics-shapes and the other transitive modules in the lockfile) | see lockfile | Apache-2.0 |
| Material Components for Android (`com.google.android.material:material`) | 1.13.0 | Apache-2.0 |
| ZXing Android Embedded (`com.journeyapps:zxing-android-embedded`) | 4.3.0 | Apache-2.0 |
| ZXing core (`com.google.zxing:core`) | 3.4.1 | Apache-2.0 |
| Kotlin standard library, `org.jetbrains:annotations` | 2.1.20, 23.0.0 | Apache-2.0 |
| kotlinx.coroutines (core, android), kotlinx.atomicfu | 1.9.0, 0.28.0 | Apache-2.0 |
| Guava `listenablefuture` (empty stub), Error Prone annotations, AutoValue annotations, JSpecify | 1.0, 2.15.0, 1.6.3, 1.0.0 | Apache-2.0 |
| Espresso idle-resource (transitive through Material/AndroidX) | 3.7.0 | Apache-2.0 |
| libopus (experimental native call audio, statically linked, official release tarball) | 1.5.2 | BSD-3-Clause plus the Opus royalty-free patent licences |
| libaom (native AV1 encoder, statically linked, built from the pinned source) | commit 0006e96c (after v3.14.1) | BSD-2-Clause plus the Alliance for Open Media Patent License 1.0 |

Build-time only (not shipped): Android Gradle Plugin (Apache-2.0), Kotlin Gradle plugin (Apache-2.0), Android NDK r28 and CMake 3.22.1 (Apache-2.0 / BSD-3). Test-only: JUnit 4 (EPL-1.0), AndroidX Test and Espresso (Apache-2.0).

## Apache License 2.0

AndroidX, Material Components, ZXing, Kotlin, kotlinx and the other Apache-2.0 components listed above are
distributed under the following licence.

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
