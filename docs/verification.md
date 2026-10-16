# V3 verification

## V0.4.1 UI and performance checks (16 October 2026)

- Debug and release builds package the updated Compose UI. Debug and release lint both completed with 0 errors (35 advisory warnings each). The release screen was installed and visually checked on the Android 16 / API 36 emulator: [updated home screen](screenshots/v4-ui-release-home.png).
- The home meeting list now uses lazy composition. Meeting labels and note topic/chapter groupings are reused across recompositions; state flows on detail/settings now stop collecting while their screen is not active.
- No test suite or physical-device performance benchmark was run for this UI iteration. See [performance procedure](performance.md) for measurements still needed on real devices.
- APK SHA-256: `C6DE2A21AD892AF90EF1AAB2F545D206E00490BE53BCF80096254DD78B389995`; AAB SHA-256: `5BFCDB7B1F566073608A0C0E642503C5870528A539DA224EBAA87D36D996324C`.

## V0.4 release checks (16 October 2026)

- Release APK and AAB built successfully with R8 and resource shrinking; both artifact signatures verified. Release lint passed with **0 errors and 35 advisory warnings** (`app/build/reports/lint-results-release.html`). Debug lint also passed.
- The release APK installed and launched on an Android 16 / API 36 emulator as `com.meetingatlas`, version 0.4.0 (code 4), target API 36. The supplied logo was visually inspected in the home header: [release home screen](screenshots/v4-home.png).
- APK SHA-256: `3A5085DDD4AB8CEC01AA69258C34275B4843A7E142003107C8615970CE952D20`.
- AAB SHA-256: `2C8F6594737E6031872D58057DA6B270236F66B4300A1089EC289F1A208C0896`.
- A separate local upload key is stored under the Git-ignored `.release/` directory. These checks verify local build/sign/install only; they do not constitute a Play Console upload or production release.

Date: 16 October 2026. Version 0.3.0 (code 3), Room schema 4. Scope: OpenRouter batch transcription and the shared notes engine, alongside the recording/playback foundation. These checks do not certify every feature in the master product brief.

## Executed host checks

- Debug app and instrumentation APKs compiled and packaged successfully.
- **33 JVM tests passed**: 10 recording/timeline/ownership tests; 11 source/schema/coverage/chapter validation fixtures; seven transcription/view tests; three HTTP cancellation/error/redaction/truncation tests; two bounded knowledge-retrieval tests.
- **Two host SQLite checks passed**. Real migration SQL upgrades exported schemas 1, 2 and 3 to schema 4; checks compare structure to a fresh database, preserve seeded content, reject orphan metadata and verify cascade deletion. Host SQLite does not replace Android Room checks.
- Lint passed with **0 errors and 28 advisory warnings**. Reports: `app/build/reports/lint-results-debug.html`. Suggestions include dependency updates, KSP/KTX, target API and the inlined microphone foreground-service constant passed through ServiceCompat. Lint runs after compilation to avoid changing annotation-processing stubs underneath analysis.
- `git diff --check` found no whitespace errors (Git reports the workspace's expected LF/CRLF conversion notices).

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

SHA-256: `1A2CC48C261DBCCA5861B7D9E4D5438772B459AE8C3A4D840969825810C4499A`.

## Executed emulator checks

**18 Android instrumentation tests passed, zero failures/skips**, on the connected Android 15 / API 35 `CloneApps_Guest_API35` emulator (`emulator-5556`). Earlier attempts to start a separate API 36 AVD had failed; the later available emulator enabled these runtime checks.

- `NotesPersistenceTest`: 11 tests cover idempotent merge/checkpoints, manual-edit protection and AI candidates, invalid-output rollback, database reopen, stale-editor rejection, unapplied-candidate supersession protection, topic-refresh eligibility, final commit before audio deletion with evidence retained, missing-coverage audio retention, no-speech retry and legacy transcript migration without invented timing or lost old notes.
- `StorageIntegrityTest`: six tests cover migrations, saved-content/child preservation, close/reopen, playback/bookmark metadata and recovery of damaged/unstarted sessions.
- `RecordingFlowTest`: one test captures actual platform-encoded emulator audio from a visible Activity, pauses, saves a bookmark without advancing paused time, resumes, repeatedly stops, validates saved media and recovers a readable segment with a corrupt trailing file. This does not prove physical microphone quality or audible rollover continuity.

Results: `app/build/reports/androidTests/connected/debug/index.html`; XML under `app/build/outputs/androidTest-results/connected/debug/`.

The debug APK was installed and `com.meetingatlas/.MainActivity` launched successfully after testing. Android Studio opened this project. The empty home screen was visually inspected: [captured screen](screenshots/v3-home.png). No generated meeting data was inserted into the user's library for the screenshot.

## Commands

```powershell
$env:ANDROID_HOME = 'C:\Users\shree\AppData\Local\Android\Sdk'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
./gradlew.bat :app:connectedDebugAndroidTest
python tools/check_migrations.py
```

## Limits of the evidence

- No real OpenRouter inference or paid STT request was made. MockWebServer transport tests use dummy tokens. The earlier pasted key is not embedded in the source/APK or used by tests. Provider compatibility and actual meeting-output quality still require review with the user's configured models.
- Citation/coverage validation establishes exact source linkage and mechanical accounting; it does not prove semantic entailment, correct agreement classification or comprehensive preservation of every fact. Long-window fixtures check planning/data handling and old-topic retrieval, not actual multi-hour model accuracy.
- No physical-device, 30-minute through 12-hour, Bluetooth accessory, locked-screen endurance, battery, CPU, thermal, interrupted-network or process-kill WorkManager qualification was completed. See [performance procedure](performance.md). The short emulator recording test is not evidence for those targets.
- V3 notes screens, export picker, Media3 playback, TalkBack, large fonts and tablet layouts do not yet have full UI acceptance coverage. Only the empty home was visually inspected.
- The map is an expandable local topic tree, with saved collapse/title state and source-linked notes. Free graph layout/viewport, outside-knowledge expansion, Q&A, global FTS, transcript correction history and encrypted portable backup remain future scope. Live transcription is deferred by the user's explicit batch-provider choice.
