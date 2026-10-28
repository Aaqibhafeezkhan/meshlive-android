# Links and framing

Part of the [protocol reference](README.md): the records carried on every hop, the GATT byte stream and the compact-header and video-codec drafts.

## Hop and endpoint records

The `BMS1` hop header is 17 bytes:

| Field | Bytes |
| --- | ---: |
| Magic `BMS1` | 4 |
| Type | 1 |
| Sequence | 8 |
| Ciphertext length including GCM tag | 4 |

The whole header is AES-GCM associated data. The nonce is four zero bytes followed by the 64-bit sequence. Each direction starts at zero and requires the exact next sequence. Active types are endpoint handshake (`4`), endpoint content (`5`), immediate-child heartbeat (`6`), serialized mesh envelope (`7`), group relay control (`8`), immediate-route withdrawal (`9`) and withdrawal acknowledgement (`10`). Removed plaintext-video types `1`/`2` and congestion-report type `3` are rejected. Store-and-forward type `11` is rejected as unknown by every reader except one that explicitly opts in on a link it owns (see "Store-and-forward messages").

The `BME1` endpoint header is 18 bytes:

| Field | Bytes |
| --- | ---: |
| Magic `BME1` | 4 |
| Content type | 1 |
| Sequence | 8 |
| Packet flags | 1 |
| Ciphertext length including GCM tag | 4 |

The header is also AES-GCM associated data, using the sequence nonce under the endpoint directional key. Endpoint opening accepts increasing sequence gaps after media drops but rejects replay and reordering; this differs from exact-next `BMS1` hop ordering. Active types are stream header (`1`), media packet (`2`), lifecycle (`4`), keyframe request (`5`), private-call signal (`6`), wrapped broadcast-group key (`7`) and terminal acknowledgement (`8`). Congestion-report type `3` is rejected. Non-media content must have zero packet flags.

Media contains compressed video or Opus audio, distinguished by the audio flag. Visible flags permit relay routing and dependent-video drops until a recovery keyframe. The decrypted packet repeats those flags; a mismatch fails closed. Lifecycle payload is exactly one byte: `LIVE=1`, `PAUSED=2`, `ENDED=3`. Keyframe requests and terminal acknowledgements have empty payloads.

`BTV3` contains video format, display rotation, audio codec, sample rate and channel count. Each inner packet has length, flags, one shared monotonic presentation timestamp and one compressed video/audio payload. It is accepted only inside authenticated `BME1`; Android rejects `BTV1` and `BTV2`. The retained Windows normal receiver's older video-only BTV2 path still needs BTV3 audio/discovery integration.

Mesh routing and inner media flags use separate namespaces. `BMV1` reserves bit 4 for lifecycle and bit 5 for terminal End; `BTV3` uses those positions for DEBUG integrity and video discontinuity. Source serialization removes those two bits only from the outer media envelope. Full inner flags stay authenticated in `BME1` and repeated in the decrypted packet. An opaque relay checks outer media flags against the mapped metadata. Only a lifecycle End gets the outer terminal bit; a recovery keyframe must not stop discovery or child delivery. Bit 7 (`128`) marks a video packet as discardable (a temporal enhancement frame whose droppability the source verified from the AV1/VP9 bitstream). Only the Experimental temporal-layering option (off by default) sets it; receivers accept it either way, and with the option off no queue skips anything.

### Mid-stream size changes (no wire change)

The `BTV3` header names a stream's or call's maximum picture size; the encoder may start below it and a ladder (the Auto stream ladder, the call resolution ladder) restarts the encoder at another size mid-stream. The coded size of every keyframe is carried by its bitstream: the VP9 keyframe header (`frame_width_minus_1`, `frame_height_minus_1`) or the AV1 sequence header (`max_frame_width_minus_1`, `max_frame_height_minus_1`) in the keyframe's temporal unit or, for an encoder that sends it only as configuration, in its codec-config bytes. No field, flag bit or record type was added: a record that older receivers cannot parse (a display-rotation record with a longer payload, a configuration record that is not a configuration) would break them, so the size is read from the bitstream and an unreadable keyframe leaves the receiver's decoder as it is. The sender marks the first keyframe of an encoder restarted at another size with the existing `FLAG_DISCONTINUITY` (bit 5, the inner flag; the mesh envelope never carries it). A receiver drops every queued frame before a discontinuity keyframe. Receivers that predate size-aware decoding treat it as a recovery point (flush, then keyframe); they see no other change. A restart that keeps the size (camera flip, camera off/on) is not marked. A size-aware receiver re-creates its decoder when a keyframe's coded size, or the codec configuration that arrived with it, differs from the decoder's: new configuration is held back and becomes the new decoder's csd (MediaCodec accepts a changed configuration only together with a keyframe, and AV1 is not among the formats Android documents for mid-stream changes); configuration equal to the decoder's is queued as before. Recovery re-queues the newest configuration, never the stream-start one. A source or relay keeps for late joiners only the codec-config packets of the current encoder: a video configuration packet that follows video media starts a new batch and replaces the cached video packets (audio configuration is kept). A relay sees only the outer mesh flags, so it cannot use the discontinuity bit; a source also drops its cached video configuration at a discontinuity keyframe that was not preceded by new configuration. Receivers record the change as `BENCHMARK decoder_rebuild: reason=size|csd|size+csd | to=WxH ...` and the decoder's adaptive-playback support in `BENCHMARK decoder_format`. The viewer's display transform follows the new size through the existing video-format callback.

## GATT byte stream and draft lost-chunk recovery

### Chunks (current)

On a GATT link the byte stream (the BMH handshakes, then `BMS1` hop records) travels as ATT values: client writes without response in one direction and server notifications in the other. Every value starts with a type byte:

| Type | Layout | Reader |
| --- | --- | --- |
| `1` data | `01 \| sequence (u32, big-endian) \| payload (1 or more bytes)` | Sequences start at 0 per direction and connection and grow by one per value. A duplicate of a recently accepted sequence (the last 4,096) is ignored; a later sequence is held until the missing ones arrive (bounded); an earlier sequence that is not a recent duplicate fails the link. |
| `2` | anything | Ignored by every current reader (Android client and server, Windows, iOS), whatever its length. No current sender emits it. |
| other | | Windows and iOS fail the link; Android ignores the value. |

Chunk headers are not authenticated: the sequence only orders the bytes, and integrity comes from `BMS1`, whose exact-next record sequence fails closed on any missing, extra or altered byte. Since the lost-chunk fail-fast (default on, `test_gatt_gap_fast_fail`), an Android reader ends the stream when a sequence gap lasts 1 s or 128 later values wait behind it (DECISIONS "Design GR", option A).

### Draft: lost-chunk recovery `GR1` (Design GR option C; revised after review 1; Android code behind a default-off switch, not yet run on phones)

Switch: `test_gatt_chunk_recovery` (on by default since 2026-10-21, design decision Q-GR1-DEFAULT A; DEBUG `false` is the control arm; Android `GattChunkRecovery.kt`), and the same setting on Windows when built. Reasons, measurements and review 1: DECISIONS "Design GR". The constants below are part of the contract: every platform uses the same values.

**Sequence arithmetic.** Chunk sequences are compared modulo 2^32: `a` is after `b` when `(a - b) mod 2^32` is between 1 and 2^31 - 1. Every distance below is computed this way.

**Messages.** Every message is a type-2 value, so a reader without `GR1` ignores it:

```text
02 | 47 52 ("GR") | op (1) | body
op 1 HELLO  body: version (1) = 1 | history values (u16) | history bytes (u32) | history ms (u16)   13 bytes in all
op 2 NACK   body: count (1), 1..8 | count x ( first sequence (u32) | run (1), 1..255 )           4 + 1 + 5 x count bytes
op 3 GONE   body: first sequence (u32) | run (1), 1..255                                         9 bytes
op 4 HIGH   body: last data sequence sent (u32)                                                  8 bytes
```

All integers are big-endian. A NACK carries as many ranges as fit the link's value limit (three at the 20-byte limit of the default 23-byte MTU). A `GR1` value with an unknown op, a version other than 1, a wrong length, a count of 0 or above 8, a run of 0, or a HELLO announcing 0 values or 0 bytes is ignored. Any other type-2 value is ignored as before. `GR1` values carry no sequence of their own, never enter the byte stream, and do not count as hearing the peer (liveness and arrival measurements count data values only).

**Capability.**
- Each side sends HELLO when its end of the link can send values (the client after the notification descriptor write and the MTU exchange; the server after the client's descriptor write), and once more 1,000 ms later.
- HELLO announces the history it keeps. A reader clamps the announced numbers to this build's own limits (1,024 values, 262,144 bytes, 2,000 ms), so a larger claim cannot widen what it waits for.
- HELLO only tells the reader that it may wait for resends. A reader **waits** (instead of the option A fail-fast) only after it has received the peer's HELLO and its own switch is on.
- A sender keeps its history and answers NACKs whenever its own switch is on, from sequence 0, whether or not the peer's HELLO arrived: a NACK itself shows that the peer has `GR1`.
- If a HELLO is lost, only that direction's reader keeps option A.
- HELLO is unauthenticated, like the chunk header. Stripping it only downgrades to option A; forging it can only make this side send NACKs that an older peer ignores.

**Sender (switch on).**
1. **History.** It keeps every data value it sends, byte for byte, until the value is older than 2,000 ms, or the history holds more than 1,024 values or more than 262,144 bytes (oldest dropped first). HELLO announces these three numbers.
   - The 1,024-value bound keeps every resend inside the reader's 4,096-sequence duplicate window.
   - The history holds its own copy, never changed after the send. A resend is that copy; no path re-chunks or re-encrypts it.
2. **NACK.** For each requested sequence:
   - Already sent and still in history: resend the stored value unchanged (same type byte, same sequence, same payload), before any new data value.
   - Already sent but no longer held: answer GONE (consecutive sequences in one range).
   - Not yet sent (above the last sequence sent): skip it. After 8 such ranges on one link, ignore its NACKs for the rest of the connection and log it.
3. **Limits.**
   - Resent bytes per rolling second are at most 25% of the data bytes this link carried in the last second (its measured budget), and at least 1,024 bytes per second, so a quiet link can still resend.
   - Each sequence is resent at most 3 times per link.
   - A request beyond a limit is skipped, not queued, and logged (`event=capped`); the reader then fails at its deadline.
4. **HIGH.** When 200 ms pass after a data value with no further data value, the sender sends HIGH with the last data sequence sent; at most 2 HIGH per second. This lets a reader notice that the last value before a pause was lost.
5. **Pacing.** Resends and `GR1` values count against the link's pacing and byte window like any value. They never change the requested bitrate, frame rate or resolution.
6. **Sending.** Control values and resends go out before the next new data value. They are sent by the link's own writer (the data writer between values, or a recovery writer while it is idle), never from a Bluetooth receive callback: a blocking send there would deadlock. On Android a server notification waits for `onNotificationSent` and a client write for `onCharacteristicWrite`, both on the callback path. A value already handed to the stack is not preempted: an outstanding acknowledged write finishes first.

**Reader (switch on and the peer's HELLO received).**
1. **Missing sequences.** The reader keeps them as ranges.
   - When a data value is held behind a gap, every sequence from the next expected one up to the highest held one that has not arrived is missing.
   - A HIGH whose sequence is at or after the next expected one, and not yet received, makes the sequences from the next expected one through the HIGH sequence missing.
   - Targets are bounded: only sequences less than `min(peer's history values, 1,024)` after the next expected one are requested. A HIGH beyond that is ignored and logged.
2. **NACK.** It sends a NACK at once for missing sequences not requested in the last 250 ms.
   - Each sequence is requested at most 3 times: when first missing, then at 250 ms and 500 ms if still missing.
   - At most 20 NACK values per second per link.
   - NACK and HELLO go before any data value already queued in this side's own sending direction.
3. **Delivery.** A resent value is an ordinary data value: the reader delivers it at its place and releases what was held behind it. A copy that arrives after the original was delivered is a duplicate and is ignored.
4. **End.** The reader ends the stream when any of these happens:
   - GONE for a missing sequence: "GATT chunk N was lost and the peer no longer holds it".
   - A sequence still missing 1,000 ms after it was first missing: "GATT chunk N was lost (3 requests, T ms)".
   - More values or bytes held behind the gap than the peer's announced history: the sender can no longer have the chunk.
   - The option A 128-value limit does not apply while recovery is in use; the 1,000 ms deadline does.

**Records and encryption.** A resend is the original value, never a new encryption.
- Every `BMS1` record inside it keeps its sequence number, its nonce (four zero bytes and the 64-bit record sequence) and its ciphertext.
- The reader delivers each data sequence exactly once and in order, so `BMS1` receives the same byte stream the sender produced and its exact-next rule is unchanged. Endpoint `BME1` records and relayed ciphertext inside it are untouched.
- A sender must not rebuild a lost value by encrypting again: a record re-sealed under a new sequence would break the reader's record order, and one re-sealed under its old sequence would reuse a nonce.

**Scope.** `GR1` is hop-local: it is between the two ends of one GATT connection, and a relay never forwards a NACK or a resend to another hop. It covers notifications and writes without response. A client using acknowledged writes (the legacy Qualcomm relay-call flush policy, or a Windows write with response) loses no value silently: a request lost in the receiving host stalls the client's ATT transaction until its timeout ends the link. LE CoC, RFCOMM and the Windows normal receiver's other transports are unchanged.

**Threats.**
- An on-link attacker who can inject values could forge a NACK. It can make the sender resend ciphertext it already sent over the air, within 25% of the link's measured budget, and the reader ignores the copies.
- It could forge a GONE or a HIGH to end the stream, or forge a resend whose bytes fail `BMS1` and end the link. All three equal its existing power to inject a malformed data value.
- No plaintext, key or nonce is exposed or reused.
- Passive observers already see chunk headers. HELLO also shows that the build has recovery, which adds little to the GATT service UUID that already marks the app.

**Logs (Android).** Every line is `BENCHMARK gatt_recovery: event=<event> | ... | link=<client|server>`:

| Event | Fields |
| --- | --- |
| `hello_sent`, `hello` | `n`; the peer's clamped `historyValues`, `historyBytes`, `historyMs` |
| `nack`, `nack_received` | `seq`, `ranges`, `round`; `queued`, `gone`, `exhausted` |
| `resend` | `seq`, `bytes`, `queuedAheadMs` (from the NACK's arrival to the resend's send) |
| `capped` | `values`, `budgetBytesPerSecond` |
| `recovered` | `seq`, `count`, `waitedMs`, `rounds` |
| `gone_sent`, `high_sent` | `seq` |
| `failed`, `ignored`, `nacks_ignored`, `writer_stopped` | `reason` |

The DEBUG hook `test_gatt_drop_received_every=N` drops the first copy of every received data value whose sequence is N-1 mod N and logs `BENCHMARK gatt_debug_drop: seq=<s> | every=<N>`, so option A or recovery can be forced on any pair.

## Draft: compact headers and aggregation `CH1` (Design CH, draft 2026-10-27, not reviewed, not built)

Status: wire draft only. Nothing here is sent by any build. Reasons, savings and review questions: DECISIONS "Design CH". Every compact form below is a different encoding of the same logical record: AEAD keys, nonces, associated data, sequence rules and checks are those of `BMS1`, `BME1`, `BMV1` and `BTV3`, and a node can always rewrite a compact header into the full one without keys.

### Negotiation

| Scope | Signal | Meaning when both peers carry it |
| --- | --- | --- |
| Hop (one link) | Link-hello **decode-mask bit 6** (`0x40`) | `CH1` hop features: `BMS2` hop headers, `BMV2` stream indexes, hop type 15 `BUNDLE`, and on GATT the chunk type 3 |
| End to end | End-to-end hello **transport-mask bit 6** (`0x40`) | `CH1` endpoint features: `BME2` headers; on calls also content type 13 (compact media packet), `BTA3` audio batches and Opus packets of 10 to 60 ms |

- The link-hello transport mask has no free bit (3 `BDO1`, 4 `SETUP`, 5 reserved by the Classic draft, 6 call relay, 7 build tag). Decode-mask bits 0-1 are codecs and bit 7 is used only in end-to-end call hellos. Every current reader tests codec bits with a mask, so an unknown decode bit in a link hello is ignored. Senders must never copy bit 6 into an announcement's capability summary, where `0x40` means audio-only.
- A bit is signed inside the hello, so it cannot be added or stripped without breaking the handshake. Clearing it is always allowed and gives the current wire exactly.
- Every compact form is self-describing by its first byte (below). A sender may switch to it at any record after it has read the peer's hello carrying the bit, and a reader that advertised the bit accepts both forms on every record. No switch-over point needs to be agreed. A reader that did not advertise the bit treats a compact form exactly as today: an invalid magic or unknown type closes the link.

### `BMS2` hop header

```text
byte 0      : 0x80 | type          (type = the BMS1 hop record type, 1..127)
bytes 1..n  : ciphertext length incl. 16-byte tag, unsigned LEB128, minimal encoding, 1..4 bytes
then        : ciphertext and tag, exactly as BMS1
```

- `BMS1` starts with `0x42` (`B`), and a `BMS2` first byte is `0x80` or more, so the two forms never collide.
- The sequence is implicit. It is the hop counter both sides already keep, because `BMS1` requires exactly the next sequence.
- **Associated data is the canonical 17-byte `BMS1` header** (`BMS1`, type, u64 sequence, u32 length), rebuilt by the reader. The nonce is unchanged: 4 zero bytes followed by the u64 sequence.
- A non-minimal or 5-byte length, a length above the reader's limit, or type 0 fails closed. The header is 2 to 4 bytes instead of 17.

### `BME2` endpoint header

```text
byte 0      : 0x80 | content type
byte 1      : packet flags (as BME1)
bytes 2..3  : low 16 bits of the absolute sequence (u16, big-endian)
bytes       : ciphertext length incl. tag, unsigned LEB128, minimal, 1..4 bytes
then        : ciphertext and tag, exactly as BME1
```

- **Associated data is the canonical 18-byte `BME1` header** with the absolute sequence. The nonce is unchanged.
- The opener rebuilds the absolute sequence as the smallest value greater than its last accepted sequence whose low 16 bits match. This keeps the current rule (strictly increasing; gaps allowed after media drops), whoever dropped records: the sender, a relay or a forwarder.
  - A replayed or reordered record rebuilds to a later sequence than it was sealed with, so its tag fails.
  - A run of more than 65,535 consecutive missing records cannot be rebuilt and fails closed, as a lost link would. A forwarder that drops that many records towards one child sends that child a full `BME1` header.
- The header is 5 to 8 bytes instead of 18.
- **Rewriting.** A node that copies endpoint records it cannot open (an opaque call relay, a stream forwarder towards its child) may pass `BME2` on unchanged only to a next hop whose endpoint session negotiated `CH1`; otherwise it rewrites the header to `BME1` from the absolute sequence it tracks for that stream. The ciphertext never changes.
  - A call relay, even of an older build, copies records unchanged, because both call endpoints negotiated end to end and drops do not break the rebuild.

### Compact media packet (content type 13, calls only)

```text
plaintext   : PTS delta (signed, zigzag LEB128, minimal) | packet payload (to the end)
```

- The packet flags are the record's authenticated `BME2` flags; there is no repeated flag byte and no length field.
- `PTS = previous PTS of the same class + delta`. The classes are audio, video and other (display rotation), and each starts at 0 after the stream header.
- It replaces `BTV3` media packets on call endpoints that both carry the end-to-end bit (direct and relayed calls).
- Fragments (type 9) keep carrying an encoded `BTV3` packet, whose 13 bytes are paid once per fragmented frame.
- Streams keep `BTV3`: a forwarder copies the source's sealed packet, so every receiver of one sealing must understand it.

### `BTA3` audio batch (calls only, in an audio media packet with flags `AUDIO | AUDIO_PAIR`)

```text
magic 'BTA3' (4) | count (1, 2..20) | unit duration code (1: 1 = 10 ms, 2 = 20 ms, 3 = 40 ms, 4 = 60 ms)
| count x unit length (unsigned LEB128, minimal, 1..2 bytes, each 1..1275) | units back to back
```

- Unit `i` has `PTS = packet PTS + i x duration`.
- A sender whose units are not evenly spaced (DTX, a capture gap) uses `BTA1`/`BTA2` as today.
- Readers keep accepting `BTA1` and `BTA2`.
- Opus packets of 10 to 60 ms (one frame per packet, TOC config by duration) are allowed only between call endpoints that both carry the end-to-end bit. Everything else stays 20 ms.

### `BMV2` mesh stream index (hop type 7 payload)

```text
byte 0      : 0x80 | stream index (0..15)   (BMV1 starts with 0x42)
bytes 1..2  : low 16 bits of the mesh sequence (u16, big-endian), rebuilt as for BME2
byte        : TTL
byte        : mesh flags
then        : the endpoint record (BME1 or BME2), to the end of the hop record
```

- **Index assignment.** On each link direction, the first envelope of a (stream ID, source node ID) pair is always a full `BMV1`. That pair takes the next free index in order of first appearance (0, 1, ...). Hop ordering is exact, so both sides assign the same index.
  - A pair beyond index 15 stays `BMV1`.
  - An index is never reused on that link.
- The reader applies every current `BMV1` check (source equals the endpoint peer, TTL, increasing sequence, duplicate window) to the rebuilt envelope.
- A forwarder rebuilds the full envelope before deciding what to send to each child, and encodes per child.
- The header is 5 bytes instead of 50.

### `BUNDLE` (hop type 15)

```text
plaintext   : item count (1, 2..64) | count x [ inner hop type (1) | length (unsigned LEB128, minimal) | inner plaintext ]
```

- Inner types allowed: 5 (endpoint content), 6 (heartbeat or report) and 7 (mesh envelope). Any other type, an empty item, or trailing bytes fails closed.
- The items are processed in order, exactly as consecutive hop records would be. The bundle uses one hop sequence number and one tag.
- A sender bundles only records that are already queued when it flushes; it never waits to fill a bundle.

### GATT chunk type 3

```text
03 | sequence (u16, big-endian) | payload (1 or more bytes)
```

- Its sequence is the low 16 bits of the type 1 sequence counter.
- A reader rebuilds the 32-bit sequence as the unique value within 32,768 of the next expected one. Duplicate, hold and gap rules are those of type 1; the 4,096-value duplicate window and the `GR1` limits fit inside 65,536.
- A sender uses type 3 only after it has read a peer link hello carrying the hop bit, so the handshake values themselves stay type 1.
- Windows and iOS readers fail on an unknown chunk type, so the bit is the only gate.

### What does not change

- Handshakes (`BMH2`, `BMH3`), `BDO1`, `BRS1`, `BFG1`, `BGK1`, labels and `BMC2` payloads, call video fragments, store-and-forward records, all AEAD keys, nonces and tags (16 bytes).
- The exact-next hop sequence, the increasing endpoint sequence, mesh replay and duplicate rules, admission and overload guards.
- Separately reviewed proposals, not part of `CH1`: one AEAD layer on direct calls, and amortised signatures on audio-only live records (DECISIONS "Design CH", sections P3 and P6).

## Draft: HEVC and H.264 video codecs (Design HC, revision 3 after reviews 1 and 2, ready for implementation)

Status: wire draft only. Nothing sends it.
- Reasons, platform coverage, switches and open questions: DECISIONS "Design HC".
- Numbers: "Draft number registry". The H.264 numbers are the ones pencilled in DECISIONS "iPhone interoperability".
- With the masters `hevc_codec` and `h264_codec` off (the default before 0.4.2; on since), no HEVC or H.264 bit is set anywhere and every byte is the two-codec format.

### Numbers

| Where | H.264 (AVC) | HEVC (H.265) |
| --- | --- | --- |
| `BTV3` video codec ID | 4 | 5 |
| Encode and decode masks, link and end-to-end hellos | bit 2 (`0x04`) | bit 3 (`0x08`) |
| Announcement `capabilitySummary` (call offers and video stream offers) | bit 2 (`0x04`) | bit 7 (`0x80`) |
| Compact (iPhone) UUID codec bits | 56 (pencilled first) | none (proposed in open question Q-HC2: take bit 56, so H.264 has none) |

**End-to-end decode-mask bit 4** (`0x10`, `DECODE_AV1_SOFTWARE`) means "my AV1 decoding is software". It is carried only in end-to-end call hellos, like bit 7: link hellos never carry it, it is not a codec, and codec tests ignore it.

**One mapping table.** The hello bits and the summary bits differ: summary bit 3 is the dual-listening hint and bit 4 is relay consent. Implementations map codec ID, hello bit and summary bit with one explicit table (VP9 `0x01`, AV1 `0x02`, H.264 `0x04`, HEVC `0x80`), and never copy a hello mask into a summary. A video offer never sets bit 6; the audio-only marker stays exactly `0x40`. The pencilled end-to-end bit 6 ("prefer H.264") is not used and stays pencilled.

**Old readers.** Every shipped reader tests codec bits with a mask: Android uses bits 0-1 (`codecCapabilityMask`), iOS `& 0x3`, Windows `& 7` against a decode mask that has no bit 2. For them, summary bits 2 and 7 and hello bits 2 to 4 mean "no common codec" or nothing. Results:
- A 22-byte stream offer in only these codecs is listed dimmed as not decodable and cannot be joined.
- A PC announcement kind-2 offer (Design QV) with `0x80` is hidden by strict readers without HEVC (Android before 0.4.2, or with the HEVC master off); with the master on it is listed, and dimmed like any offer this phone cannot decode.
- A `BTV3` header with codec 4 or 5 fails closed as an unsupported codec.
- No reader rejects an unknown mask bit.

### Negotiation

- **Calls.** Each sender chooses for its own direction from the peer's end-to-end decode mask, which relays copy unchanged.
  1. A family `CallVp9Compatibility` policy on the sender gives VP9 when the selected codec is 3, 4 or 5.
  2. Peer bit 7 (prefer VP9), counted only when the peer also sets VP9 bit 0: a sender that can encode VP9 sends VP9, unless it has a hardware HEVC encoder and the peer decodes HEVC; then hardware HEVC comes first and VP9 second. Bit 7 still puts VP9 above AV1, H.264 and software encoders.
  3. Otherwise the first eligible encoder whose codec the peer decodes, in the order hardware AV1, hardware HEVC, hardware VP9, hardware H.264, then today's software order. If peer bit 4 is set, AV1 moves below the sender's hardware HEVC and hardware VP9 encoders that the peer decodes, and only below those.

  These steps build one ordered, filtered list per direction, and fallback walks only that list. A codec whose prefer switch is off is not in the Automatic list at all. A manual choice the peer decodes is sent as chosen; steps 2 and 3 do not override it. A receiver checks the header's codec against its own advertised decode mask, as today.
- **Streams.** The source's order follows open question Q-HC1 (answered A on 2026-10-28: hardware AV1, hardware VP9, hardware HEVC, hardware H.264, then software, except that the Android app's bundled libvpx VP9 encoder sits between hardware HEVC and hardware H.264, tied to the coverage table in DECISIONS). The source checks no viewer. Admission refuses a viewer or forwarder whose decode mask lacks the codec's bit, as for any codec. A forwarder derives its offer summary from the authenticated header through the table.
- **Eligible encoders.**
  - Hardware only: MediaCodec `isHardwareAccelerated`, VideoToolbox with `RequireHardwareAcceleratedVideoEncoder`, Media Foundation hardware MFTs.
  - The size range and the measured or achievable frame rate cover the session.
  - CBR is explicit where the encoder declares it. An encoder whose only mode is CQ is not eligible. An encoder that declares no modes keeps its resolved mode, with the requested and effective modes logged.
  - B-frames and temporal layers are disabled.
- **Decode bits mean the whole wire envelope.** The H.264 bit means Constrained Baseline, Main and High (8-bit 4:2:0); the HEVC bit means Main. Each bit is advertised only when the full decode path is probed at the advertised maximum size and FPS; hardware or platform software decoders both count. A sender never infers decoder support from its own encoder list.
- **Compact discovery.** While Q-HC2 is open, HEVC is not representable. An iPhone does not stream HEVC, and compact call offers carry their VP9/AV1 bits and negotiate HEVC and H.264 in the hello. If Q-HC2 is answered A:
  - bit 56 means HEVC, and current decoders map it explicitly to and from summary `0x80` (never by shifting the summary);
  - shipped decoders map it to summary bit 2 and find no common codec;
  - no compact sender announces an H.264 stream.
- **PC call offer (PC announcement kind 0).** Its packed three-bit codec field (byte 8, bits 0-2, below the route cost in bits 3-6) carries VP9, AV1 and H.264 hints only. HEVC is never encoded there and is negotiated only in the hello. A PC always has a representable fallback (VP9).
- **Unchanged.** An open-broadcast source (`BOB1` `CARD`) uses VP9 or AV1 only. `BTL1` and the call fragment format are unchanged. The PC announcement's kind-2 summary rule (Design QV) accepts `0x80` while the HEVC master is on (Android 0.4.2).

### Sender rules (both codecs)

- **Byte stream.** Annex B, one access unit (AU) per `BTV3` video packet, exactly one coded picture in exactly one slice. The discardable flag (bit 7) is never set. Wider subsets (several slices, CRA) need a later revision with vectors.
- **No codec-config packet.** A `FLAG_CODEC_CONFIG` video packet is never sent for codecs 4 and 5.
- **Parameter sets** (HEVC VPS, SPS and PPS; H.264 SPS and PPS) go in-band, in that order, before the first slice of every keyframe AU, and in no other AU.
  - The sender merges the encoder's consecutive codec-config buffers into one cached set, replacing the cache when the encoder emits new ones.
  - It prepends the cached sets to every keyframe AU whose own sets are missing, and strips sets from non-keyframe AUs.
  - It does not ask the encoder to prepend them (`KEY_PREPEND_HEADER_TO_SYNC_FRAMES` is not used).
- **Keyframes.** A keyframe is an IDR: HEVC `IDR_W_RADL` (19) or `IDR_N_LP` (20), or H.264 type 5. CRA is not sent. The `BTV3` keyframe flag is set from the NAL type, never from the encoder's buffer flag. Closed GOP is configured where offered.
- **Keyframe requests.** A keyframe request must be answered by an IDR, judged from the emitted NAL types. If none follows within 1 s the request is repeated. After 3 misses the sender ends video with `no_idr` and the component is ineligible for this process.
- **Order.** No B-frames or reordered pictures, and `BTV3` presentation timestamps strictly increase.
  - HEVC: `sps_max_num_reorder_pics` 0.
  - H.264: only I and P slices, and either `pic_order_cnt_type` 2 or a VUI `bitstream_restriction` with `max_num_reorder_frames` 0.
- **Stripped.** SEI, AUD and filler data are removed.
- **Size changes.** As for VP9/AV1: a `FLAG_DISCONTINUITY` IDR whose in-band SPS carries the new size. The coded size is the SPS size minus its cropping:
  - HEVC: `pic_width_in_luma_samples` and `pic_height_in_luma_samples` minus twice the conformance-window offsets;
  - H.264: `(pic_width_in_mbs_minus1 + 1) x 16` and `(pic_height_in_map_units_minus1 + 1) x 16` minus twice the frame-crop offsets.

### Access-unit validation (receiver, fail closed)

A bounded structural parser checks every packet before a decoder sees it. It reads only the fields named here. Everything else in the codec syntax is left to the platform decoder: the parser is a gate, not a sanitizer, and an AU that passes still reaches that decoder.

**Outcomes.**
- A failed AU is dropped as corrupt video under the existing rules (drop, request a keyframe).
- A `FLAG_CODEC_CONFIG` video packet, or `BTV3` flag bit 7 (discardable), on codec 4 or 5 closes the session as an unsupported stream.
- Three rejected keyframes in a row within 10 s also close it.

**Scanning and escapes.** One pass over the payload finds start codes and NAL units and checks the escapes. Inside a NAL unit:
- `00 00 03` followed by `00`, `01`, `02` or `03` is an escape, and only that `03` is removed;
- `00 00 03` as the last bytes of the NAL unit is allowed;
- `00 00 00`, `00 00 01` and `00 00 02` end the NAL unit or are forbidden there;
- `00 00 03` followed by a byte above `03` rejects the AU.

**Parse budget.** Parameter-set parsing reads at most the first 512 unescaped bytes, and slice-header parsing the first 64. If a field this section needs lies beyond the budget, or a read passes the end, the AU is rejected. A `ue(v)` with more than 31 leading zeros, or an Exp-Golomb value that overflows 32 bits, also rejects it. Loops (scaling lists, HRD CPB entries, POC cycle offsets) are bounded by their specification maxima.

**Log only.** Values above the usual range are accepted up to the specification's maximum and logged as `au_unusual`.

1. **Framing.** The payload is 1 byte up to the `BTV3` video payload limit and starts with `00 00 01` or `00 00 00 01`. Zero bytes before a start code are trailing zeros. An AU has at most 8 NAL units, each of at least the NAL header size.
2. **NAL header.**
   - HEVC: `forbidden_zero_bit` 0, `nuh_layer_id` 0, `nuh_temporal_id_plus1` 1.
   - H.264: `forbidden_zero_bit` 0.
3. **Allowed types.** Any other type rejects the AU, including HEVC CRA (21), which is deferred.
   - HEVC: 0 and 1 (trailing), 19 and 20 (IDR), 32 VPS, 33 SPS, 34 PPS, 35 AUD, 38 filler, 39 and 40 SEI.
   - H.264: 1 (non-IDR slice), 5 (IDR slice), 6 SEI, 7 SPS, 8 PPS, 9 AUD, 12 filler.
4. **One picture, one slice.** Exactly one slice NAL unit per AU. Parameter sets, AUD and prefix SEI come before it; filler and HEVC suffix SEI come after it.
   - **HEVC slice header.** `first_slice_segment_in_pic_flag` must be 1. Then `no_output_of_prior_pics_flag` (IDR only) and `slice_pic_parameter_set_id`. Then `num_extra_slice_header_bits` reserved bits, the count taken from the referenced PPS. Then `slice_type`, which must be 1 (P) or 2 (I), and 2 in an IDR.
   - **H.264 slice header.** `first_mb_in_slice` 0, `slice_type` 0, 2, 5 or 7 (P or I; I only in an IDR), then `pic_parameter_set_id`.
5. **Keyframes and parameter-set activation.**
   - The keyframe flag is set if and only if the slice is an IDR.
   - An IDR AU carries exactly one VPS (HEVC), one SPS and one PPS, in that order, each at most 1,024 bytes.
   - The chain must resolve inside that AU: slice PPS ID to that PPS, PPS SPS ID to that SPS, and the HEVC SPS VPS ID to that VPS.
   - Only after the whole AU passes does this set become the active configuration, atomically. If it differs from the previous one, the decoder is flushed and rebuilt before the AU is queued.
   - A non-IDR AU carries no parameter set, and its slice must name the active PPS.
   - A rejected IDR leaves the previous active configuration. Non-IDR AUs are then dropped until the next valid IDR.
6. **Session envelope** (rules 8 and 9). Both coded dimensions, not just the visible ones, are bounded per axis. Either orientation is accepted: the larger side is compared with the larger header side. Crops use checked arithmetic. Crop offsets are in the codec's crop units (2 luma samples for 4:2:0 frames), and the visible width and height must stay at least 16.
   - H.264: each coded side is at most the `BTV3` header's maximum width or height rounded up to a multiple of 16 (macroblocks).
   - HEVC: each coded side is a multiple of 8 (the smallest MinCbSizeY) and exceeds its cropped (conformance-window) side by less than 64 (the largest coding tree block), and the cropped size fits the header's maximum width and height. An encoder that pads to its coding-tree size and crops back (NVIDIA: 426x240 coded 448x256) is accepted; the coded size stays below the header plus 64 per axis, and the DPB limit is unchanged.
7. **HEVC VPS.** `vps_max_layers_minus1` 0 and `vps_max_sub_layers_minus1` 0.
8. **HEVC SPS.**
   - `sps_max_sub_layers_minus1` 0, so `profile_tier_level` is the fixed 12-byte general part.
   - `general_profile_space` 0, and `general_profile_idc` 1 or `general_profile_compatibility_flag[1]` set (Main).
   - `chroma_format_idc` 1, and both bit depths 8.
   - `pic_width_in_luma_samples` and `pic_height_in_luma_samples` within the envelope; the conformance window as in rule 6.
   - `sps_max_num_reorder_pics` 0.
   - Log only: `general_level_idc` above 123, up to 186; `sps_max_dec_pic_buffering_minus1` above 5, up to 15.
9. **H.264 SPS.**
   - `profile_idc` 66, 77 or 100 (no constraint-flag requirement; rule 10 excludes the Baseline features we do not use).
   - For profile 100: `chroma_format_idc` 1, both bit depths 8, and scaling lists parsed and accepted.
   - `pic_order_cnt_type` 2, or 0 or 1 with VUI `bitstream_restriction_flag` 1 and `max_num_reorder_frames` 0.
   - `frame_mbs_only_flag` 1.
   - Macroblock width and height within the envelope; frame cropping as in rule 6.
   - Log only: `level_idc` above 41, up to 62; `max_num_ref_frames` above 4, up to 16.
10. **PPS.**
    - HEVC: `pps_pic_parameter_set_id` at most 63, and `num_extra_slice_header_bits` read for rule 4.
    - H.264: `pic_parameter_set_id` at most 255, `num_slice_groups_minus1` 0 (no FMO), `redundant_pic_cnt_present_flag` 0.
11. **Removed.** SEI, AUD and filler are removed before decoding; their contents are never parsed.

**Decoder input.**
- Decoders run in low-latency mode where offered: Android `KEY_LOW_LATENCY` when `FEATURE_LowLatency` is supported, Media Foundation `CODECAPI_AVLowLatencyMode`.
- Android queues the Annex B AU. csd comes from the active configuration: H.264 csd-0 SPS and csd-1 PPS; HEVC csd-0 VPS+SPS+PPS. The decoder is rebuilt when the sets or the size change.
- Apple converts to 4-byte length-prefixed NAL units, builds the format description from the keyframe's sets (`CMVideoFormatDescriptionCreateFromHEVCParameterSets` or `...FromH264ParameterSets`) and rebuilds the session when they change.
- Windows feeds Annex B to the `MFVideoFormat_HEVC` or `MFVideoFormat_H264` decoder MFT, which loads in the app's process.

**Logs.**
- `BENCHMARK encoder_choice: ... codec=HEVC|H264 | reason=...` (DECISIONS "Design HC").
- `BENCHMARK au_reject: codec= | rule=<1..11> | type= | bytes=`.
- `BENCHMARK au_unusual: codec= | field= | value=`.
- `BENCHMARK decoder_hold: codec= | p50_ms= | p95_ms=`.

**Vectors** (written with the code): a keyframe AU and a non-keyframe AU captured from each platform's hardware encoder for each codec, plus one negative AU per rule above.
