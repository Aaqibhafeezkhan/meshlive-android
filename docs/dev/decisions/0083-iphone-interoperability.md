# iPhone interoperability

**Design review required** for anything on the wire. Compact discovery (an iPhone-sized advertisement) is implemented
behind `test_ios_compact_discovery` (PROTOCOL.md "Experimental iPhone compact discovery"); the iOS client lives in the
sibling repository MeshLive/meshlive-ios. Done when an iPhone's call offer, viewer request and stream offer are found
and connected by every reference phone with the switch on, in both setup roles, with `scan_stats` counters and evidence
of whether each phone filters the masked UUID in its controller or falls back to host filtering (AOSP
`ScanManager.shouldUseAllPassFilter` moves a whole scanner to all-pass when it has more filters than free controller
slots; the switch doubles each scanner's slot use).

Optional H.264 is not coded (it would be default-off `test_h264_codec`). It would use `BTV3` codec 4, encode and decode
mask bit 2, `capabilitySummary` bit 2 or compact codec bit 56, and call-hello decode-mask bit 6 (`0x40`, prefer H.264);
all are free today and receivers already reject unknown codecs. Before coding, a PROTOCOL.md design and review must
settle: bit 7 (prefer VP9) wins when both preference bits are set; SPS/PPS delivery (replay the cached config before
each IDR, or set `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`, with receivers accepting in-band SPS/PPS); no B-frames
(`BTV3` timestamps are monotonic); decoders accepting any supported profile, not only Constrained Baseline; Annex B
access-unit validation; and an H.264-only iPhone failing cleanly against Android builds without it.
