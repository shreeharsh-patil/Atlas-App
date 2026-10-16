# Recording qualification and performance

Status: targets and procedure, not benchmark results. No physical-device measurements have been collected in this workspace. A software emulator cannot establish battery efficiency, Bluetooth quality, thermal behavior or long-session stability.

## Device matrix

| Class | OS range | Required coverage |
|---|---|---|
| Constrained phone, 3–4 GB RAM | API 26–30 | AAC fallback, single-file fallback, storage pressure, process recovery |
| Midrange phone, 4–6 GB RAM | API 31–33 | Bluetooth/SCO, route disconnect, notification permission, locked screen |
| Current Pixel-class phone | API 34–36 | microphone foreground restrictions, silenced input, Media3 audio focus, thermal reporting |
| Manufacturer with additional power policies | API 33–36 | 6-hour lock screen, interruption/reopen, restricted battery mode |
| Tablet/foldable | API 34–36 | navigation, rotation, wide/short screen, large fonts and playback |

Use physical devices for release qualification. Record exact model, OS build, free storage, battery health, ambient conditions, Bluetooth accessory and app commit/APK hash.

## Duration matrix

For Balanced, test 30 min, 1 h, 3 h and 6 h on every phone class. Test 12 h on at least a midrange and a current device where power/storage allow. Repeat screen-on and screen-off conditions. Exercise Storage Saver and High Quality at 30 min and 3 h, and each device's sample-rate fallback. No arbitrary app duration cap is introduced.

## Goals to validate

| Area | Engineering goal | Measurement |
|---|---|---|
| Capture | No app-induced gap across a successful supported rollover | known continuous test signal; decode boundaries and inspect waveforms |
| Memory | Bounded footprint; no duration-proportional heap growth | start/30 min/1 h/3 h/6 h Java/native PSS and heap retention |
| CPU | Screen-off work limited to encoding, writes and essential monitoring | Perfetto thread traces and process CPU-time deltas |
| Battery | Explain additional drain relative to matched idle and platform recorder | BatteryStats/charge counter under comparable conditions, several repeats |
| File size | Near bitrate × captured seconds / 8 plus measured container overhead | sum actual segment sizes, report preset and actual codec/rate |
| Persistence | No loss of already validated segments after interruption | compare file hashes/manifest before and after recovery |
| Local UI | Responsive navigation and no growing local-query latency as history grows | frame timing at device refresh budget; query p50/p95 on measured dataset |
| Thermal | No AI inference during recording; local work defers at severe thermal status | trace ownership and worker transitions with thermal log |

Do not publish percentages, gap durations or battery figures until measured. A target is not a guarantee. Do not compare absolute battery percentages across unrelated devices.

## Procedure

1. Use a release-like build without a debugger for power measurements. Disable automatic content upload; record a nonprivate reference signal with known duration. Record local settings and selected audio route.
2. Capture a matched idle baseline with the same radios, screen state and temperature. Repeat the same duration with recording; report variance and charging state. Test charging sessions separately.
3. Collect `adb shell dumpsys meminfo com.meetingatlas`, process CPU times and BatteryStats; use Android Studio/Perfetto for scheduling, I/O, allocations and frame time. Keep traces free of transcript/audio payloads. Avoid continuous verbose logging or profiling overhead in the battery baseline.
4. At segment rollover, pause/resume, route changes and low-storage warnings, inspect the debug journal and validate each output with a decoder. Container duration metadata alone cannot prove sample continuity or speech intelligibility.
5. Repeat with screen locked, app switched away, Activity recreated, playback stopped/started, notification actions repeated, storage pressure, microphone revocation, phone call, competing capture, accessory disconnect, process kill and force-stop. Reboot then reopen; do not expect automatic capture resumption.
6. Reopen offline and verify meeting metadata, timeline, bookmarks, notes and player errors. Inspect corrupted trailing media recovery and ensure good earlier segments remain available. Source audio removed by the explicit retention policy is intentionally unavailable for playback.
7. Preserve the result sheet with real observations, error codes, trace locations and limitations. File regressions against the relevant slice; do not enable lower-priority features to mask recording defects.

## Suggested result record

`device | OS | APK SHA-256 | preset/effective rate | route | duration | screen state | start/end free bytes | CPU-time delta | PSS samples | idle battery baseline | recording battery samples | segment validation | measured gaps | service errors | thermal state | trace references`

All result fields currently remain **not measured** until a real run is completed.
