# Privacy and security

For users: what other people can learn about you when you use MeshLive, what is hidden, and what is not. The wire-level rules are in [PROTOCOL.md](PROTOCOL.md).

## What nearby phones can see

Anyone with a Bluetooth scanner in range can read what MeshLive broadcasts. They do not need to connect or pair. The
**content** of calls, streams and messages is encrypted. What the announcements reveal depends on the settings and the
app versions below.

**What a phone broadcasts.** When a phone offers a call or a stream it sends a 22-byte Bluetooth LE announcement (or an
equivalent service UUID when the experimental "find iPhones" switch is on) containing:

- a **six-byte ID**. With rotating discovery IDs on (the default) it is a random-looking 48-bit ID
  that changes every 15 minutes. It is derived from a key that never leaves the phone, so it says nothing about who
  the phone is until someone connects (or holds a contact lease, below). Only the cases under "Still stable or
  exposed" use the fixed node-ID prefix instead (48 bits, the first 6 bytes of the SHA-256 of the phone's identity
  key; it does not change until the identity is wiped or reset);
- the Bluetooth transport it offers, which video codecs it supports, and flags such as "accepting connections",
  "can relay", "call available", "stream available" and "paused";
- the route cost (how many relay hops away the source is);
- for a stream: a 4-byte route token that follows the ID, a sequence number, and the resolution, frame rate and
  bitrate. A phone searching for a lost stream advertises a request that carries its own current ID and a hint that
  only the stream's source can match.

**What rotation changes.** Android changes a phone's own Bluetooth LE address from time to time so that scanners
cannot follow it. Older versions of the app put a fixed prefix in the payload, so a scanner that recorded
prefixes could link all of those changing addresses to the same phone, across places and days. Every advertising
set of the app (call-ready presence, stream offer, waiting viewer request, forwarder offer, title pages) stops at each
UTC quarter hour, stays silent for a random 1 to 8 seconds and restarts on a new address with a new ID. A call or
stream already connected keeps its link. In normal use the setting **Reachable for calls** is on, which means the
phone advertises that it can take a call while the app is open and, through its session service, in the background;
turning it off stops that, at the cost that others cannot call you unless your Call screen is open. Rotating IDs are
one switch (Settings > Advanced > Rotating discovery IDs); the identity-hiding handshake and the throwaway identities
for strangers are two more (Settings > Features). All three are on by default and each is its own off-switch.

**Who learns who you are.** With the defaults, a call link carries both phones' identity keys encrypted. A phone that
is not one of your verified contacts meets a throwaway identity instead of your real one. Your real identity goes only
to verified contacts and, after you press Accept, to the person whose call you accept; a declined call reveals nothing.
Still true: accepting a call from a stranger tells them who you are, and calling a stranger tells whoever answers who
you are; a live stream source still answers the old handshake, so a viewer, or anyone who connects to the source,
learns its identity; and a phone with pseudonyms on refuses the old handshake, so it cannot call, or be called by, an
older version of the app or a phone that has the switches off. Message exchanges and calls relayed through other
phones (experimental) work only with verified contacts while pseudonyms are on.

**Still stable or exposed.**

- **Older app versions** cannot see a phone with rotating IDs, and a rotating phone sees them by their fixed prefix. A
  rotating viewer that joins an older source shows its own fixed prefix while its request is on air (the app says so),
  and anyone can advertise a decoy old-style offer to make a viewer do the same.
- **Visible by handle.** The Settings button advertises the fixed prefix for 10 to 25 minutes so older builds and
  people who know your handle can find your streams. Every such window can be linked to every other window and to the
  minutes just before it. A phone with pseudonyms on still refuses calls from older versions.
- **Live streams** show their source's identity to every viewer, stay linkable by their route token while they run,
  and a stream title, if you set one, is advertised openly.
- **Classic Bluetooth** uses the phone's permanent hardware address. A first Classic contact with an unpaired phone
  shows its name and address while it is discoverable.
- **Paired phones.** A phone that is paired with yours through Android can work out your rotating LE addresses.
- **The Bluetooth device name** can be read by any phone that connects, before the handshake, and is shown by Classic
  lookups. MeshLive never renames the phone on its own. With rotating IDs on, connectable announcements are
  non-discoverable on Android 14 and newer, which makes the phone refuse the name to an unpaired phone that connects
  through them. In the test the S24 (Android 16) sent them that way and the ASUS (Android 11) and M31 (Android 12) did
  not, so on Android 13 and older the name stays readable. Links your phone opens itself still show the name to the
  other side. Settings > Advanced has an option, off by default, to use the neutral name "Phone" instead; it renames the
  phone for everything (headphones, car, watch, PC), shows your original name so you can restore it, and if you
  uninstall the app while it is on the name stays "Phone" until you rename the phone in Android settings.
- **Capability bits.** The codec set, transport and relay hint bits stay the same across IDs: a few bits that can
  single out a phone when few others are nearby.
- **Verified contacts.** After a call you both answered, mutual verified contacts exchange keys that let each recognise
  the other's IDs nearby ("seen nearby, unconfirmed", never a check mark) for up to four weeks or until you forget the
  contact or reset the presence key. A phone read on day D reveals its IDs up to 27 days ahead, so use **Reset presence
  key** after a phone was out of your hands.
- **Legacy chips.** A phone whose Bluetooth chip only supports legacy advertising may keep its address when the ID
  changes and shows a note saying so.
- **Other Bluetooth radios.** Android's own beacons (Find My Device, offline finding, Fast Pair), the phone's
  connection-request addresses, and the size and timing of encrypted data on a link are outside the app's control; they
  show that a call or stream is happening, and roughly of what kind.
- Optional names and stream titles (experimental) are public while they are enabled.

To reduce exposure further: switch Bluetooth off when you do not need it, switch **Reachable for calls** off when you are
not expecting calls, leave names and titles off, use the neutral Bluetooth name option on Android 13 and older, and
verify the people you call often (QR code or safety code).

## Measurements

Early results from the maintainers' own phones. They show the design works; they are not proof that nobody can track you.

### Scanner test

In the combined privacy session of 2026-10-11 (build 3996bfb2, four phones), four phones (Galaxy S24, ASUS, Galaxy M31 and Galaxy A15s) ran
rotating IDs with the identity-hiding handshake and pseudonyms. A passive-tracker tool read full Bluetooth logs of
three of them and the phones' own logs: 66 advertising sets decoded across three quarter-hour boundaries (23:15, 23:30
and 23:45 IST) inside a 40-minute window in which only rotating announcements were on air. Result: **no field
carried over any of the three boundaries**: no ID, route token, capability hint or page field repeated, and no phone
reused its advertising address for a new ID. The only allowed repeat was a stream's own logical token, which follows the
stream. The phones switched IDs within 80 ms of the boundary and stayed silent 1.3 to 7.7 seconds. With the tool's
default parameters the same data raised six findings, each explained and none a failure of rotation (two old-style
announcements from the previous test scene still on air, two address resolutions of a bonded phone pair, and two
restarts of a stream source 11 seconds before a boundary that kept the same ID). The same session ran a 4 minute 51
second call across a boundary, a stream found and viewed by its rotating ID (a viewer waiting across a boundary joined
7 seconds after the source returned), a lease exchange between verified contacts, and calls relayed through one phone.
Calls relayed through two phones formed no route in that session.

### Identity test

Bluetooth logs of two phones showed both phones' identities in the clear in a control call with the switches off, and none of them in three calls with a stranger (accepted, declined, accepted after an app restart).

### Not measured

Battery cost; reading the Bluetooth name on the A15s; Bluetooth logs of the A15s and
the whole time of the M31; an active attacker holding a contact's key, fake announcements, relays with the new
handshake, message exchange and abuse limits; and the residuals the tool reports but cannot remove: one phone alone in
an area can be followed by the timing of its announcements, and a few capability bits stay the same across IDs. This was
one session on four phones. 
## Security model and limitations

What MeshLive protects, and what it does not. The full rules are in [PROTOCOL.md](PROTOCOL.md); to report a
problem privately, read [SECURITY.md](../SECURITY.md).

**Encrypted and authenticated.**

- Every phone has a persistent P-256 signing identity in the Android Keystore; there are no accounts or servers.
- Every Bluetooth connection starts with a signed handshake with fresh ephemeral keys. After it, records are sealed
  with AES-256-GCM, ordered and replay-protected. Tampering, replay, reordering and a wrong key fail closed. There is
  no plaintext mode and no silent downgrade.
- Media and call signals are additionally sealed end to end between the source and each viewer (or the two call
  endpoints). A relay in the middle forwards ciphertext without being able to read, forge or reorder it. A viewer that
  forwards is an admitted viewer and can decrypt its own copy.
- Store-and-forward message content is sealed to the recipient and stored encrypted at rest. The in-app emergency wipe
  deletes the keys first.

**What is not hidden.**

- **Discovery is public.** Anyone with a Bluetooth scanner can see that a MeshLive phone is nearby, whether it offers a
  stream or accepts calls, and the stream's rough size and bitrate.
- **Rotating IDs, with limits.** Announcements carry a random ID that changes every 15 minutes (on by default), so a passive scanner could not follow a phone across the three ID changes we measured. A live stream
  source's identity, Classic Bluetooth addresses, paired phones, a lone phone's announcement timing and a few capability
  bits remain linkable; see [What nearby phones can see](#what-nearby-phones-can-see).
- **Bluetooth metadata.** Bluetooth names and addresses, the existence of a connection, and the size and timing of
  encrypted records are visible to a radio observer and to every relay on the path. That reveals "a call is
  happening" and often "voice only" versus "video". A first Classic contact with an unpaired phone can expose its name
  and address while it is discoverable.
- **No authenticated radio link on LE.** LE GATT and CoC (and Classic without pairing) have no Android-level
  authenticated link key. MeshLive's own handshakes do not depend on one, so an active attacker between the phones
  cannot read or forge content, but can drop, delay or jam traffic.
- **A prefix is not an owner.** Discovery binds a connection to a 48-bit prefix of the node ID, which a determined
  attacker could try to grind a key for. Compare the QR code or safety code with a contact in person; a verified
  contact whose key changes is refused by default.
- **Relays can disrupt.** A malicious relay or advertiser can drop, delay or refuse a stream or call, attract it to a
  longer route or report a false hop count, but it cannot impersonate an endpoint or open the media.
- **Names and titles** (experimental) are signed and sent inside the encrypted session, but a stream title is also
  advertised publicly while the option is on.

**Not done yet.**

- **No independent security audit.** The primitives are standard (P-256, ECDH, HKDF-SHA-256, AES-256-GCM), but the
  protocol composed from them has not been reviewed by outsiders and is not a certification.
- Human ownership verification beyond comparing codes, live group re-key or revocation, and trust-on-first-use pinning
  of unverified peers are open work ([PLAN.md](PLAN.md)).
- The emergency wipe cannot undo flash remanence, copies outside the app (backups, screenshots, the system log, other
  people's phones), or malware already on the phone.
- Video over Bluetooth is slow and its recovery after link loss has a gap; do not depend on it for safety.
