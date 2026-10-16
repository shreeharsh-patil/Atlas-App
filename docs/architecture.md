# Initial architecture and implementation plan

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

## Next increments

1. Make recorder recovery and segmented playback robust across physical devices; add a segment manifest migration and file validation.
2. Add Media3 playback and import through Android's document picker.
3. Add editable local transcript, summary, decisions, action items, and search models.
4. Add opt-in AI processing behind explicit consent; persist validated results locally before showing them.
5. Add persistent mind-map graph, evidence-linked expansion, and grounded Q&A.
6. Add encrypted export/restore, accessibility, performance testing, CI, and release hardening.
