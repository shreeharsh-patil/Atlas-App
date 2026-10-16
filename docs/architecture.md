# Architecture overview

The implemented batch pipeline is documented in [V3 notes](v3-notes.md). The wider implementation sequence is [Meeting Atlas V2](v2-plan.md). [Verification](verification.md) separates executed host checks from pending device tests; [performance qualification](performance.md) defines the real-device matrix without invented results.

## Phase 1 boundary

The first increment keeps a single Android module so the project can stay buildable while the product boundaries are still evolving. Presentation, persistence, dependency injection, and recording each have separate packages and a narrow dependency direction. The service writes only status transitions and final segment metadata, not high-frequency timer values.

## Recording data flow

1. The user taps Start while the Activity is visible.
2. The app requests microphone permission (and notification permission on Android 13+), creates a local meeting row, and starts a microphone foreground service.
3. The service immediately enters foreground mode, configures `MediaRecorder`, and writes encoded AAC/M4A to app-private durable files.
4. UI and notification commands are serialized by the service. Pause and resume use platform recorder controls; stop finalizes and validates files before updating Room.
5. Startup recovery inspects unfinished meetings and retains readable finalized files as interrupted sessions.

## Safety boundaries

- No automatic cloud upload, account, boot receiver, or hidden microphone capture.
- No AI work runs during recording.
- No permanent wake lock is held. Screen-off behavior must be verified on real hardware.
- A MediaRecorder rollover is best effort: the device determines callback timing and encoded file behavior. It is not an exact-time split and does not make an unfinalized MP4 recoverable.
- App-private storage and disabled Android backup avoid accidental system backup of meeting content. Uninstalling can remove app-private content; an explicit archive/restore feature is still planned.

## Next increments (V2 slice order)

1. Qualify the implemented journal, validated recovery and Media3 playback on devices.
2. Complete long-session, background and battery qualification before enabling heavy local AI.
3. Qualify implemented WorkManager checkpoints and timestamped batch transcripts; add transcript correction history and global FTS.
4. Evaluate implemented source-linked notes, chapters, action completion, templates and revision protection on real meetings.
5. Migrate maps into transactional graph records; persist expansion, edits and viewport state.
6. Add cited Q&A and cross-meeting retrieval.
7. Complete adaptive navigation, import/export, encrypted restore, accessibility and release qualification.
