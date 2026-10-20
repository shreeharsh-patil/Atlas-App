# Meeting Atlas

Meeting Atlas is a native Kotlin and Jetpack Compose meeting recorder with Room-based local storage. Record, save, reopen and play segmented audio offline. Creating notes explicitly sends audio/transcript to OpenRouter, saves the results locally and applies the user's audio-retention setting. The default retains the earlier requested behavior of removing source audio only after notes are saved; turn this off in Settings to preserve playback.

Version 0.5.0 is the design and reliability release. It introduces the Meeting Atlas
design system: **Lobster** for the wordmark and screen titles, **Lobster Two** for true
italic display accents, **Lora** as the reading face with real italics, and **Manrope**
for interface chrome. The home screen is now a hero panel over a deep green wash with
the supplied mark, a brass accent and raised cards. The same iteration fixes a wide set
of recording, playback, notes and build defects; see [release notes](docs/release.md)
for the full list. Per the user's selected scope, transcription uses OpenRouter after
recording is saved. [V3 implementation](docs/v3-notes.md) describes its contracts and
limits; [V2 architecture and slice plan](docs/v2-plan.md) tracks the wider product
scope. Device qualification and model-quality review remain required.

## Current implementation

- Native Compose home, recent-meeting list, library, meeting detail, OpenRouter settings, and recording screens, built on a shared design system (`ui/theme/Type.kt`, `ui/theme/Theme.kt`, `ui/AtlasComponents.kt`).
- Four bundled type families (Lobster, Lobster Two, Lora, Manrope — all SIL OFL 1.1) with a complete Material 3 typography scale, a corner-radius scale and a brand palette.
- Hilt dependency injection and a Room-backed local meeting catalog.
- Microphone permission flow and a microphone foreground service started from an explicit user action.
- AAC/MPEG-4 mono capture to app-private files, pause/resume/stop notification controls, and monotonic elapsed timing.
- Low-power architecture: no live transcription or audio analysis; the service does not refresh the notification every second.
- Storage segments are rolled at a file-size threshold when the device supports `MediaRecorder.setNextOutputFile`; finalized segments have Room metadata.
- Startup recovery keeps readable finalized segments and marks unfinished sessions interrupted.
- Room session journal, stable segment identities, integrity checks and bounded technical diagnostics in debug builds. Updating a meeting uses Room Upsert and preserves child data.
- Media3 playback with one logical timeline, cross-segment seeking, 10-second skip, speed, saved position and standard background playback controls. Capture pauses playback.
- Timestamped recording/notification bookmarks; edit labels, seek available audio and reopen bookmarks offline.
- DataStore quality presets (Storage Saver/Balanced/High Quality), device AAC configuration fallback, input-change detection, optional Android 12+ Bluetooth communication routing, Android 10+ silenced-input pause, and 30-second storage checks.
- Actual microphone amplitude at 10 Hz only with a visible recording waveform subscriber. A contextual recording bar opens the ongoing session from other screens.
- Explicit whole-meeting deletion, local save errors, and a shared ownership gate that excludes AI processing during recording.
- Durable WorkManager processing with network/battery constraints, optional charging-only scheduling, bounded retries, pause/resume and Room checkpoints. Completed transcription chunks survive extraction failures.
- Timestamped OpenRouter batch transcription in streamed two-minute AAC chunks. Provider segment timestamps, approximate chunk intervals and unknown legacy timing remain distinguishable.
- One normalized knowledge set powers Quick, Standard, Detailed and Study notes plus a saved expandable topic tree. Seven templates guide extraction without adding missing information.
- Exact transcript quotes, source revisions, per-window coverage accounting, distinct decisions/proposals/actions, evidence-gated owners/deadlines, and chapters only from contiguous provider-timed sources. Structural checks do not prove semantic accuracy.
- Editable notes, revision history, protected manual edits, separately retained AI candidates, highlights, action completion, topic renaming/collapse, scoped refresh, paged transcript search and Markdown export with evidence.
- The OpenRouter API key is encrypted with an AES-GCM key held in Android Keystore. Model IDs can be changed in Settings.
- Audio is sent only after the user confirms on the meeting screen. Transcript and audio are sent to OpenRouter for processing; provider retention and model pricing depend on the configured models.
- With the audio-retention policy enabled, source audio is removed only after nonempty final notes and evidence are committed to Room. Failed, paused, interrupted, no-speech and empty-notes runs keep audio. Cleanup can be retried separately. Transcript and notes remain available offline. `openrouter/free` is the text-model default with no paid text fallback; Whisper transcription is billed separately by OpenRouter.

## Architecture

The initial app is kept as one Android Gradle module to keep the first increment buildable. Packages form the initial boundaries:

- `ui`: Compose screens, theme, and ViewModel.
- `data`: Room entities, DAOs, database, and repository.
- `recording`: foreground service, recorder file handling, process state, and recovery.
- `ai`: Android Keystore-backed OpenRouter key storage and cancellation-aware streaming HTTP requests.
- `notes`: WorkManager orchestration, AAC remuxing, typed extraction/validation, transactional merges, shared view projections and export.
- `di`: Hilt storage providers.

Room is the catalog source of truth. Audio is stored under app-private `files/recordings/<meeting-id>/`, never in cache. The service is independent of Activity lifetime and serializes user commands. Recording status is persisted at state transitions rather than once per timer tick. The current recorder uses a preferred 24 kHz AAC configuration and retries with the platform default sample rate if preparation fails.

## Build

Requirements: JDK 17+, Android SDK Platform 36, and Gradle 8.13 (bundled with the committed wrapper; AGP is pinned to 8.13.2 in `gradle/libs.versions.toml`). Open this directory in Android Studio or run `./gradlew.bat` / `./gradlew` directly. Set `ANDROID_HOME` to your Android SDK location if it is not already configured.

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebugAndroidTest
./gradlew.bat :app:lintDebug
py -3 tools/check_migrations.py
# With a connected Android device:
./gradlew.bat :app:connectedDebugAndroidTest
```

Installable debug APK output is `app/build/outputs/apk/debug/app-debug.apk`. The signed
release APK is `app/build/outputs/apk/release/app-release.apk`; see
[release preparation](docs/release.md) for the signing setup and checksums.

## Recording limits and verification

The microphone foreground service follows Android's visible-start and runtime-permission rules. Android cannot guarantee uninterrupted capture after force-stop, permission revocation, reboot, storage exhaustion, or manufacturer process termination. The app preserves readable finalized segments and marks incomplete sessions interrupted when it can recover them. MediaRecorder file rollover is size-based, not an exact-duration guarantee, and some devices may not support rollover reliably.

The signed Android release is prepared for installation and Play Console testing. No physical-device session, long-duration recording or battery profile has been completed, and no Play Store submission has been made. See [release preparation](docs/release.md), [verification status](docs/verification.md), and [device/performance qualification](docs/performance.md).

## Current limitations

Live processing is deferred by the user's choice to keep OpenRouter batch transcription. Audio import, on-device inference, transcript correction history, meeting Q&A, global FTS, a pan/zoom graph with saved viewport/relationship edges, outside-knowledge expansion, cross-meeting tasks, portable backup/restore and encrypted audio at rest remain future work. The current mind map is a saved expandable topic tree. Older notes/maps remain readable, and their transcript is migrated only when explicitly processed. No actual cloud request, physical-device recording, battery measurement or model-quality evaluation has been performed for this version. Recovery cannot reconstruct unreadable audio or guarantee original timestamp alignment across missing middle segments.
