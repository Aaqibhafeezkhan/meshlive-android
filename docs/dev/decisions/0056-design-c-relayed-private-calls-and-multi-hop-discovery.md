# Design C: relayed private calls and multi-hop discovery

**Design review required.** Default off (`test_relayed_calls`, Settings > Advanced "Experimental: relayed calls"). The
design was reviewed independently on 2026-10-11 and builds on the earlier draft (R14-R22) and on these design
decisions: hop-local reading accepted, relay helping on by default while the app runs once the feature is enabled,
consent asked once and preselected, a radius of 2 relays. Wire rules: PROTOCOL.md "Experimental relayed calls and
multi-hop discovery". With the switch off, every byte on air and on the link is unchanged.

**Goal.** A caller A reaches a call-ready phone D that is one or two relay phones away, and an ordinary phone relays
calls in the background without being told which call to take. Before this, only DEBUG relays existed (a launch extra
names the callee).

- **Roles and reach table.** Target D (call-ready, consents), relay helper R (opted in, call endpoint idle), caller A.
  Each helper keeps a table of targets fed only by adverts it scanned itself: D's own call offer gives a cost-1 entry, a
  neighbour's reach-page entry gives cost + 1, up to the cap (default 2, DEBUG `test_relayed_calls_max_relays`, at most
  4). Reach pages are a second legacy non-connectable advertising set (one page per second).
- **On-demand setup.** A connects to R1's call listener, authenticates the hop, and sends `SETUP(target, budget,
  remaining time, setup ID)` as the first hop record (hop type 12). R1 checks its table, connects to the next hop
  itself and regenerates the same message downstream; `READY` and `FAIL` return one hop at a time. After READY the
  existing opaque call relay copies the ordered end-to-end records, and A runs the ordinary end-to-end handshake
  through the chain.
- **Hop-local, with stated stretch.** A phone re-advertises only what its direct neighbours told it, and setup is
  regenerated at every hop with a new ID, smaller budget and shorter deadline. Reachability spreads by repeated
  re-advertisement (a distance vector with an age), and the target's identifier travels the chain inside the SETUP, so
  each relay on the path learns its adjacent links, the target and (from the signed end-to-end hello it copies) both
  endpoint identities. A passive observer near a relay learns that D is within N hops of it.
- **Identity and abuse limits.** A relay cannot read or forge content (end-to-end keys). A requires the end-to-end
  peer to match the target it selected (the full fingerprint for a verified contact); each helper link identity must
  equal the helper offer that was selected; the last relay accepts only the target or another relay as its child.
  Absolute deadlines from READY (10 s end-to-end handshake, 20 s Invite) and a 60 s ringing cap stop a caller from
  pinning helpers.
- **Rotating IDs.** With both experiments on, relaying uses role-0 rotating IDs only (reach pages version 6, `SETUP`
  message 4, every hop checked with `BDO1`/`BDX1`); DEBUG `test_relayed_rotating=false` restores the earlier exclusion.
- **Battery and pre-emption.** Helping runs only while the phone is call-ready and none of these hold: battery below
  20% and not charging, Battery Saver, severe thermal status, Bluetooth off, the phone's own exchange. A relayed call
  already running is not cut by a pause. Starting or answering the phone's own call or stream ends any relay in
  progress. The foreground notification reads "Helping a call nearby" with a Stop action. Idle scanning cost per hour is
  a gate to measure on each reference phone before any default is considered.
- **Failure and fallback.** A shows the route ("Nearby", or "Via 2 phones, heard 8 s ago, unconfirmed"), picks the
  fewest relays then the freshest, and skips a failed pair for 30 s. A chain that breaks mid-call ends the call;
  automatic re-routing is future work. Direct fallback is a failed requested chain: `test_relayed_calls_require=<n>`
  accepts only a chain of exactly n relays. One bearer end to end; only LE transports are supported on demand. iPhones
  can neither help nor be relayed targets until the compact amendment.
- **Deferred:** automatic candidate iteration inside one attempt, the embedded single-entry carrier for phones without a free advertising set, Classic on-demand relays, mid-call re-routing, extended-advertising reach lists and an iPhone caller. Phone-probe history: [decisions](README.md) "Relayed calls: first phone probe".
- **Phone verification queue** (one helper at a time, `test_relayed_calls=true` on every participant): run
  `python -B tools/dex_api_scan.py` on the APK first; then one helper on GATT with `test_relayed_calls_require=1`,
  rotating IDs off and on (S24 -> ASUS -> M31 and the reverse, then each phone as helper; the M31's dual-role limit
  decides which may help), then the same on LE CoC. Record setup time (invite to ringing) and compare quality with the
  DEBUG chain at the same settings (relayed video cap 15 FPS / 110 kbps).
