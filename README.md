# Meeting Atlas

Meeting Atlas is a native Kotlin and Jetpack Compose meeting recorder designed around local storage. Recordings stay on-device until the user explicitly chooses to create notes. The app then transcribes audio through OpenRouter, asks the selected text model for structured notes and a mind map, saves the results locally, and removes the source audio after the notes are durably stored.

## Current implementation

- Native Compose home, recent-meeting list, library, meeting detail, OpenRouter settings, and recording screens.
- Hilt dependency injection and a Room-backed local meeting catalog.
- Microphone permission flow and a microphone foreground service started from an explicit user action.
- AAC/MPEG-4 mono capture to app-private files, pause/resume/stop notification controls, and monotonic elapsed timing.
- Low-power architecture: no live transcription or audio analysis; the service does not refresh the notification every second.
- Storage segments are rolled at a file-size threshold when the device supports `MediaRecorder.setNextOutputFile`; finalized segments have Room metadata.
- Startup recovery keeps readable finalized segments and marks unfinished sessions interrupted.
- OpenRouter transcription and structured meeting notes, including a summary, key points, decisions, action items, and expandable mind-map branches.
- The OpenRouter API key is encrypted with an AES-GCM key held in Android Keystore. Model IDs can be changed in Settings.
- Audio is sent only after the user confirms on the meeting screen. Transcript and audio are sent to OpenRouter for processing; provider retention and model pricing depend on the configured models.
- Source audio is deleted only after a complete transcript, summary, and mind map are saved to Room. If processing fails, the local recording is kept. Transcript and generated notes remain locally available offline.

## Architecture

The initial app is kept as one Android Gradle module to keep the first increment buildable. Packages form the initial boundaries:

- `ui`: Compose screens, theme, and ViewModel.
- `data`: Room entities, DAOs, database, and repository.
- `recording`: foreground service, recorder file handling, process state, and recovery.
- `ai`: Android Keystore-backed OpenRouter key storage, short AAC chunk preparation, transcription and structured summary requests, and post-save audio cleanup.
- `di`: Hilt storage providers.

Room is the catalog source of truth. Audio is stored under app-private `files/recordings/<meeting-id>/`, never in cache. The service is independent of Activity lifetime and serializes user commands. Recording status is persisted at state transitions rather than once per timer tick. The current recorder uses a preferred 24 kHz AAC configuration and retries with the platform default sample rate if preparation fails.

## Build

Requirements: JDK 17+, Android SDK Platform 36, and Android Gradle Plugin-compatible Gradle (8.13 for AGP 8.13). Open this directory in Android Studio or use the Gradle wrapper once generated. Set `ANDROID_HOME` to your Android SDK location if it is not already configured.

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
```

Installable debug APK output is `app/build/outputs/apk/debug/app-debug.apk`.

## Recording limits and verification

The microphone foreground service follows Android's visible-start and runtime-permission rules. Android cannot guarantee uninterrupted capture after force-stop, permission revocation, reboot, storage exhaustion, or manufacturer process termination. The app preserves readable finalized segments and marks incomplete sessions interrupted when it can recover them. MediaRecorder file rollover is size-based, not an exact-duration guarantee, and some devices may not support rollover reliably.

No physical-device session, long-duration recording, battery profile, interruption, or recovery test has been completed in this workspace. Do not treat this increment as release-ready. Before a production release, exercise screen-off recording, notification controls, pause/resume, low storage, process termination, and 1+ hour sessions on several real devices.

## Current limitations

Playback, audio import, transcript editing, meeting Q&A, search, backup/export, encrypted audio at rest, and performance instrumentation are not implemented yet. Transcription uses OpenRouter's configured speech-to-text model. Long recordings are repackaged into short audio chunks before upload; long transcripts are condensed in sections before final notes are generated. Confirm your chosen models, provider data handling, and pricing in OpenRouter before processing private meetings.
