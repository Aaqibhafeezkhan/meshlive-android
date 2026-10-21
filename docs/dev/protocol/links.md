# Links and framing

Part of the [protocol reference](README.md): the records carried on every hop and the GATT byte stream.

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

Switch: `test_gatt_chunk_recovery` (Experimental, DEBUG, default off; Android `GattChunkRecovery.kt`), and the same setting on Windows when built. Reasons, measurements and review 1: DECISIONS "Design GR". The constants below are part of the contract: every platform uses the same values.

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
