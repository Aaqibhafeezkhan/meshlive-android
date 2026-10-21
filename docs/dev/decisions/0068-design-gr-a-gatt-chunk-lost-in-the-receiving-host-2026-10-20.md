# Design GR: a GATT chunk lost in the receiving host (2026-10-20; option C revised after review 1 and built on Android behind a default-off switch)

**Status.**
- Option A (fail fast with a reason) is built behind `test_gatt_gap_fast_fail` (default on, no wire change).
- Option B (keep values within one link-layer PDU) was A/B tested from the PC side: it reduced the losses but did not remove them, so it is not proposed as a policy.
- Option C (lost-chunk recovery `GR1`): review 1 approved it with changes for a gated implementation. Revision 1 below answers each item.
  - Android code: `GattChunkRecovery.kt` behind `test_gatt_chunk_recovery` (Experimental, DEBUG, default off), with the DEBUG drop hook `test_gatt_drop_received_every`. Not yet compiled by the root or run on phones.
  - Windows: not built.
  - Wire rules: PROTOCOL "GATT byte stream and draft lost-chunk recovery".

**Problem.** Three of twelve calls from a Windows PC to a Redmi Note 9S (Android 11, legacy stack, Qualcomm) ended with "Call peer stopped responding". In each, the Redmi's host logged `bt_hci_packet_fragmenter: reassemble_and_dispatch L2CAP packet too small (6 < 4). Dropping it.` then `got continuation for unknown packet. Dropping it.` about 8 s before the end. The ten clean calls have neither line. One notification (a 514-byte value, 521 bytes of L2CAP) never reached the app. `GattBytePipe` treated the missing sequence number as reordering, parked every later chunk and delivered nothing, so the call's 8 s liveness check ended it.

**Cause (sources).**
- Core specification: since erratum 10216, an ACL start fragment may be shorter than the 4-byte Basic L2CAP header (it may even be empty), so a sender may split the header across fragments. The Linux host gained support in commit 4d7ea8ee90e4 "Bluetooth: L2CAP: Fix handling fragmented length" (January 2021, citing Core v5.2 Vol 3 Part A section 1.4 and the erratum); Zephyr's host has a similar fix.
- AOSP legacy stack (`system/bt` `hci/src/packet_fragmenter.cc`, identical in android10-release, android11-release, android11-qpr3-release, android11-security-release, android-11.0.0_r48, android-security-11.0.0_r50/_r52 and android12-release): a start fragment with `acl_length < 2` is dropped (`invalid acl_length`). A 2-3 byte start is kept, because it already holds the L2CAP length. The continuation that follows a dropped start then finds no partial packet (`got continuation for unknown packet`). So every phone on this fragmenter loses a notification whose start fragment carries 0 or 1 bytes.
- The Redmi's message `(6 < 4)` and `(5 < 4)` does not match AOSP, which checks `acl_length < 2` first and prints `packet->len` against 2. The Redmi's build compares against the full 4-byte header, so it also drops 2-3 byte starts (packet length 6 = 4-byte HCI preamble + 2 bytes). That is vendor code; its source was not found (CodeLinaro not checked successfully). The `(5 < 4)` drop is a 1-byte start, which stock AOSP Android 11 would drop too.
- The GD assembler (`packages/modules/Bluetooth` `system/gd/hci/acl_manager/assembler.h`, main) waits for the next fragment when the start is shorter than the header, and drops only on a length mismatch.

**Exposure.** Receivers: phones on the legacy fragmenter (Android 12 and older, `BluetoothStackLineage` PLATFORM_SYSTEM_BT) for 0-1 byte starts, plus vendor builds like this Redmi's for 2-3 byte starts. Senders: only the PC has produced such fragments in our logs; phone senders have not. Which side cuts the fragment is unknown without an air capture. It is either the sending controller, or the receiving controller passing link-layer PDUs up to its host.

**Option B measured (PC side, 2026-10-20).** The PC capped its GATT values at 244 bytes (applied and confirmed). That is 251 bytes of L2CAP, one full link-layer PDU at the maximum data length. The early-drop rate fell from 3 of 10 calls to 1 of 10. Yet the Redmi still logged `L2CAP packet too small (5 < 4)` in 2 of the 10 capped calls. That is a 1-byte start for a PDU that needs no split, and stock AOSP Android 11 drops it too. A size cap therefore lowers the rate but cannot remove the loss, and losses must be recovered where they happen.

**Options.**

| Option | What it does | Wire | Cost and risk |
| --- | --- | --- | --- |
| A. Fail fast (built, default on) | The pipe ends the stream when a gap is held 1 s or behind 128 later chunks, with "GATT chunk N was lost (M later chunks waited T ms)"; a call ends with that reason after about 1 s instead of about 8 s. The window keeps room for a brief reorder across several ATT bearers (EATT); one bearer never reorders, and both senders (client writes, server notifications) send one chunk at a time; a busy write retries in place and any other failure ends the stream. `test_gatt_gap_fast_fail=false` is the control arm. | None | The call still ends; it only ends sooner and says why. |
| B. Avoid the fragmentation | Keep each notification within one 251-byte link-layer PDU: a 244-byte value is 247 bytes of ATT and 251 of L2CAP. A 514-byte value is 521 bytes, which is 251 + 251 + 19 over the air, so the cap may cost little or nothing when connection events are PDU-limited. On Android 11 and older the receiving client can ask for MTU 247 (`test_gatt_request_mtu=247`; Android 14 and newer ignore a request below 517). A receiver that is the GATT server cannot cap a client's values, so the sender must cap (the Windows 244-byte A/B). | None (MTU exchange and value size are standard) | Measured: it lowers the rate (3 of 10 to 1 of 10 early drops) but 1-byte starts still occur on a 251-byte PDU (2 of 10). Not enough alone. |
| C. Recover a lost chunk (`GR1`, proposed) | The reader asks for the missing sequence (NACK) in a type-2 value that older readers ignore; the sender resends the stored value byte for byte from a short history; the reader's existing reorder buffer and duplicate filter put it in place. Details below. | Type-2 messages on the GATT chunk layer; Android, Windows, later iOS. Older peers ignore them. | About 256 KiB of history per link while the peer supports it; control traffic only after a loss. Unauthenticated like the chunk header; review below. |
| D. Renew the link | Design SR (in-call link renewal) reopens the link after a one-way stall; a lost chunk is one such stall. | As Design SR | Heavier; covers more failures. |

**Recommendation.**
- Keep A as the backstop.
- Build C behind `test_gatt_chunk_recovery` (Experimental, default off) on Android and Windows after review. Then run the PC-to-Redmi call series with C on and off.
- Keep D (Design SR) for stalls that are not a single lost value.
- Do not add the B policy below. The Windows 244-byte cap can stay a PC-side choice only if its own throughput A/B shows no cost, because it lowers how often recovery is needed.

**Option C in detail (wire rules: PROTOCOL "GATT byte stream and draft lost-chunk recovery").**

*Why it works.* A loss in the receiving host removes one whole ATT value; the values before and after it arrive. The sequence number already shows which value is missing, and the reader already holds later values in order and drops duplicates. Only two things are missing: a way to ask, and a sender that still has the bytes.

*Carrier and old-peer interop.* All three readers (Android client and server, Windows `carrier.rs`, iOS `GattChunks.swift`) accept and ignore a type-2 value of any length, and no sender emits one. So `GR1` rides in type 2 with the prefix `47 52` ("GR") and needs no new type byte. An old peer never fails on it.

Every hello capability bit is taken: transport-mask bits 0-7 in link hellos are used, and bit 5 is reserved by the Classic exchange draft. The capability is therefore a `GR1` HELLO exchanged on the chunk layer itself. Its effect depends on the peers:

| Peers | What happens |
| --- | --- |
| Both have `GR1` on | HELLOs cross; each side keeps a history and answers NACKs; each reader recovers. |
| New reader, old sender | No HELLO from the peer: the reader keeps option A (fail fast in 1 s), sends no NACK. |
| Old reader, new sender | The old reader ignores the HELLO and never asks; the sender's history is never used. |
| A HELLO is lost | Sent twice (at link start and 1 s later). If both are lost, the reader that missed them keeps option A. The sender still answers NACKs, because it keeps its history whenever its own switch is on. |
| Windows or iOS build without `GR1` | As "old" above; nothing fails that does not fail today. |

*History size.* The sender keeps the values it sent in the last 2,000 ms, at most 1,024 values and 262,144 bytes, whichever is smallest.
- The reader gives up 1,000 ms after a value is first missing. So 2 s covers the deadline plus the time the NACK spends crossing.
- 256 KiB covers one second at about 2 Mbit/s, more than the application throughput an LE link reaches even at 2M PHY with the largest data length. Calls run far below it.
- 1,024 values keep every resend inside the reader's 4,096-sequence duplicate window, even at the 15-byte payload of a 23-byte MTU.
- The history keeps its own copy of each value (review 1 item 2), because the stack or a caller may reuse the array it was given. That is at most 256 KiB per link with the switch on. Windows moves each sent `Vec` into a ring.
- A sender keeps it from sequence 0 whenever its own switch is on (review 1 item 6), so a HELLO lost toward it costs nothing.

*Timing and rate.* The reader notices the gap when the next value arrives. That is usually the same or the next connection event, since the sender keeps sending media or heartbeats. A HIGH probe covers a value lost just before the sender went quiet.
- The NACK goes ahead of data queued in the reader's own direction, and the resend goes ahead of the sender's new data. One request therefore costs about two to four connection intervals plus at most one value already in flight each way: roughly 15-60 ms at 7.5-15 ms intervals.
- Rounds repeat at 250 ms and 500 ms if the NACK or the resend is lost too. The 1,000 ms deadline leaves room for three rounds.
- Rate limits (review 1 item 7):
  - Reader: 20 NACK values per second.
  - Sender: resent bytes per rolling second at most 25% of the data bytes the link carried in the last second (its measured budget), with a floor of 1,024 bytes per second so a quiet link can still resend. Each sequence is resent at most 3 times.
  - A skipped resend is logged (`event=capped`). These bound what a forged NACK or a lossy link can take from the media.
- Targets are bounded (review 1 item 4): a reader asks only for sequences less than `min(peer history, 1,024)` after the next expected one, keeps missing sequences as ranges, and ignores a HIGH beyond that bound. Sequence comparisons are modulo 2^32 (item 9).
- Resends count against the sender's pacing and byte window. They never change the requested bitrate, frame rate or resolution.

*Encryption and record order.* A resend is the stored value, never a new encryption.
- Every `BMS1` record inside it keeps its record sequence and therefore its nonce (four zero bytes and the 64-bit sequence) and its ciphertext.
- The reader delivers each chunk sequence once and in order, so `BMS1`'s exact-next rule sees the same bytes it would have seen without the loss. Endpoint `BME1` records and copied relay ciphertext ride inside unchanged.
- Rebuilding the value by encrypting again is forbidden. Under a new record sequence it would break record order; under the old one it would reuse a nonce.

*Interaction with option A.* While recovery is in use toward a peer, the 128-value limit is replaced by the peer's announced history: more held values or bytes than that, and the sender cannot have the chunk. The 1,000 ms deadline stays. GONE ends the stream at once with its reason. Without a peer HELLO, A is unchanged.

*Security review items.* The chunk layer has no authentication today, and `GR1` adds none:
- A forged NACK makes the sender resend ciphertext that was already on the air, within its rate limit, and the reader drops the copies.
- A forged GONE or HIGH, or a forged resend, can end the stream. That equals an attacker's existing power to inject a malformed data value, which already fails closed in `BMS1`.
- Stripping HELLO downgrades to A. Forging HELLO toward an old peer only makes this side send NACKs that the peer ignores.
- HELLO shows a passive observer that the build has recovery. The app's GATT service UUID already identifies the app.
- `GR1` is hop-local (one GATT connection), as required for every control.

*Threading (review 1 item 3, the blocker).* The first draft let the NACK handler send a resend itself. On Android that deadlocks:
- A server's `onCharacteristicWriteRequest` would block on the notification latch, which only `onNotificationSent` releases, on the same callback path.
- A client's `onCharacteristicChanged` would block likewise on the write latch.

So callbacks only parse and enqueue, and a writer drains the queue:
- `GattChunkRecovery` keeps a priority deque of control values and resends.
- The data writer drains it before each new data value, under the stream's lock.
- Each link with the switch on has one daemon thread, `gatt-recovery-client` or `gatt-recovery-server`. Every 50 ms it runs the timers (the second HELLO, HIGH, the reader's rounds and deadline) and drains the deque under the same lock while the data writer is idle.
- A value already handed to the stack is not preempted; an outstanding acknowledged write finishes first (item 10).
- Locks: the pipe's lock and the recovery lock never wait on a send, and neither is taken while the other is held in the opposite order.

*Platform impact.*
- **Android (built, default off).**
  - `GattChunkRecovery.kt`: `Gr1` codec and constants, `ResendHistory`, `RollingBytes`, `GattChunkRecovery` (sender, timers, writer thread) and `GattRecoveryReader` (missing ranges, rounds, deadline).
  - `GattBytePipe`: calls the reader, keeps option A when the peer sent no HELLO, and has the DEBUG drop hook.
  - `handleIncoming`: hands a type-2 value to the link's recovery, which parses and queues only; with the switch off type 2 is ignored as before.
  - `ChunkedGattOutputStream`: drains before each data value, records each data value in the history, and offers `sendPriority()` for the writer thread. The client and server outputs take the link's recovery.
  - With the switch off `GattChunkRecovery.forLink` returns null: no history, no thread, no type-2 value sent.
- **Windows** (`src/bluetooth/carrier.rs`):
  - `Pipe::offer` parses `GR1` instead of returning early on type 2, and stops counting type-2 values in its arrival measurements (review 1 item 1).
  - The pipe gains the reader state and the 1,000 ms deadline. Windows has no fail-fast today: it holds up to 4,096 values like the old Android pipe, so option A's deadline should land with this change.
  - `Writer` keeps the history ring and a resend queue drained before each new chunk. The worker that owns `LinkIo` must also wake for a resend when it is idle; today it runs only inside `write`. Its rule "partial failure never permits reuse/retry" stays: a resend repeats a value whose write completed, and a failed resend fails the link like any write.
  - `central.rs` (writes) and `peripheral.rs` (notifications) send resends through the same `LinkIo::send`, and the pacer counts them.
  - Tests: extend `tests/framing.rs` and `tests/gatt_carrier.rs` with the shared vectors.
- **iOS** (`GattChunks.swift`): the same reader and writer changes later. Until then iPhones behave as old peers.
- **Every platform** uses the same constants (item 11): 2,000 ms, 1,024 values, 262,144 bytes, HELLO repeat 1,000 ms, rounds at 250 ms, 3 requests, deadline 1,000 ms, 20 NACKs per second, 3 resends per sequence, 25% budget with a 1,024-byte floor, HIGH after 200 ms idle and at most 2 per second, 8 out-of-range ranges.
- **PRIVACY** at promotion: HELLO tells an observer of the link that the build has recovery (item 11).

*Expected outcome.*
- A single lost value (each logged loss on the Redmi was one notification) is recovered without ending the call: the call has a hitch of about the recovery time, typically under 100 ms and never more than 1,000 ms. The reader logs `gatt_recovery: event=recovered` with the wait.
- A call ends only if the same value is still missing after three rounds or the sender no longer holds it. It then ends within about 1 s with option A's reason.
- In the uncapped and capped runs, every logged loss was a single value (two values 0.8 s apart in one call, each a separate gap), so each would have been recovered.
- Option C does not cover acknowledged writes: a lost write request stalls that client's ATT transaction until its timeout. It also does not cover stalls where nothing at all arrives (option D).

**DeviceFamilyPolicy entry (not proposed; kept for the record).** The option B entry below is withdrawn by the B measurement: a cap does not remove 1-byte starts.
- ID `gatt-legacy-fragmenter-short-start`. Matcher: `BluetoothStackLineage` PLATFORM_SYSTEM_BT (Android 12 and older), receiving as GATT client.
- Behaviour: request MTU 247, so the peer's values fit one link-layer PDU. Log the requested and effective MTU in the existing `mtu_request` setup line.
- Sources: AOSP `packet_fragmenter.cc` (android11-release, android12-release), `assembler.h` (main), Linux 4d7ea8ee90e4 and erratum 10216.
- Evidence: the PC-to-Redmi calls above.
- Confidence: withdrawn (the B A/B above).
- Off-switch: `test_device_policy_off=gatt-legacy-fragmenter-short-start`.
- It would change the MTU, not a requested bitrate, frame rate or resolution.

**Test plan.**
- **Option A (built).**
  - JVM `ChunkGapPolicyTest`:
    - a gap held 1 s fails with its reason, and the reader gets it as an IOException;
    - 128 held values fail before 1 s;
    - a brief reorder delivers every byte;
    - a second gap left behind a filled one starts its own clock;
    - the control arm keeps waiting.
  - Phone: the PC-to-Redmi series with A on and off. With A on, each lost-chunk call ends about 1 s after the drop with "Call link lost: GATT chunk N was lost ...". With A off, the calls end "Call peer stopped responding".
- **Option C (Android built; review 1 item 12).**
  - JVM `GattChunkRecoveryTest`, two linked sides with a fake clock:
    - a single lost value is resent byte for byte and the stream continues;
    - the history keeps the original bytes when the sent array is reused;
    - a NACK callback only enqueues while the sender is blocked inside a send;
    - a lost NACK, then a lost resend, recover in rounds 2 and 3;
    - three unanswered rounds end at 1,000 ms;
    - GONE ends at once;
    - HIGH finds a tail loss;
    - an out-of-range HIGH and NACK are ignored, and NACKs stop after 8 bad ranges;
    - an oversized HELLO is clamped, and malformed values (version, count 0 or 9, run 0, lengths, unknown op) are ignored;
    - with one HELLO lost, only the informed reader waits and the other fails fast;
    - a NACK flood gets at most 25% of the measured budget;
    - a brief reorder costs at most a duplicate;
    - two losses are both requested and recovered;
    - a resend after the deadline is ignored;
    - a lossless soak sends no request or resend;
    - the history stays within its bounds (the per-link memory a relay multiplies);
    - an older reader ignores every `GR1` value;
    - type-2 values do not count as hearing the peer;
    - the DEBUG drop hook drops each chosen value once.
  - Still to do:
    - shared byte vectors replayed by Windows (and later iOS);
    - a `BMS1` round trip across a recovery;
    - Windows: exclude type 2 from arrival measurements.
  - Phone and PC:
    - Same-settings A/B on and off, using the DEBUG drop hook (`gatt_drop_received_every`) on phone pairs, including a legacy-budget pair, and the PC-to-Redmi series. Record recoveries, `waitedMs`, `queuedAheadMs` and resent bytes; check that media rate holds while resending.
    - Interop: one side on with a frozen older APK, a Windows build without `GR1`, and an iPhone build. Nothing must fail that does not fail today.
    - Pass: no call ends for a single lost value.

## Review 1 (2026-10-20, second reviewer): approve with changes, gated behind `test_gatt_chunk_recovery`

(1) Old Android/Windows/iOS readers ignore type 2 (GattVideoTransport.kt about 1753, carrier.rs about 142, GattChunks.swift about 48); Windows still counts type-2 values in its arrival measurements: exclude them. (2) Resends are byte-identical ciphertext under the same nonce: sound; state and test that history is never mutated after send and that no path re-chunks or re-seals. (3) **Blocker:** resending from the NACK handler deadlocks on Android (onCharacteristicWriteRequest blocks on notifyLatch, released by onNotificationSent on the same binder channel; the client likewise with writeLatch): callbacks only enqueue; a writer thread drains a priority queue; a test that fails if a callback blocks. (4) Bound HIGH/NACK targets to min(history, 1024) beyond the next expected sequence; keep missing sets as ranges. (5) Clamp HELLO to 1024 values / 256 KiB. (6) A sender keeps history whenever its own switch is on, from sequence 0; HELLO only tells the reader to wait for a resend. (7) Cap resends at a fraction (for example 25%) of the measured link budget, not 32 KiB/s. (8) Log `queuedAheadMs` per resend. (9) Define comparisons modulo 2^32. (10) A NACK can't preempt an outstanding acknowledged write. (11) Identical constants on every platform; note HELLO in PRIVACY at promotion. (12) Added tests: callback non-blocking, out-of-range HIGH/NACK, oversized HELLO, one-sided HELLO loss, NACK flood vs media at the legacy budget, reorder, simultaneous losses, post-deadline resend, zero-loss soak, relay memory, interop with frozen older APK/Windows/iOS builds, type 2 excluded from heardWithin/arrivals.

## Revision 1 (2026-10-20): answers to review 1

| Item | Answer |
| --- | --- |
| 1 Type 2 and arrival counts | Android counts data values only (`heardWithin` is set by a data value, never by a type-2 value; tested). Windows: exclude type 2 from its arrival measurements when `GR1` is built there. |
| 2 Immutability | The history stores its own copy at send time and gives each resend a fresh copy; no path re-chunks or re-encrypts (tested with a sender that overwrites every array it sends). |
| 3 Callback deadlock (blocker) | Callbacks parse and enqueue only; the data writer and one recovery writer thread per link send under the stream's lock (see "Threading"; tested with a sender blocked in a send). |
| 4 Bounded targets, ranges | Requests and HIGH only within `min(peer history, 1,024)` after the next expected sequence; missing sequences kept as ranges; an out-of-range HIGH is ignored and logged. |
| 5 HELLO clamp | A received HELLO is clamped to 1,024 values, 262,144 bytes and 2,000 ms; 0 values or 0 bytes is ignored. |
| 6 History from sequence 0 | A sender with the switch on keeps its history from the first value and answers any NACK; HELLO only lets the reader wait. |
| 7 Budget | Resends at most 25% of the data bytes sent in the last second (floor 1,024 bytes per second); `event=capped` when a resend is skipped. |
| 8 `queuedAheadMs` | Logged on every resend: from the NACK's arrival to the resend's send. |
| 9 Modulo 2^32 | `seqAfter(a, b) = a - b` in 32-bit arithmetic for every comparison in the new code and in the pipe's gap checks. The pipe's held-chunk map is still ordered by raw value, which assumes no wrap inside one connection (2^31 values). |
| 10 Acknowledged writes | Stated in PROTOCOL: a NACK waits behind a value already handed to the stack. |
| 11 Same constants, PRIVACY | Listed under "Platform impact"; the PRIVACY note waits for promotion. |
| 12 Tests | JVM tests as listed in the test plan; the shared vectors, the Windows tests and the phone, PC and interop runs are still to do. |
