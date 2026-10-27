# Call audio coalescing promoted (2026-10-27)

On a slow direct link, call audio that queued behind a blocking video write went out one 20 ms record at a time, each
with its own framing, ATT value or CoC SDU and two AES-GCM operations, so the backlog grew. Coalescing sends the units
already waiting as one existing `BTA1` pair or `BTA2` batch record (no added delay, no wire change). Direct GATT video
calls, 25 s per arm, ABBA x3: M31 <-> S22 control 747 sender queue drops and call audio queue age up to 3.7 s, coalescing
0 drops and at most 20 ms; ASUS <-> A15s video 13.3 -> 15.0 fps, sender drops 136 -> 102. Promoted to default on with the
owner's go-ahead (build 4); DEBUG `test_call_audio_coalesce=false` keeps the old behaviour as the control arm. Call audio
pairs stay Experimental.
