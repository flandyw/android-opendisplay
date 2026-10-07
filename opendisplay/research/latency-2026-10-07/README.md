# Pipeline latency improvements, 2026-10-07

The sender still uses one hardware encode in flight. Prior measurements show that additional in-flight encodes increase latency, particularly at 5K. This change improves how that one slot is used: when an encode finishes, the latest skipped capture can enter immediately, subject to the selected frame-rate deadline. It no longer needs to wait for another ScreenCaptureKit callback or a whole additional replay interval. New captures replace the cached pixels without pushing an existing replay deadline back.

Other changes:

- Sender and receiver video queues use interactive QoS; the sender requests the interactive video network service class.
- Capture submits only complete ScreenCaptureKit frames.
- Outstanding sends are capped at three frames and a byte threshold of two nominal frames, with a 64 KiB floor. An oversized IDR can be submitted by itself. This bounds work waiting for Network.framework; `contentProcessed` is not an acknowledgement from the receiver and does not measure kernel or network queues.
- Encode and send completions from retired pipelines cannot decrement the new pipeline's counters.
- Static screens replay a keyframe promptly after the peer handshake or a keyframe request, instead of depending on the two-second watchdog.
- The sender assembles the wire header, telemetry, parameter sets and video slices in one buffer.
- The receiver locates start-code candidates with `memchr`, retains picture NALs as ranges, and writes AVCC directly into one CoreMedia-owned allocation. Parameter sets are still copied because they outlive their packet.
- A full display decoder queue is flushed, then dependent frames are skipped until a keyframe restores synchronization. Recovery trades a brief pause for discarding queued stale video. Failed decoders and resume from background use the same synchronization gate.
- Current OS versions use `sampleBufferRenderer.enqueue`; older supported systems retain the display-layer API. The optional explicit VideoToolbox decoder requests real-time processing.
- Invalid video packet lengths terminate the connection rather than growing an unbounded receive buffer.

## Local conversion benchmark

`tools/quality/payloadbench.swift` compares the previous scanner and per-NAL copies against the new scanner and direct AVCC writes. Both paths allocate their final CoreMedia block and produce identical bytes. Eight synthetic NALs contain deterministic pseudo-random bytes with emulation prevention. Both paths are warmed and measured in alternating order over six runs, compiled with `swiftc -O`.

| Nominal picture bytes | Previous conversion | New conversion | CPU time reduction |
| --- | ---: | ---: | ---: |
| 32 KiB | 0.024 ms/frame | 0.003 ms/frame | 89% |
| 128 KiB | 0.096 ms/frame | 0.010 ms/frame | 90% |
| 1 MiB | 0.733 ms/frame | 0.077 ms/frame | 90% |

These numbers measure parsing, allocation and copying only. They do not measure encode throughput, transport latency or capture-to-photon delay.

## Validation

- 123 macOS unit tests pass, including replay deadline, send budget, scanner equivalence, sliced `Data`, AVCC output and CoreMedia-owned-memory checks.
- Debug sender, universal arm64/x86_64 Debug receiver, and iOS simulator builds pass. Debug bundle identifiers are unchanged.
- Synthetic hardware-encoded H.264 and HEVC each decode all four input frames through the actual `StreamReceiver` and its explicit VideoToolbox path over loopback TCP. Headers and payloads are deliberately split across sends to exercise deframing.
- The Intel test host `ssh imac` does not resolve in this environment. Fullscreen end-to-end latency and visual-quality measurements on that host remain unmeasured.

Reproduce the conversion benchmark:

```sh
swiftc -O Shared/VideoPayload.swift tools/quality/payloadbench.swift -o build/payloadbench
build/payloadbench
```
