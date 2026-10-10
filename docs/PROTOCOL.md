# Bluetooth media protocol

This reference defines the current authenticated application protocol over Classic RFCOMM, LE L2CAP CoC and LE GATT. All integers are network byte order. Plaintext compatibility and silent transport fallback are rejected.

## Security layers and participants

```text
Bluetooth transport: RFCOMM, LE CoC or bounded GATT byte stream
  BMH2 adjacent-link handshake: persistent signed identity + ephemeral P-256 ECDH
  BMS1 hop records: directional AES-256-GCM, ordered per Bluetooth connection
    BMH2 endpoint handshake: separate signature/KDF domain, opaque to a blind relay
    BME1 endpoint content: stream/media, lifecycle, timing and authenticated controls
    BFG1 forwarded-stream grant: source ID + stream ID + current media-open key
    BGK1 static group epoch key: independently wrapped for each final receiver
```

Direct streams use both handshakes. A direct stream or private call has one peer endpoint session. A normal viewer-forwarder is an admitted viewer and can decrypt for local playback. With forwarding enabled, it authenticates one child using fresh link and endpoint handshakes, wraps the current media-open key in `BFG1`, then copies unchanged upstream `BME1` ciphertext. The child checks the grant's source/stream IDs against its first authenticated mesh envelope and independently opens the shared media sequence. This is immediate-parent admission; the source does not maintain a graph-wide endpoint list, and forwarding adds no second codec or media decrypt/re-encrypt pass.

A forwarding chain has a separately authenticated endpoint session for each adjacent parent/child pair. Child keyframe requests and terminal acknowledgements are authenticated locally and regenerated toward the upstream parent, because adjacent sessions use different directional keys. Queue, decoder, thermal and bitrate telemetry is never forwarded graph-wide.

The hidden blind-relay harness has only adjacent-link keys and cannot open endpoint media. Its branched group mode creates one source `BGK1` epoch key and wraps it in an independent endpoint session for each final receiver. It can copy one shared encrypted media record to multiple children. This is distinct from the trusted normal viewer-forwarder; static key distribution does not implement live revocation or re-key.

## Identity and key agreement

- Android's persistent P-256 ECDSA signing key lives in Android Keystore. Windows uses a non-exportable named CNG user key.
- The node ID is the first 16 bytes of SHA-256 over the identity key's DER SubjectPublicKeyInfo.
- Each handshake creates fresh ephemeral P-256 ECDH keys and a 32-byte random nonce. The signed hello binds role, node ID, identity key, ephemeral key, nonce and transport/codec capabilities.
- Initiator/responder derive 64 bytes through HKDF-SHA-256, split into directional 32-byte AES keys.
- Link signatures and KDF use `BluetoothVideoMesh/LinkHandshake/1` and `BluetoothVideoMesh/LinkSession/1`.
- Endpoint signatures and KDF use `BluetoothVideoMesh/EndToEndHandshake/1` and `BluetoothVideoMesh/EndToEndSession/1`. A valid link hello is invalid in the endpoint domain.

`BMH2` is a clean cutover: `BMH1` peers cannot authenticate or downgrade the connection. Discovery-selected link identities must match the selected announcement prefix, including hybrid BLE-control/Classic-RFCOMM after operating-system pairing. Direct DEBUG connections still authenticate a persistent app identity but lack that discovery binding.

Classic link security is independent of the app's. Streams and forwarders have always used insecure RFCOMM; calls and relays use Android's secure RFCOMM, which requires a MITM-authenticated bond on both phones. The experimental Classic without pairing (the register in [ARCHITECTURE.md](ARCHITECTURE.md), off by default) makes every role insecure: Android pairs Just Works without storing a bond, so the radio link has no authenticated link key, as on LE CoC and GATT. Nothing in BMH2, BMS1, BME1, BFG1 or BGK1 changes, and the link and endpoint handshakes still bind the announced node prefix. An active radio attacker can therefore sit between the phones at the link layer and see record sizes and timing (the metadata a passive observer of LE links already sees), but cannot open, forge, reorder or replay records, and cannot impersonate either identity. Remembered Classic addresses are hints bound to an authenticated identity only after BMH2 succeeds on them; a relayed handshake can point a hint at a relay, which costs a failed or relayed attempt, never trust. A first contact by Classic inquiry needs the target to be discoverable, which exposes its name and public address to nearby scanners for the visibility period.

## Identity verification

Added 2026-10-10 (API only; the UI binds it). Fingerprints, codes and checks are computed locally from keys the BMH2 endpoint handshake already authenticates; no record format changes.

- **Why.** Discovery binds a link to the 48-bit (12-hex) announcement prefix, and calls/streams check the endpoint identity against that prefix. The node ID itself is 128 bits and the signed hello proves possession of the key, but an attacker who grinds about 2^48 P-256 keys gets one whose node ID starts with a victim's prefix and passes the prefix check. Comparing the full key fingerprint closes that gap for contacts the user has verified.
- **Fingerprint.** `SHA-256(identity SubjectPublicKeyInfo DER)`, the exact bytes `NodeId.fromPublicKey` hashes, so the first 16 bytes are the node ID and the first 6 the announcement prefix. Shown as 64 hex digits (16 groups of four). Each authenticated endpoint session (`EndToEndVideoSession.peerFingerprint`) carries the peer's fingerprint.
- **Safety code (Signal style).** Each party has its own 30-digit half: `h1 = SHA-512("BluetoothVideoMesh/IdentityVerification/SafetyCode/2" || fingerprint)`, `h(i+1) = SHA-512(h(i) || fingerprint)` up to `h5200`; group `g` (0-5) is the big-endian 40-bit integer from bytes `5g..5g+4` modulo 100000, zero-padded. The pair code is the two halves sorted as strings: 60 digits in twelve groups of five. Both phones show the same code for the same pair, and it never changes for the pair. Each half depends on one key only, so a man in the middle must find a key whose half equals the victim's half: a second preimage of about 2^100 tries, each costing 5,200 hashes and a key generation. There is no birthday shortcut (the version 1 code hashed both keys together, which allowed one, and was replaced before release). The QR comparison of the full 256-bit fingerprint remains the stronger path.
- **QR payload.** `BVMID1.<64 hex fingerprint>.<base64url, no padding, UTF-8 NFC display name>`. Encoders write uppercase hex (QR alphanumeric mode); parsers accept all-uppercase or all-lowercase hex. The parser rejects any other magic/version, field count, length or alphabet, mixed-case hex, padding, non-canonical base64, invalid UTF-8 and names that are blank, longer than 64 bytes, or contain control, format (bidi embeddings, overrides and isolates U+202A-202E and U+2066-2069, directional marks, zero-width characters, BOM), private-use, surrogate, unassigned or separator code points. Names are NFC-normalized. The display name is an unauthenticated label; only the fingerprint is compared.
- **Verified contacts.** `noBackupFilesDir/identity-verification/verified-contacts.bin`, sealed with its own non-exportable Keystore AES-256-GCM key (`bluetooth-video-mesh-verification-v1`). The file and key exist only after the first contact is verified. A peer becomes verified only when the scanned (QR) or confirmed (safety code) fingerprint equals the full fingerprint of an endpoint handshake completed in this process with that node ID; the last 1,024 authenticated peers are remembered by node ID for this. A second key with a verified contact's prefix cannot be marked verified until the old contact is explicitly forgotten.
- **Session check.** Every end-to-end handshake (calls through any relay chain, direct and forwarded streams, group endpoints) records the peer fingerprint and compares it with the verified contacts right after the signature verifies and before any content key is used. A verified contact with the same 48-bit prefix but a different fingerprint is `MISMATCH`: it is logged as `SECURITY IDENTITY MISMATCH`, the UI listener is notified, and by default (`MismatchPolicy.REFUSE`) the handshake fails with "possible impersonation". `WARN` continues and leaves the warning to the UI. With no verified contacts nothing changes.
- **Forwarded streams.** The forwarder's own fingerprint is checked in its handshake. The original source is known only by the 128-bit node ID that the authenticated forwarder states in its `BFG1` grant: the receiver compares it with verified contacts of the same prefix (equal node ID: verified; same prefix, different node ID: `MISMATCH` under the same policy). This trusts the forwarder, which is an admitted viewer holding the media key and could re-seal content anyway.
- **Group branches.** A group source that refuses one receiver drops only that receiver: it sends that receiver an empty `WRAPPED_KEY` control (the existing type, no new value). The receiver's endpoint cannot open it and fails closed, and the relay reports it gone. The other receivers keep their keys and the stream continues; the refused receiver is not counted, and its later controls are ignored.
- **Damaged store.** A store that exists but cannot be opened is `STORE_UNREADABLE`, with a cause: `CORRUPT_FILE` (AES-GCM authentication or structure fails: damaged, or sealed under a lost Keystore key) stays unreadable until the user resets the store (`resetVerifiedContacts()`); `KEYSTORE_ERROR` and `IO_ERROR` are retried on the next access. The cause is logged and UI listeners are told once per episode. The policy is fail-open (design decision): nobody is refused because of it, every peer counts as unverified meanwhile, and the store is never overwritten implicitly.
- **What it does not do.** It proves which key a phone holds, not who holds the phone. Unverified peers are still accepted exactly as before (prefix check only). The store has confidentiality and integrity against offline tampering (AES-GCM under a Keystore key) but no rollback or deletion protection: anyone who can delete or roll back app files on the phone can make a verified contact unverified (never falsely verified), which disables the mismatch refusal for that contact. Store-and-forward contact cards keep their own safety number (key plus mailbox key). A wipe or reinstall creates a new key, so the contact becomes unverified (new prefix), not a mismatch.

## Hop and endpoint records

The `BMS1` hop header is 17 bytes:

| Field | Bytes |
| --- | ---: |
| Magic `BMS1` | 4 |
| Type | 1 |
| Sequence | 8 |
| Ciphertext length including GCM tag | 4 |

The whole header is AES-GCM associated data. The nonce is four zero bytes followed by the 64-bit sequence. Each direction starts at zero and requires the exact next sequence. Active types are endpoint handshake (`4`), endpoint content (`5`), immediate-child heartbeat (`6`), serialized mesh envelope (`7`), group relay control (`8`), immediate-route withdrawal (`9`) and withdrawal acknowledgement (`10`). Removed plaintext-video types `1`/`2` and congestion-report type `3` are rejected. Experimental store-and-forward type `11` is rejected as unknown by every reader except one that explicitly opts in on a link it owns (see "Experimental store-and-forward messages").

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

## Private-call signaling

`BMC2` is an exact 23-byte payload inside endpoint-content type `6`:

| Field | Bytes |
| --- | ---: |
| Magic `BMC2` | 4 |
| Signal | 1 |
| Random non-zero call ID | 16 |
| Reason | 1 |
| Relay-hop count | 1 |

Signals are Invite (`1`), Ringing (`2`), Accept (`3`), Decline (`4`), Cancel (`5`), Hang-up (`6`), Camera off/on (`7`/`8`) and Microphone muted/unmuted (`9`/`10`). Reasons are None (`0`), Rejected (`1`), Busy (`2`), Unsupported (`3`), Normal (`4`) and Error (`5`). Decline accepts only Rejected/Busy/Unsupported; Hang-up accepts only Normal/Error; all other signals require None. Unknown values, invalid length, zero call ID and invalid state transitions are rejected.

Only Invite can carry a non-zero relay count: the number of relays between caller and callee, which the caller reads from the route cost of the offer it connects to. The wire parser accepts counts `0..15`; the app builds at most four relays (below). Both endpoints validate call ID and state before acting. Camera/microphone start after recipient acceptance. `BMC2` is never accepted as plaintext BLE control.

For the opaque call relay, A and C alone hold content keys. B copies both ordered encrypted directions through independent adjacent queues/liveness without camera, microphone, decode or re-encode. The envelope binds call ID, endpoint identities, direction and endpoint records; adjacent radio identity remains separate from endpoint identity.

### Private-call relay chains

A call may cross a chain of opaque relays: caller -> R1 -> ... -> Rn -> callee, with n at most four (`PrivateCallRelayChain.MAX_RELAYS`; the six required phones form at most four relays between two endpoints). On by default since 2026-10-09; DEBUG `test_call_relay_chain=false` (benchmark tunable `call_relay_chain`) restores the one-relay rule exactly. The call relay itself is DEBUG-only. No record type, flag or signal changes: chains reuse the discovery route cost, the BMH2 link hello's signed `maxChildren`, and the existing Invite relay count. With chains on, a call relay also sets transport-mask bit 6 (`0x40`, call relay) in the capabilities of its signed BMH2 link hello. Every reader tests only the transport bits it needs, so older peers ignore it; with chains off the hello is unchanged.

- **Downstream first, one parent, one child.** A relay authenticates its child with a fresh BMH2 link handshake before it advertises anything. Only then does it advertise the child's offer (the callee's node prefix and flags) at route cost + 1, accept exactly one parent within 180 s (`PARENT_WAIT_MS`: enough for three outer relays and the caller to connect), and leave the air (Classic too, with chains on; CoC and GATT already stopped advertising). A relay whose parent never comes stops and releases its child. Each relay serves one call and stops when either side closes.
- **Child admission (hop-local).** An offer at route cost 0 is the callee itself: the child's authenticated link identity must equal the announced prefix, exactly as for one relay. An offer at route cost 1..3 is another relay: the child's link identity must not be the announced callee (Classic refuses a direct link, as for a relayed caller), must not be this relay, and its signed link hello must declare relay capacity (`maxChildren` at least 1; call endpoints sign 0) and the call-relay bit (forwarding receivers and stream relays also sign `maxChildren` 1 but never the bit). Over Classic a relayed child is only the advertising device itself (its discovered address, or its exact bonded address or unique bonded name): no bonded-phone fallback and no inquiry, so a relay cannot attach to another call's relay or a receiver that happens to answer. A relay never takes an offer for its own prefix or above route cost 3, so no advertised count exceeds four.
- **Parent admission (hop-local).** A relay refuses a parent whose link identity is itself, its own child or the callee it relays for.
- **No loops.** A relay has a child before it advertises, and it takes one parent only after that. A cycle would need some relay to be another's child before that relay had a child of its own, so every chain is a path that ends at a route-cost-0 endpoint. The route cost grows by one at every hop and stops at four. No signal travels more than one hop: each relay reads its child's advertisement and its two adjacent link hellos only.
- **Caller limit.** The caller ignores and refuses an offer whose route cost exceeds four instead of clamping it into the Invite.
- **End-to-end identity.** The caller runs the BMH2 endpoint handshake through every relay and requires the endpoint identity to match the discovered callee prefix ("Call peer identity changed between discovery and connection"); the callee authenticates the caller the same way. Relays copy endpoint handshakes, ciphertext and ordered records unchanged and replace heartbeats with version-1 liveness, as one relay does.

**Malicious relay.** Route cost, prefix and flags in a BLE advertisement are unauthenticated hints, and a relay's signed `maxChildren` proves only that it chose to sign relay capacity. A malicious relay, or a phone that advertises a callee's prefix at any route cost, can therefore drop, delay or refuse the call, attract it to a longer route, or report a false relay count, which changes only the caller's relayed video cap. It cannot impersonate an endpoint: endpoint signatures and keys use their own domain, the node ID is a hash of the identity key, and an endpoint hello that a relay answers itself carries the relay's identity, which the caller's prefix check refuses. It cannot read, forge, reorder or replay media or call signals, because `BME1` and `BMC2` are sealed with endpoint keys it never holds, and a modified record fails the endpoint's open. It sees what one relay sees: adjacent identities, signed public endpoint hellos, and record sizes and timing. The 48-bit discovery prefix is the existing binding between a discovered person and an endpoint identity; chains add no weaker step.

Admission, overload and capacity guards stay hop-local: each relay still holds exactly one parent and one child, the endpoints keep their admission and rate rules, and relayed calls keep the relayed video cap (15 FPS, 110 kbps; an experimental measured cap is in the register in [ARCHITECTURE.md](ARCHITECTURE.md)). A relay that cannot keep up exerts backpressure on its parent link; a stalled hop ends the call through the existing liveness and close paths.

### Experimental voice calls and push-to-talk (off by default)

Voice calls add no signal, reason, capability bit or version; `BMC2` stays 23 bytes. The only change is ordering: Camera off (`7`) may now precede acceptance. A voice caller queues Invite and then Camera off in its ordered endpoint sequence. A callee with the experiment on treats Camera off while it is ringing as a voice offer; Camera on while ringing withdraws the offer. A voice answer queues Accept and then Camera off. Neither side starts a camera or video encoder, and no video packet, codec configuration or keyframe is sent. Each `BTV3` header still declares Opus audio and the codec/format that a later upgrade would use. A receiver creates its video decoder only when the first video packet arrives.

Existing Android peers, and peers with the experiment off, already accept Camera off/on in every call phase. They see a video call whose caller has video off, and they answer with their own video. The voice caller shows that video when the first frame renders. During the call, each endpoint controls only its own camera with Camera on/off, as in video calls: adding video is an upgrade, and turning off your own video is a downgrade. The voice layout appears while neither direction carries video. A negotiated two-sided switch, where one side asks the other to turn its camera on or off, would need a new signal value. The `BMC2` decoder rejects unknown signal values, so that value would need a capability gate and review; it is left for later.

### Call codec preference bit

In an end-to-end call hello, decode-mask bit 7 (`0x80`) means "decode VP9 when you can encode it; the other listed codecs are decodable fallbacks". A legacy Qualcomm phone (API 32 or older) sets it, with its real decoders, instead of advertising VP9 only. A sender that has a VP9 encoder sends VP9; one that has none sends another listed codec. The bit is not a codec: codec tests ignore it, link hellos never carry it, and relays copy the end-to-end hello unchanged, so direct and relayed calls decide alike. The control arm `test_call_codec_fallback=false` advertises VP9 only, as before.

### Experimental call video fragments (off by default)

`test_call_video_fragments` (benchmark tunable `call_video_fragments`) adds one endpoint content type, video fragment (`9`), for direct calls only. Its reason: a GATT write blocks for the air time of the whole record, so call audio waited 620-1193 ms behind one large video frame (calls-noloss-m31asus-51cc8028).

- **Negotiation.** An endpoint with the experiment on sets bit 7 (`0x80`) of the transport mask in its signed end-to-end hello. The link hello is unchanged. Existing decoders accept unknown transport bits and test only the transport bit they need. A sender fragments only when both hellos carry the bit and the call is direct (relay count 0). With the experiment off, neither the bit nor type `9` is ever sent, so the wire is exactly as before. A receiver that did not advertise the bit rejects type `9`; every other endpoint reader (streams, relays, forwarders) rejects it as an unknown type, exactly as before.
- **Format.** A video media packet whose encoded `BTV3` packet (13-byte header plus payload) exceeds 3,072 bytes is cut into chunks of at most 2,048 bytes. Each chunk is sealed as its own `BME1` record of type `9`, with the frame's packet flags in the header. The payload is a big-endian total length (4 bytes), a big-endian offset (4 bytes), and then the chunk. Fragments use the same directional key, nonce sequence and replay/reorder rejection as every endpoint record, and `BMS1` hop ordering is unchanged.
- **Interleaving.** Between two fragments the sender may write queued call audio media packets, and only those. The receiver accepts a frame's fragments only in order: the first at offset 0, each next one at the previous end, all with the same total and flags. The flags may not include audio or codec configuration, and the total is at most the largest endpoint plaintext. Once complete, the receiver decodes the concatenation as a `BTV3` media packet with the same flag check as a whole media packet.
- **Failure.** While a frame is unfinished, a non-audio media packet or a stream header fails the call closed, as does any gap, overlap, change of total or flags, empty chunk or overrun.
- **Metadata.** Fragment sizes and timing replace whole-frame record sizes as traffic metadata.

Push-to-talk adds no wire value. Holding the button sends Microphone unmuted (`10`), and releasing it sends Microphone muted (`9`). The microphone and Opus encoder keep running, and the sender drops captured audio while muted. Security is unchanged: all signals use the authenticated, ordered endpoint sequence. As with camera off today, a relay or radio observer can infer a voice call or a released talk button from the absence of video or audio records. That is record size/timing metadata.

## Experimental peer labels: handles, names, call subjects and stream titles (off by default)

DEBUG `test_peer_labels`; otherwise the Advanced setting "Experimental: names and titles". Naming design phase 1 with review fixes R1-R10 and R13 (design decisions are recorded in [DECISIONS.md](DECISIONS.md) under "Naming, uniform handle and relayed discovery"). With the switch off, every byte on air and on the link is unchanged. Not yet tested on phones.

**Handle (UI only, no wire change).** `prefix48 = SHA-256(identity SPKI DER)[0:6]` (the announcement node prefix). Encode `prefix48 << 2` (50 bits) as 10 Crockford Base32 symbols (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`), most significant first, shown `SSSSS-SSSSS`; the last symbol's low 2 bits are zero. Parsing is case-insensitive, maps `I`/`L` to `1` and `O` to `0`, ignores hyphens and spaces, and needs exactly 10 symbols with a zero tail. Vectors (`PeerLabelTest`, generated by the design's `handle_vectors.py`): `010203040506` -> `04106-1050R`, `a1b2c3d4e5f6` -> `M6SC7-N75YR`, `000000000000` -> `00000-00000`, `ffffffffffff` -> `ZZZZZ-ZZZZW`. People who are not your contact always show the full handle (R5: 25 bits are ground in minutes). Logs, `BleL2capPeer.label` and host tools keep the 12-hex prefix (R10). The handle proves nothing before connecting; after connecting it is the existing 48-bit prefix check. Only the full fingerprint (QR or safety code) verifies a person.

**Capability.** End-to-end hello transport-mask bit 5 (`0x20`) = "accepts and sends `LABELS`". It is never set in a link hello or a `BGK1` group hello. Old readers test only their own bits. A `LABELS` record is sent only when both endpoint hellos carry the bit; an opener that did not advertise it rejects type `10` as an unknown type, exactly as before.

**Signed label `BNL1`** (big-endian):

```text
"BNL1" | version 1 (1) | kind (1): 1 person name, 2 stream title, 3 call subject
| SPKI length (2, 1..512) | identity SubjectPublicKeyInfo DER
| sequence (4; counts within one binding only, starts at 0)
| binding type (1): 1 call ID, 2 stream ID | binding (16)
| text length (1, 1..64) | UTF-8 text
| signature length (2, 1..256) | ECDSA P-256 SHA-256 DER signature over
    "BluetoothVideoMesh/Label/1" || 0x00 || every byte before the signature length
```

There is no timestamp (R2: it leaked clock skew). Every label is bound to the `BMC2` call ID or a stream ID, so it cannot be replayed into another call or stream, and no per-signer state outlives the session. A direct stream that has no mesh stream ID binds to the all-zero stream ID; that binding is accepted only from the authenticated endpoint peer of that direct stream (a forwarded grant naming the zero stream ID gets no labels). Labels are signed with the existing identity key, once per binding.

**Container `LABELS`** (BME1 content type `10`, packet flags 0): `"BLB1" | count (1, 0..3) | count x (length (2) | BNL1)`. Count 0 is valid (R4).

**Receiver checks, in order.** Container lengths; label magic/version; known kind (unknown kinds are skipped, for later kinds); `SHA-256(SPKI)[0:16]` equals the expected signer (the call's endpoint peer; the direct stream's endpoint peer; for a forwarded stream the source node ID in `BFG1`); signature; binding equals this call ID or stream ID; the text is already canonical under the text rules below. A label that fails is dropped and `SECURITY LABEL REJECTED kind=… reason=…` is logged without the text; a higher sequence replaces a lower one within the session; more than one container per 2 s or eight per minute is ignored. A label never fails a call or stream.

**Text rules (`LabelText`).** Trim, collapse whitespace runs to one space, NFC. Byte limits: person name 32, stream title 48, call subject 64. Reject control, format (bidi controls, ZWJ/ZWNJ), private-use, surrogate, unassigned and line/paragraph separator code points; more than 3 consecutive combining marks; U+2713/2714/2705/2611/1F5F8; scripts outside UTS #39 Moderately Restrictive (one script plus Common/Inherited; Latin plus one other script except Cyrillic or Greek; Latin+Han+Hiragana/Katakana, +Bopomofo or +Hangul); and handle-shaped text: after NFKC and lowercasing, `^[0-9a-z]{5}-[0-9a-z]{5}$`, or text that parses as a full or short handle **and** contains a digit (R6: "Brave Tiger" stays valid). The unassigned check uses the receiver's Unicode version, so a newer character drops the name to the handle on an older phone (fails safe).

**Calls (direct and relay chains).** `LABELS` is an ordinary endpoint record; existing call relays copy it unparsed.
- Caller: one container (its name if set, and the subject) bound to the call ID, queued immediately before Invite in the ordered endpoint sequence.
- Callee: holds at most one container received before the Invite of the same session and verifies it against that Invite's call ID (R3).
- Callee's own name (R1): sent only immediately before Accept, or at Ringing when the caller is a verified contact (full fingerprint). A declined, cancelled or unanswered call never reveals the callee's name.

**Streams.** A stream hop exchanges `LABELS` only when both endpoint hellos carry bit 5 **and** the link peer is the endpoint peer (no opaque stream relay between, since stream relays inspect BME1 types). Then exactly one record is sent at a fixed position: a direct source sends it as the first endpoint record, before the stream header (title bound to the mesh stream ID, or to the zero ID for a non-mesh stream); a forwarder sends it right after `GROUP_KEY` under the child's endpoint session, containing only the labels it verified from the `BFG1` source for that stream ID, byte for byte (count 0 if none). The receiver verifies after reading the header (the stream ID is known then). A forwarder can withhold labels but not forge, edit or rebind them; per PROTOCOL "Forwarded streams", a malicious forwarder can still name itself as the source and sign its own title (R22).

**Public title pages.** A second legacy, non-connectable, scannable advertising set beside the stream offer (source or verified forwarder): primary PDU = the service UUID `c8d5a101-…`; scan response = manufacturer `0xFFFF`:

```text
version 3 (1) | stream route token (4, of this advertiser's stream offer) | SHA-256(title)[0:2]
| page (1): index << 4 | count (count 1..3, index < count) | 1..19 title bytes (whole code points)
```

Interval 250 ms, one page per second (about 4 advertising events per page). Old scanners reject the payload (not 22 bytes). Scanners with the switch on route version-3 payloads to a title board before the per-address dedup, so rotated pages are seen; they reassemble by (token, digest, count) within 30 s, require valid canonical UTF-8 matching the digest, and show nothing for a token with two different digests within 60 s. An advertised title is shown as "unconfirmed"; after joining, the signed kind 2 label replaces it. If no set is free (a one-time probe of free legacy sets, logged `BENCHMARK adv_set_probe free=… | extAdv=… | multiAdv=… | maxAdvData=…`, found fewer than 2 free sets, so one stays spare for the stream offer's own stop + restart and OS advertisers; a forwarder that never probed probes first; or the start fails, e.g. `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`), only the signed title exists; the stream is never affected. iPhones cannot advertise manufacturer data, so an iPhone broadcaster has no public title.

**Vectors** (`SignedLabelTest`, `TitlePageTest`; the iOS repository holds the same table):

| Input | Bytes (hex) |
| --- | --- |
| Signing domain | `426c7565746f6f7468566964656f4d6573682f4c6162656c2f3100` |
| `BNL1` signed part: kind 3, SPKI `3001`, sequence 0, call ID `01020304-0506-0708-090a-0b0c0d0e0f10`, text "Hi" | `424e4c31 01 03 0002 3001 00000000 01 0102030405060708090a0b0c0d0e0f10 02 4869` |
| Empty `LABELS` container | `424c423100` |
| Title page: token `0x81234567`, title "Hi" | `03 81234567 3639 01 4869` |

**Compact (iPhone) call-offer amendment (R11).** Before compact v1 ships: call-offer bit 18 = "may be relayed" (the same consent as call-offer `capabilitySummary` bit 4, used by relayed discovery, phase 2). Decoders carry it into `capabilitySummary` bit 4 and never reject it; call-offer bits 49..19 and 17..0 still reject. Vector: call offer, prefix `010203040506`, LE CoC, VP9+AV1, cost 0, bit 18 set -> `C8D64010-2030-4050-60C0-000000040000` (`CompactDiscoveryAnnouncementTest`).

## Experimental iPhone compact discovery (off by default)

DEBUG `test_ios_compact_discovery`; otherwise the Advanced setting "Experimental: find iPhones". It changes discovery hints only. Android's own advertisement, every handshake, record, key, admission and overload rule are unchanged. Not yet tested on phones or against an iPhone.

**Why.** An iOS app can advertise only a local name and service UUIDs, never manufacturer data. In the foreground one 128-bit UUID fits in the advertising PDU an Android scanner reads; further UUIDs go to Apple's overflow area, which only iOS reads, and a background iPhone puts every UUID there. An iPhone therefore announces itself in one service UUID instead of the 22-byte manufacturer-data announcement (company `0xFFFF`) plus `c8d5a101-…`.

**Layout.** Read the UUID as one big-endian 128-bit integer `U` in canonical string order (Java most- then least-significant bits, `CBUUID.data`; the on-air AD value is the same bytes reversed, which both stacks undo).

| Bits of `U` | Field |
| --- | --- |
| 127..112 | Magic `0xC8D6` |
| 111..110 | Version `1` |
| 109..108 | Kind: `0` private-call offer, `1` viewer request, `2` stream offer, `3` rejected |
| 107..60 | Node-ID prefix (6 bytes, as in the 22-byte announcement) |
| 59, 58, 57 | GATT transport (0 = LE CoC; Classic is not representable), relay capable, stream paused (stream offers only) |
| 56..54 | Codec mask: 54 VP9, 55 AV1, 56 reserved for H.264 |
| 53..50 | Route cost 0..15 |
| 49..19 | Route-token bits 30..0. Call offer: 0. Others: token = `0x80000000 or field` |
| 18..0 | Call offer: 0. Viewer request: 18..12 bitrate in 25 kbps units, 11..0 zero. Stream offer: 18..11 the `sequence` byte, 10..9 resolution index (426x240, 640x360, 854x480, 3 = unspecified), 8..7 FPS index (15, 24, 30, 3 = unspecified), 6..0 bitrate in 25 kbps units |

**Decoding.** Strict: wrong magic or version, kind 3, the paused bit outside a stream offer, a call offer with any of bits 49..0 set, or a viewer request with any of bits 11..0 set rejects the UUID. Codec bits are carried, never rejected: bit 56 becomes `capabilitySummary` bit 2, which today's codec tests ignore, so a later H.264-capable iPhone stays visible to this build. The result is an ordinary `DiscoveryAnnouncement`: a call offer gets `FLAG_ACCEPTING_CONNECTIONS | FLAG_PRIVATE_CALL_AVAILABLE`, a viewer request `FLAG_ACCEPTING_CONNECTIONS`, a stream offer `FLAG_STREAM_AVAILABLE`, each ORed with the GATT, relay and paused flags; unspecified geometry/FPS and bitrate 0 decode as 0 (unknown); there is no route recovery. Every real route token has bit 31 set (`StreamRouteToken`'s advertiser mask sets it and logical tokens are 1..2^31-1), so implying the bit loses nothing. Not representable, and never sent by an iPhone: Classic, route recovery, route cost above 15, a viewer request without a route token (the DEBUG direct-receiver mode), geometry/FPS outside the tables (index 3), a bitrate above 3175 kbps (saturates). Android stream settings (100-1500 kbps in 25 kbps steps, the three resolutions, 15/24/30 FPS) are all exact.

**Scanning.** With the switch on, every `BleL2capDiscovery` passes a second `ScanFilter.setServiceUuid(C8D60000-0000-0000-0000-000000000000, mask FFFF0000-0000-0000-0000-000000000000)` in the same `startScan` call. Filters are ORed and it is still one scan start, so the scan-start quota is unchanged. When manufacturer data `0xFFFF` is present it is used exactly as before; otherwise the first service UUID whose top 16 bits are `0xC8D6` is decoded. The DEBUG `scan_stats` line appends `compact` and `compactInvalid` counters while the switch is on and is byte-identical while it is off. After discovery the existing clients connect unchanged: the iPhone hosts the same GATT database (`c8d5a101-…` with the `c8d5a102-…` PSM characteristic, dynamic PSM, insecure channel; or `c8d5a105-…` with `c8d5a103-…`/`c8d5a104-…` chunks), then BMH2, BMS1 and BME1 run as today.

**Security.** The same trust level as the 22-byte announcement, which any advertiser can already forge: every field is an unauthenticated hint. The full 48-bit prefix is present, so the link and endpoint identities must still match it (and the full fingerprint for verified contacts); prefix grinding still costs about 2^48 key generations. Route tokens are broadcast correlation values, not secrets, and keep 31 bits of entropy. A random foreign UUID passes the filter with probability 2^-16 and must then pass the version, kind and reserved-bit checks; a false or forged match only causes a connection attempt that fails at GATT discovery or BMH2, bounded by the existing admission and overload guards. The decoder is fixed-size integer arithmetic.

**Limits.** An iPhone is discoverable only while its app is in the foreground, and it can advertise one role at a time (one UUID). An iPhone viewer that lost its parent advertises an ordinary viewer request. Masked service-UUID filters are public API since API 21 and AOSP re-checks every filter on the host for every result. Each `ScanFilter` takes one controller filter slot, and a scanner with more filters than free slots runs all-pass with host filtering (more wake-ups), so the switch doubles each scanner's slot use. Whether a phone's controller applies the 128-bit mask in hardware is per phone and unmeasured.

**Vectors** (`CompactDiscoveryAnnouncementTest`; the iOS repository holds the same table):

| Input | UUID |
| --- | --- |
| Call offer, prefix `010203040506`, LE CoC, VP9+AV1, cost 0 | `C8D64010-2030-4050-60C0-000000000000` |
| Call offer, prefix `a1b2c3d4e5f6`, GATT, VP9, cost 2 | `C8D64A1B-2C3D-4E5F-6848-000000000000` |
| Viewer request, prefix `010203040506`, LE CoC, VP9+AV1, token `0x9abcdef0`, cost 0, 300 kbps | `C8D65010-2030-4050-60C0-D5E6F780C000` |
| Stream offer, prefix `a1b2c3d4e5f6`, GATT, relay capable, paused, VP9, cost 1, token `0xdeadbeef`, sequence `0x85`, 640x360, 15 FPS, 300 kbps | `C8D66A1B-2C3D-4E5F-6E46-F56DF77C2A0C` |

## Experimental rotating discovery IDs (off by default)

DEBUG `test_rotating_ids`; otherwise the Advanced setting "Experimental: rotating discovery IDs". Reviewed design 2026-10-10 (RV1-RV19); the review settled on these answers (a time-limited Visible-by-handle window instead of permanent dual advertising, Everyone with a growing delay, mutual verified contacts with 4-week leases, 15-minute epochs). Phase 1 covers **strangers only** and **call-ready presence only** (role 0). Streams, viewer requests, forwarders, relay chains, title-page content and recovery keep version 2 and the fixed prefix ([ROADMAP.md](ROADMAP.md) "Discovery privacy" lists the remaining phases). Off: every byte on air and on the link is unchanged, and version 5 / compact v2 adverts are rejected exactly as by old builds. Not yet tested on phones.

**Keys and epochs.** Presence key `P`: 32 random bytes per identity with generation `g` (u32, from 1), in no-backup app storage; it never leaves the phone. Epoch `e = floor(unix_seconds / 900)` (u32), week `w = floor(e / 672)`. Role `r` (u8): 0 call offer, 1 stream offer, 2 viewer request, 3..255 reserved. All integers big-endian; domains end with `0x00`. Every salt, the owner's own included, is keyed by the week's lease key (RV11), so contacts that later receive leases compute the same IDs:

```text
L_w  = HMAC-SHA256(P, "BluetoothVideoMesh/Discovery/Lease/1" || 0x00 || u32 w)                 (32)
salt = HMAC-SHA256(L_w(e), "BluetoothVideoMesh/Discovery/Salt/1" || 0x00 || u32 e || u8 r)      (32)
RDI  = SHA-256("BluetoothVideoMesh/Discovery/Id/1" || 0x00 || nodeId (16) || u32 e || u8 r || salt)[0:6]
```

The reviewed vectors below hold with their `P` read as `L_w`.

**Monotonic epoch (RV4).** The phone persists `lastEpoch`. A tick happens only when the wall epoch differs from the one seen at the last tick, or 960 s of `elapsedRealtime` passed since it (900 s plus one minute, so a normal boundary never double-ticks); at a tick `E = max(wallEpoch, lastEpoch + 1)`. Every other advertiser restart (after a call, a retry, a mode change) reuses `E`. After a reboot `E` is reused only while the wall still shows the epoch of the last tick. If `E - wallEpoch > 96` lasts one hour of consistent wall time (wall minus elapsed stable within 2 min), the phone starts generation `g + 1` with a new random `P` and `E = wallEpoch`. An epoch, once left, is never advertised again, and repeated restarts cannot push `E` ahead.

**Announcement version 5.** The 22-byte version 2 layout with byte 0 = `5` and bytes 1-6 = the role's RDI. A call offer's route token stays 0. Stream tokens (`logical XOR mask(RDI)`) are defined but not sent in Phase 1. Old scanners reject version 5 ("Unsupported announcement version").

**Compact UUID version 2 (iPhone).** The compact v1 layout with version bits 111..110 = `10` and bits 107..60 = the RDI; it decodes to announcement version 5. A decoder without the switch rejects it like any other version.

**Link opening, hop type 13 `BDO1`.** Link hello transport-mask bit 3 (`0x08`) means "sends and accepts `BDO1` as the first hop record". In Phase 1 only call endpoints set it (their link hellos; never an endpoint, stream, relay, forwarder or `BGK1` hello). When both link hellos carry it, the first hop record in each direction is exactly one `BDO1`, before the store-and-forward dispatch; a reader rejects type 13 as unknown at any other point:

```text
"BDO1" (4) | version 1 (1) | count (1, 0..2) | count x entry (43)
entry = RDI (6) | epoch (4) | role (1) | salt (32)
```

Decoding is strict (magic, version, count, exact length). A responder opens only call-offer IDs **pinned to this connection**: an ID that is on air, or that left the air less than 60 s earlier (the connection was accepted under it, or the boundary just passed). An ID that went off air long ago (a long call, a Visible-by-handle window, an absence) gets no fresh grace when the next one starts, and an arbitrary or older epoch is never opened. The initiator sends count 0 (it advertised nothing).

**Check (RV15).** The check follows the advert the caller selected, never the peer's hello. A selected version 5 or compact v2 advert requires bit 3 and an entry with that role whose RDI equals the selected 6 bytes and recomputes from the authenticated link node ID (`RDI(nodeId, e, r, salt)`); otherwise the call fails as a prefix mismatch fails today ("Peer identity changed between discovery and connection"). A selected version 2 advert uses the prefix check, whatever the hello says. Phase 1 calls version 5 offers only directly (route cost 0): the link identity must also equal the end-to-end identity. A relayed version 5 offer is refused (relays never mirror one: their scanners keep rejecting version 5) until the end-to-end opening `BDX1` exists. Verified contacts still need the full fingerprint, exactly as today.

**Rotation (RV6, RV12, RV1).** Classic call availability keeps version 2 (the Classic address is permanent and the Classic connector finds a callee by its fixed prefix; RV2), so only LE CoC and GATT call offers rotate. At each UTC boundary an idle call listener stops (break-before-make), stays silent for a uniform 1-8 s and reopens on a **new** advertising set with the new RDI. Reopening also gives a new GATT server and, on stacks with per-session LE CoC listeners (Android 11+), a new PSM; Android 10 stacks keep one long-lived listener per role (`CocListeners`, AOSP leak) and therefore the same PSM (stated residual). A call or connection in progress keeps its link. Experimental title-page sets also restart on a new set at the boundary. Within an epoch, `StableAdvertisingSet` keeps its address through the low-latency -> balanced step as before. The boundary check runs on the main-thread handler at the boundary and at least every 60 s (no exact alarm, RV16); Doze can delay it, which keeps the old ID on air longer but breaks nothing.

**Pre-handshake exposure (RV1).** While the switch is on, the adapter name (served as the GAP Device Name to any central, before BMH2) is set to the neutral `Phone`, the same on every phone; the user's previous name is restored when the switch is turned off (unless the user renamed the phone meanwhile). A DEBUG benchmark launch renames only with `test_rotating_neutral_name=true`, so test phones keep their names and the legacy Classic candidate plan (which matches bonded phones by name) never sees several phones called `Phone`. After an emergency wipe the neutral name stays (no record of the user's name remains). The PSM characteristic stays readable before the handshake; it rotates with the listener as above. Nothing else identifying is served before BMH2.

**Stranger tarpit (RV8).** A call responder delays its BMH2 hello by `2^(n-6)` s (31 ms for n = 1, 1 s for n = 6, capped at 30 s) for the n-th stranger handshake in the current epoch; a handshake whose call rings is forgiven. Verified contacts are never delayed. The delay runs after the initiator's hello verified and before the responder's hello, so it bounds mass probing and battery, not privacy: anyone who connects still learns the identity, once per epoch.

**Visible-by-handle window (design decisions, RV5).** The Settings button "Visible by handle for 10-25 minutes" (DEBUG `test_rotating_visible_window`) makes LE call-ready presence advertise **version 2** with the fixed prefix from now until the first epoch boundary at least 10 minutes later (`endEpoch = ceil((start + 600 s) / 900 s)`), so old builds and anyone who knows the handle can find the phone. The window starts and ends on a new advertising set (the listener restarts with the usual 1-8 s silence), and version 5 resumes with the new epoch's ID. There is no permanent dual advertising. Stated residual: every window is linkable to every other window (same prefix) and to the part of the epoch just before it. The window lives in memory only; an app restart ends it. Once over it is latched off, and it never outlasts 25 minutes of elapsed time, so a wall clock moved back cannot revive or stretch it; a window that ends between boundaries restarts the listener at once.

**No recognition yet (RV3).** Phase 1 has no presence-key delivery, no contact table and no recognition. A version 5 caller is "Nearby phone" with no handle and no check mark before connecting; the handle appears only after the handshake. No connection is ever started by a match; retries only continue a call the user started.

**Phase 2 formats (defined and tested, not sent yet).** `BDX1` = BME1 content type 11, calls only, sent only when both *endpoint* hellos carry bit 3 (no endpoint hello sets it in Phase 1):

```text
"BDX1" (4) | kind (1) | body
  1 OPENING           count (1, 0..2) | count x entry (43)                 (the BDO1 body after its version byte)
  2 RECOGNITION_OFFER reserved (1) = 0
  3 PRESENCE_LEASE    generation (4, >= 1) | sender epoch (4) | first week (4) | count (1, 1..4) | count x L_w (32)
```

Decoding is strict; a receiver drops and logs any other kind or length and never fails a call. Receiver rule for leases (design decision: mutual verified contacts, per-contact switch on by default, the current and next 3 weeks): a higher generation replaces the stored one, the same generation merges the delivered weeks (leases refresh at every session) and drops weeks before the first delivered one, a lower generation is ignored; the skew `sender epoch - my epoch` is clamped to +-30 days. The recognition table holds roles 0 and 1 for every epoch in `myEpoch + skew + [-1, +1]` (`[-8, +8]` while the skew is older than 14 days) that a lease covers; role 2 is not recognised; a hit is "seen nearby · unconfirmed", never a check mark and never a reason to connect (`PresenceLeases.kt`).

**Debug lines.** `rotating_ids: enabled=`, `rotid_visible_window`, `rotid_name`, `rotid_tarpit`, `rotid_open: role=call | result=` (DEBUG adds `rdi=` and `prefix=` after a pass; release builds never log both), DEBUG `rotid_boundary`, `rotid_restart`, and the Phase 0 measurement lines `adv_set_churn` (every set start/stop/in-place step with its set identity) and `adv_address_seen` (a scanner's per-node advertising address when first seen or changed). `tools/rotid_phase0.py` joins them offline.

**Vectors** (`RotatingDiscoveryTest`; `P = 000102…1f`, node ID `a1b2c3d4e5f60718293a4b5c6d7e8f90`, epoch of 2026-10-10T00:00:00Z = 1990656 = `0x001e6000`):

| Input | Output |
| --- | --- |
| Role 0 salt / RDI | `d8cbe82b78824c1ba08d0c4eed26502189e19ccccd180e46c4826c5ba47e7167` / `44e69c0a7a7d` |
| Role 1 salt / RDI | `dcd9349e228e00ceb4a7ec2e7e023fc0c34c507f88e99934897105ca4fae84c0` / `bb2d061f6480` |
| Role 2 salt / RDI | `e59970c8523f23a6b1b16a858daf75320e3291d6c86d0329df89a62899804335` / `974ae410ea91` |
| Role 0, epoch + 1 salt / RDI | `7ece98afad0378297e68c9e2b59c1a1f88448a0c70b86d91f192f65d263a763b` / `67aba670e65a` |
| Version 5 call offer, role-0 RDI, flags `0x41`, VP9+AV1, token 0, cost 0 | `0544e69c0a7a7d410300000000000000000000000000` |
| `mask(bb2d061f6480)` / route token for logical `0x12345678` | `0x8066c9bc` / `0x92529fc4` |
| Version 5 stream offer, role-1 RDI, flags `0x0c`, VP9, that token, cost 0, sequence 1, 640x360, 15 FPS, 300 kbps | `05bb2d061f64800c0192529fc40001028001680f012c` |
| Compact v2 call offer, role-0 RDI, LE CoC, VP9+AV1, cost 0 | `C8D6844E-69C0-A7A7-D0C0-000000000000` |
| `BDO1`, one entry (role 0 above) | `42444f31010144e69c0a7a7d001e600000d8cbe82b78824c1ba08d0c4eed26502189e19ccccd180e46c4826c5ba47e7167` |
| `BDO1`, empty | `42444f310100` |
| Lease domain / week of the epoch above | `426c7565746f6f7468566964656f4d6573682f446973636f766572792f4c656173652f3100` / 2962 |
| `L_w` / `L_(w+1)` from `P = 000102…1f` | `d4b8f2947b874084885be10af7cae3f5e2f5a3037d65bbd3da0af828245d568a` / `b5d948c06d9ce94699b90f2d9feebfc49817a64422d5acadc7c58cfdae8e3fdc` |
| Owner's own role 0 salt / RDI from that `P` (salt keyed by `L_w`) | `f82bb42958807ae3f9b64ec0bccff4370d521b5719df26d6286c36e3d573265f` / `ddb0a80c67aa` |
| `BDX1` OPENING (role 0 entry of the vector table) | `42445831010144e69c0a7a7d001e600000d8cbe82b78824c1ba08d0c4eed26502189e19ccccd180e46c4826c5ba47e7167` |
| `BDX1` RECOGNITION_OFFER | `424458310200` |
| `BDX1` PRESENCE_LEASE, generation 1, sender epoch above, first week 2962, `L_w`, `L_(w+1)` | `424458310300000001001e600000000b9202` followed by the two keys |
| Recovery hint (Phase 1b), `Kmedia = 404142…5f`, failed node above, logical `0x12345678`: raw key (review vector) / RV18 `Khint = HKDF-SHA256(Kmedia, salt 32 x 0x00, info "BluetoothVideoMesh/Discovery/Recovery/1")` = `d6647a80…c9c322` | `efaddd28c492` / `52d1d45e4f85` |

**Threats that remain** (design section 9): a link sniffer reads both identities from the cleartext BMH2 hellos; anyone who connects learns the identity (once per epoch); OS-paired devices resolve every address with the IRK; Classic uses the permanent address; the phone's own SCAN_REQ/CONNECT_IND addresses rotate on the stack's timer; OS beacons (Find My Device, offline finding, Fast Pair) rotate on their own schedule; a phone alone in an area is linkable by continuity; stable capability bits are a few bits of quasi-identifier; a streaming phone still broadcasts its fixed prefix in Phase 1.

## Experimental store-and-forward messages (off by default)

Switch: `test_store_forward` (DEBUG) or the Advanced setting "Experimental: store-and-forward messages". With it off, no reader accepts hop type `11`, no endpoint reads a dispatch record, and calls/streams are unchanged. v1 covers two directly adjacent phones only; the carrier role (ROADMAP "Design A v2") exists in code but is hard-off. Design and reasoning: [ROADMAP.md](ROADMAP.md) "Design A".

### Keys and contact card

- Each identity has a static P-256 **mailbox key**: Android Keystore ECDH (`PURPOSE_AGREE_KEY`) on API 31+, otherwise a software key whose PKCS#8 bytes are sealed by a non-exportable Keystore AES-256-GCM key. The same Keystore AES key encrypts opened messages and every metadata file (contacts, neighbour ledger, bundle meta, seen IDs) at rest under `noBackupFilesDir/store-forward`. If the recorded mailbox key can no longer be loaded (Keystore cleared, data restored elsewhere), a new key is created and Messages shows a "message key reset" notice until dismissed, because contacts must receive the new card.
- **Contact card `BCC1`**: `BCC1 | version 1 | identity SPKI length (2) | identity SPKI | compressed mailbox key (33) | created ms (8) | signature length (2) | ECDSA signature`. The identity key signs `BluetoothVideoMesh/StoreForward/Card/1 || everything before the signature`. The node ID is SHA-256(identity SPKI)[0:16]. A card decodes only with a valid signature and an on-curve mailbox point. Text form `BCC1:` + unpadded base64url (about 300 characters, QR-ready; no QR renderer is bundled).
- A card received over a link must match the link's authenticated node ID. It is stored PENDING until the user accepts it, and a pending card authorizes nothing: it is not in the tag table, its bundles are refused (not for me) and stay queued with the sender until acceptance. Ignoring a card removes it and anything held for it; unaccepted cards are dropped after 14 days. A different mailbox key for a known identity is ignored (key-change warnings are open work). Typed card text is accepted immediately. No two held cards (pending or accepted) may share a mailbox key: a link card reusing one is refused (`MAILBOX_IN_USE`), and a typed or accepted card removes pending cards that copied its key (accepting beside another accepted holder fails). Senders are resolved by the node ID inside the key slot, which must be an accepted card holding exactly that mailbox key. Cards carry no proof of possession of the mailbox key: ECDH cannot give a non-interactive proof and a Keystore `PURPOSE_AGREE_KEY` key cannot sign. The identity binding in the KDF (below) and the uniqueness rule leave a squatting card unable to open, forge or block anything.
- **Safety number**: 20 digits (four groups of five) from `SHA-256("BluetoothVideoMesh/StoreForward/SafetyNumber/1" || u16 SPKI length || identity SPKI || compressed mailbox key)`, shown for your own card and for each pending card so users compare them in person before accepting.
- **Pair secret** `Kp = HKDF-SHA256(ikm = ECDH(mailbox_A, mailbox_B), salt = SHA-256(sorted compressed mailbox keys), info = "BluetoothVideoMesh/StoreForward/Pair/2" || the two node IDs sorted)`. Binding the node IDs means the same mailbox key under another identity gives a different secret and different tags (no unknown-key share). **Recipient tag** = `HMAC-SHA256(Kp, "tag" || utcDay u32)[0:8]`, where utcDay is the bundle's created hour / 24. Both members compute the same tag. It differs per pair and per creation day, but a bundle's tag and ID are fixed for that bundle's whole lifetime (up to 14 days), so an observer who sees one bundle again can follow it until it expires. Bundles created on different days carry unlinkable tags. Recipients precompute tags for every held card over the 14-day window.

### Bundle header `BSB1` (carrier-visible, immutable)

| Field | Bytes |
| --- | ---: |
| Magic `BSB1`, version 2 (version 1 is rejected) | 4+1 |
| Created hour, expiry hour (UTC hours since epoch) | 4+4 |
| Priority class (0 text/receipt/location/card, 1 voice, 2 photo, 3 video) | 1 |
| Padded plaintext length | 4 |
| Chunk count (1-256) | 2 |
| Slot count (1-8) | 1 |
| Key slots | 154 x slots |
| Chunk hash list: SHA-256(ciphertext chunk)[0:16] | 16 x chunks |

Bundle ID = SHA-256(header). Decoding is strict: the lifetime must be positive and at most 336 hours (QA4), the chunk count must equal ceil(padded / 8176), and the length must be exact. v1 writes one slot. No proof-of-work field exists in version 1.

**Slot** (154 bytes) = `tag (8) | ephemeral key e, compressed (33) | part1 (49 + 16) | part2 (32 + 16)`. AAD = all fixed header fields and the hash list (everything except the slots, including the slot count) `|| slot index (1) || slot tag`. `salt = SHA-256("BluetoothVideoMesh/StoreForward/2" || e || R)`.
- `k1 = HKDF(ECDH(e, R), salt, ".../sender" || recipient node ID)`; part1 = AES-256-GCM(k1, nonce 0, sender compressed mailbox key || sender node ID, AAD).
- `k2 = HKDF(k1 || ECDH(S, R), salt, ".../content-key" || sender node ID || recipient node ID)`; part2 = AES-256-GCM(k2, nonce 0, 32-byte content key, AAD || part1).

The recipient learns and authenticates the sender (static-static ECDH), and the sender must be a held card whose node ID matches part1. Carriers learn nothing about the sender. There is no signature, so the recipient cannot prove authorship to a third party (deniable, QA7); like Noise X, a compromised recipient key can impersonate others to that recipient. Unknown senders are refused (contacts only, QA2).

**Forward-secrecy limitation (open design decision).** Both slot keys derive from the recipient's long-term mailbox key, so a later compromise of that key decrypts every retained or captured bundle addressed to it and reveals the senders. On API 31+ the key is non-exportable in Keystore: an attacker must keep using the phone. On API 29-30 it is a software key sealed by a Keystore AES key that has no user-authentication requirement, so a temporary app-process compromise can copy it for later offline decryption. Options, not yet chosen:
- per-bundle forward secrecy from one-time prekeys published in the contact card or exchanged over a link (X3DH-like; adds key management and prekey exhaustion);
- periodic mailbox-key rotation with card re-exchange;
- `setUserAuthenticationRequired` (or unlocked-device-required) on the wrapping key, which stops background opening while the phone is locked.

On API 29-30 the unwrapped software private key also lives in the app's memory for as long as the process runs (it is decrypted once when Messages or an exchange first opens the store). Anyone who can read that process memory (a debugger on a compromised phone, a memory-dumping exploit) obtains it, and with it every past and future bundle until the key is reset. API 31+ keeps the private key inside Keystore.

### Content `BSM1`

`BSM1 | kind (1) | created ms (8) | conversation ID (16) | reply-to bundle ID (32, zero if none) | MIME length (1) | MIME | body length (4) | body`. Kinds: text 1, voice 2, photo 3, video 4, location 5, receipt 6, contact card 7. A receipt's reply-to is the delivered bundle ID and its body the received UTC hour (QA3: 1:1 receipts on, hour resolution; no read receipts). The plaintext `u32 length || BSM1` is zero-padded to Padmé size (minimum 1024 bytes, capped at 256 chunks), split into 8176-byte pieces, and each piece is sealed with AES-256-GCM under the per-bundle content key, nonce = chunk index, AAD = `index u16 || count u16`. Ciphertext chunks are 8 KiB except the last. Opening verifies every chunk hash, every tag and zero padding; any failure rejects the bundle.

### Transfer `BSF1` in hop type 11

Each `BMS1` type-11 record carries `BSF1 | message type (1) | body length (4) | body` inside the unchanged authenticated, ordered link session.

| Type | Body | Purpose |
| --- | --- | --- |
| HELLO 1 | version (1), free carrier quota (4; 0 = not a carrier), daily remaining from this neighbour (4), inventory digest (8) | First message each way |
| SUMMARY 2 | count (2), last (1), entries of bundle ID (32), tag (8), class (1), expiry hour (4), chunk count (2), chunks held (2) | Up to 256 entries per message |
| WANT 3 | bundle ID (32), need header (1), chunk count (2), needed-chunk bitmap | Resume from bitmaps |
| HEADER 4 | copy tokens (1, always 0 in v1), BSB1 header | |
| CHUNK 5 | bundle ID (32), index (2), ciphertext (at most 8 KiB) | Verified against the hash list before storage |
| ACCEPT/REFUSE 6 | bundle ID (32), reason: 0 sent (all chunks this phone can give for the WANT), 1 have, 2 quota, 3 expired, 4 rate, 5 too large, 6 invalid, 7 not for me, 8 unknown | |
| DELIVERED_LOCAL 7 | bundle ID (32) | The recipient's phone tells its adjacent peer it holds the bundle. It is sent only to the contact that owns the bundle's tag: the authenticated peer must be that contact and the offered tag must equal the delivered bundle's own tag, so it is no recipient oracle. Acted on only for the receiver's own bundle addressed to that authenticated peer; ignored for carried bundles (QA5) |
| DONE 8 | reason: 0 normal, 1 suspend, 2 timeout, 3 error, 4 rate limited | 4: the responder refuses because this neighbour exchanged too recently or too often |
| CARD 9 | BCC1 card | Sent after HELLO |

Differences from the original design draft (see [ROADMAP.md](ROADMAP.md) "Design A"): SUMMARY and CHUNK carry the full 32-byte bundle ID (an 8-byte prefix made WANT ambiguous and let a peer grind collisions); CARD 9 and reason 0 "sent" were added; the HELLO digest is recorded but does not yet skip a sync.

**Exchange.** A responder admits at most one exchange per adjacent identity per 60 s and 10 per hour; otherwise it answers DONE(rate limited) and closes. While an exchange holds the call endpoint, the call listener stays open (one extra LE CoC listen, counted by the registration ledger). An incoming connection then suspends the exchange within one chunk and is answered as an ordinary call. The initiator sends HELLO and CARD. A responder whose call endpoint has the experiment on reads the first record after the BMH2 link handshake with type 11 allowed for that read only: type 11 starts an exchange, and anything else goes unchanged into the endpoint handshake. After a peer's HELLO, each side sends SUMMARY. The bundles offered are its own queued bundles addressed to that authenticated peer. A peer's HELLO carrier quota is self-declared and widens the offer only when this phone itself runs the carrier role (off in v1). With carrying on, complete carried bundles not received from that peer are also offered. Ordering is by class, then size, then age. The receiver admits each entry hop-locally and WANTs it. The offerer answers in order with HEADER (if needed), the CHUNKs it holds and REPLY 0. A side sends DONE(normal) when every WANT is answered, and the exchange ends when both DONEs have crossed. At most 1,024 SUMMARY entries are offered or accepted per exchange. A repeated WANT for a bundle, or more unknown WANTs than that, is a protocol violation. Each WANT becomes one lazily read job, so outbound queues are bounded. Serving stops with ACCEPT/REFUSE 4 (rate) at 8 MB per exchange or 20 MB per neighbour per UTC day. A dedicated writer drains outbound work so that neither side blocks on a full radio buffer. A local suspend (live call, `sf_suspend`) is checked before every record: at most the chunk being written completes, then DONE(suspend) and the link closes. A separate watchdog enforces 10 s without progress and 120 s per exchange. If the writer is stuck in a write to a peer that stopped reading, the watchdog closes the link one second after a suspend request. A malformed or out-of-order message sends DONE(error) and closes. After more than four poisoned chunks the exchange is abandoned. Chunks write idempotently and bitmaps persist after each chunk, so any interruption resumes from the next missing chunk with this or another phone.

**Durability.** An own bundle's header and chunks are fsynced before its meta (the commit point) marks it complete. Received chunks are fsynced in batches of 16 (and when a bundle completes or an exchange ends) before the saved bitmap claims them, so a crash only re-requests the unsynced chunks. Each stored chunk is checked against the hash list before it is served. A damaged own bundle becomes FAILED (shown as failed, no longer offered) and the exchange continues with the next bundle. A bundle whose header cannot be read at startup is kept and listed as failed instead of being deleted silently. Deleting an outgoing message withdraws it. Neighbour ledger records idle for 30 days are pruned, and at most 512 are kept.

**Admission and storage (hop-local).** Per authenticated adjacent identity: 20 MB and 200 bundles per UTC day and 8 MB per exchange. Per sending contact: 50 bundles per day, charged only after the HEADER's key slot authenticates that contact as the owner of the bundle's tag. Before that, admission charges only the adjacent neighbour's allowance, so a stranger who knows a tag cannot spend a contact's allowance. A header whose slot does not authenticate the tag's owner is refused (6 invalid) before any chunk. Inbox 64 MB, counting opened incoming messages; own outgoing 32 MB. Opened messages (both directions) are kept encrypted at rest, up to 96 MB. Each keeps its bundle's expiry hour and is deleted at that hour. The list decrypts only small listing records; bodies are decrypted on demand. Carried: 64 MB, at most 25% per source neighbour and 10% per tag; eviction removes stalled partials (24 h), then class 3 > 2 > 1 > 0, oldest first, and never own or inbox data. Expiry uses the receiving phone's clock: `stored_until = min(expiry, received + 14 d)`, with 2 h skew allowed only at acceptance and a created hour at most 2 h ahead. Opened, rejected, delivered and user-deleted IDs stay in a seen set until expiry + 2 h skew + 1 h, past the last hour acceptance allows, so a replay never reopens or resurrects a message or triggers another receipt. Receiving is crash-safe: the opened message is stored durably first. Only then is the ID tombstoned and marked delivered, and the ciphertext deleted. If storing fails (archive full or a write error) the ciphertext stays, delivery is not acknowledged, and opening is retried at the next exchange. Decrypted voice notes play from memory, and recording leftovers are deleted when Messages starts, pauses or closes.

**What a link observer or peer learns.** A radio observer sees a call-transport link with record sizes and timing. The adjacent peer learns the other's identity (already true of BMH2), mailbox card, the offered tags/classes/sizes/expiry and transfer timing. Contents, senders and recipients of carried bundles stay hidden. Discovery is unchanged: v1 uses the private-call availability advertisement, and a version-3 announcement is not implemented.

## Lifecycle, liveness and route withdrawal

Lifecycle and media share ordered endpoint-content sequence space. Pause clears queued media and sends authenticated `PAUSED` every two seconds. Resume queues `LIVE` before media. End clears queued media, queues and flushes `ENDED`, then closes the link. Repeated `PAUSED` is intentional liveness. An advertised paused hint is untrusted and cannot replace the authenticated lifecycle record.

Each parent acts first on immediate socket EOF/read/write failure or GATT disconnect; it closes only the affected child link. Authenticated version-1 keepalive has a one-byte payload and terminates at the immediate parent, which independently generates its own upstream keepalive.

| Link | Heartbeat period | Silent-link timeout |
| --- | ---: | ---: |
| RFCOMM / CoC child | 2 seconds | 8 seconds |
| GATT direct viewer or receiver-node child | 2 seconds | 6 seconds |

EOF/callback/write failure takes precedence over the timeout. Matched GATT kill/rejoin tests established that callbacks and forwarding writes can leave a stale direct-viewer session; these heartbeats bound that immediate silent link. They do not recreate the retired graph-wide metrics stream. No queue, bitrate, FPS, drop, decoder, thermal or liveness metric crosses more than one immediate link or changes the broadcast encoder target.

A receiver's hop-local link report travels in the same heartbeat type and describes only the link from its adjacent sender; it is never forwarded (a call relay replaces it with a version-1 heartbeat).

| Version | Payload |
| --- | --- |
| 1 | `01` (liveness only) |
| 2 | `02`, arrival-train kbps (2), flags (1: bit 0 link late), added delay ms (2): exactly 6 bytes |
| 3 (2026-10-09) | the version 2 fields with version `03` (flags bit 1: delivery only), then received media bytes (4) and receiver link clock ms (4): 14 bytes |

Received media bytes count, in arrival order, the media this receiver has taken off the link since it started, modulo 2^32: each whole media packet's payload and, for experimental call video fragments, each fragment's chunk bytes when the fragment arrives (never the reassembled packet again). These are exactly the bytes its sender counts as written, record by record (display-rotation packets are excluded in calls). The link clock is milliseconds since this link started at the receiver, modulo 2^32, at the arrival of the last counted byte (not when the report was written, and not device uptime). The sender maps that byte offset to its own write time for it: the one-way delay minus its rolling 2-minute minimum is the queue in front of the media, and the return path's delay is not part of it. Counters that go backwards are a peer reset, not a 2^32 wrap. Flag bit 1 marks a report sent only to refresh delivery: its lateness fields repeat the last full report and must not feed a lateness trend.

Version 1 and 2 parsers accept only these exact payloads and close the link on anything else, so version 3 is sent only to a hop known to accept it. Version 3 and later parsers read the first 14 bytes, ignore flag bits 2-7 and trailing bytes (up to 64 bytes in all) and reject shorter payloads, so a later version never closes a version 3 link. On a direct private-call link each current endpoint sends one version-1 heartbeat after the endpoint handshake to say it accepts version 3; earlier endpoints never send version 1 on a direct link and take it as liveness. An endpoint sends version 3 only once the call and its relay-hop count are known and the call is direct, so a call relay never receives it. A stream parent has no message that older viewers accept for such a hello (they reject heartbeats from a parent), so stream viewers send version 3 only under the experimental stream delivery reports, which require every phone in the stream to run that build. A capability bit in the signed hello would carry this negotiation for streams too; it needs its own protocol and security review.

Decoder recovery seals an empty `KEY_FRAME_REQUEST`; each viewer-forwarder authenticates it and creates the equivalent upstream request, coalescing it with local recovery. Terminal acknowledgement works similarly. A parent waits up to two seconds for its active child before acknowledging upstream, so a missing child cannot block the chain indefinitely. The blind-relay harness instead forwards endpoint keyframe control as opaque `BME1`.

An overloaded immediate parent sends authenticated one-byte `ROUTE_CONTROL`, reason `LOCAL_CAPACITY=1`, waits up to four seconds for the matching `ROUTE_CONTROL_ACK`, then closes that child link. It neither ends nor changes the source stream. The receiver suppresses the exact node-prefix/stream-token/transport route for 30 seconds and returns to discovery; it does not repeat ordinary same-route reconnect. Triggering queue/cadence measurements remain private to that parent.

Explicit `ENDED` returns the receiver to discovery without retrying that broadcast. Unexpected loss preserves the last frame and searches for the same logical broadcast token and codec until the user leaves. A replacement can be the original source or another viewer-forwarder/transport, but its adjacent identity must match the announcement and its authenticated source must match the originally accepted source. The replacement reauthenticates and starts at a fresh keyframe; recovery is break-before-make. A recovery request uses six bytes for the failed immediate-node prefix that ordinary viewer requests do not need, keeping the discovery body 22 bytes. That hint can prioritize/gate attempts and never replaces authenticated identity/liveness.

## Emergency wipe

`EmergencyWipe.panic(context)` (no confirmation, bound by the UI to an in-app gesture such as a triple-tap) and `EmergencyWipe.wipeConfirmed(context, "WIPE")` run one synchronous, single-flight sequence on a worker thread; a concurrent or re-entrant call returns `alreadyRunning`.

1. **Stop admission.** No new end-to-end session is admitted and no Keystore key is created (identity, store-and-forward, verification) for the rest of the process.
2. **Pending marker.** `noBackupFilesDir/emergency-wipe-pending` is written and synced.
3. **Keys first.** Every Android Keystore alias of the app is deleted, so data sealed under them (store-and-forward messages and metadata, the software mailbox key, verified contacts) becomes unreadable even if the wipe is interrupted.
4. **Sessions.** The registered owner ends every call, stream, relay and discovery on the main thread (best-effort hang-up first). The wipe waits for it at most 2.5 s and never runs it unbounded on its own thread.
5. **Data.** Every SharedPreferences file (settings and learned link, rate, PHY, encoder and group-epoch memories) is cleared and deleted. The contents of the app data directory (files, `no_backup` with store-and-forward outbox/inbox/courier data, verified contacts and learned Classic addresses, cache, databases) are deleted, except the native library link, ART code cache and the marker. So are the app's external files and cache (benchmark traces and media).
6. **Second sweep.** In-memory caches are dropped, then the Keystore, preferences and files are swept again for late writers. The report is `complete` only when that final sweep leaves nothing.
7. **Finish.** Every app notification is cancelled (call alerts carry the caller's label; also once right after the sessions end). The marker is removed only when complete. One line is logged (`EMERGENCY_WIPE reason=... | complete=... | alreadyRunning=... | durationMs=...`, no identifiers or secrets), and the process ends.

On the next launch, a remaining marker makes MainActivity resume the wipe before any transport, key or store opens. If it still cannot complete (for example the Keystore keeps failing or storage is read-only), a minimal "Wipe incomplete" screen replaces the app. It has Retry and an App info link (clearing storage there finishes the wipe), and starts nothing; the app never closes silently. After a complete wipe, the next launch creates a new identity, so peers see a new node ID.

No intent reaches the panic or confirmed wipe. DEBUG builds alone accept `--es test_emergency_wipe <token>` on launch or new intent, and only when `<token>` (32-128 lowercase hex) equals the one-shot file `files/debug-emergency-wipe-token`. Write it with `adb shell run-as <package>` (debuggable builds only; internal storage, because on Android 10 legacy storage another app with the storage permission could write external app files). The debug wipe also deletes benchmark inputs in the app's external files.

A wipe cannot guarantee:

- **Flash remanence.** File deletion on flash storage does not overwrite cells; wear levelling and TRIM timing decide when blocks are erased. Files sealed under Keystore keys rely on key deletion instead, which is as strong as the device's Keystore deletion (strongest with StrongBox or rollback-resistant keys). Learned memories, settings, traces and media are plain files and may be recoverable forensically until the blocks are reused.
- **Copies outside the app.** adb backups or device-to-device transfers made earlier, screenshots, the system logcat buffer (which an app cannot clear), recordings saved to shared storage, the system's Bluetooth bond list and cached device names, and anything a peer stored (their verified-contact entry, received messages, carried bundles) are untouched.
- **A running attacker.** Malware or a debugger already on the phone can copy keys or data before the wipe. A phone that is powered off or seized before the wipe is triggered is not wiped.
- **Interruption.** Power loss mid-wipe leaves later steps undone until the next launch resumes them; keys go first to limit that window.

## Confidentiality and failure boundaries

A relay sees adjacent identities/capabilities, signed public endpoint hellos, record direction/size/timing, endpoint type/sequence/flags and its own local queue/drop information. Record timing and size remain traffic metadata.

A blind relay cannot open video geometry/FPS/rotation/audio headers, compressed VP9/AV1/Opus data, media timestamps, keyframe-request payload, wrapped group keys or group-sealed media. A normal viewer-forwarder can decrypt because it is an admitted local viewer; its forwarding branch still copies ciphertext unchanged.

Modified ciphertext/header metadata, replay, reordering, invalid signatures, wrong handshake domains and wrong recipient keys fail closed. Cryptographic identity does not establish human ownership. Full-fingerprint verification (above) is local and opt-in per contact; TOFU pinning of unverified peers, dynamic audience revocation/re-key, larger alternate-path proof and independent security audit remain open. Open/approval/verified audience modes would change admission, not remove hop/content encryption. Current primitives do not constitute independent protocol certification.

## Verification

```powershell
.\gradlew.bat testDebugUnitTest
dotnet run --project .\receiver\BluetoothVideoReceiver.csproj -c Release -- --self-test
dotnet run --project .\tools\BluetoothVideoFileSender\BluetoothVideoFileSender.csproj -c Release -- --self-test
```

Android/Windows tests share a fixed AES-256-GCM vector. Focused regressions cover [handshake domains](../app/src/test/java/com/example/bluetoothtest/MeshSecurityTest.kt), [hop ordering/tamper](../app/src/test/java/com/example/bluetoothtest/SecureBluetoothLinkTest.kt), [forwarded grants](../app/src/test/java/com/example/bluetoothtest/ForwardedStreamSecurityTest.kt), [static group keys](../app/src/test/java/com/example/bluetoothtest/GroupBroadcastSecurityTest.kt) and [call signaling/state](../app/src/test/java/com/example/bluetoothtest/PrivateCallSessionTest.kt) and [experimental voice-call/push-to-talk negotiation](../app/src/test/java/com/example/bluetoothtest/IntercomCallTest.kt) and experimental store-and-forward [sealing, cards, tags and padding](../app/src/test/java/com/example/bluetoothtest/StoreForwardCryptoTest.kt) and [transfer, resume, dedup, expiry, quotas, preemption and the security-review fixes](../app/src/test/java/com/example/bluetoothtest/StoreForwardLinkTest.kt). Unit/self-test success proves its stated protocol checks, not current phone quality or Windows media interoperability. [DECISIONS](DECISIONS.md) retains measured security/forwarding constraints with their original evidence.
