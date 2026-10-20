# Verification

## V0.5.0 design and reliability checks (20 October 2026)

- Debug and release builds package the new Compose UI and the new design system. Debug
  and release lint both completed with **0 errors**; the remaining advisories are
  dependency-update notices, the KSP migration suggestion and the supplied launcher
  artwork shape.
- The four bundled families (Lobster, Lobster Two, Lora, Manrope) are SIL OFL 1.1 and
  their licence texts ship in `third_party_fonts/`.
- **48 JVM tests passed**, zero failures:
  - 12 source/schema/coverage/chapter validation fixtures
  - 10 transcription, view and normalisation tests
  - 6 playback timeline tests
  - 5 audio-lock ownership and recovery tests
  - 5 formatter tests
  - 3 HTTP cancellation/error/redaction/truncation tests
  - 3 recording-clock tests
  - 2 bounded knowledge-retrieval tests
  - 2 notes-contract tests
- Release APK and AAB built successfully with R8 and resource shrinking; the APK
  signature was verified with `apksigner` against the local upload key.
- APK SHA-256: `CD02719C38F43C07B364E4C2C3D2D88995AF889D3A93CF2C19F504CF7A2D6AA4`.
- AAB SHA-256: `77ADA79CE3F9ECD88AA5C3F19BDD208C3A405B96BAFDC52D0D84140E0FA4DEA6`.
- The host SQLite migration check now derives its target version from the exported
  schemas instead of a hard-coded number, and asserts that a migration exists for every
  schema version.

### Not re-verified in this iteration

- No physical-device recording, long-session, Bluetooth accessory, locked-screen
  endurance, battery, CPU or thermal measurement was run. See
  [performance procedure](performance.md).
- No instrumented (on-device) test run was performed in this iteration. The 18
  instrumentation tests from the previous release remain in the repository and are
  expected to pass, but they were not executed here.
- The release APK was built and its signature verified, but it was **not** installed and
  walked through on an emulator in this iteration.

## Executed host checks

- Debug app and instrumentation APKs compiled and packaged successfully.
- **Two host SQLite checks passed**. Real migration SQL upgrades the exported schemas to
  the latest version; the checks compare structure to a fresh database, preserve seeded
  content, reject orphan metadata and verify cascade deletion. Host SQLite does not
  replace Android Room checks.
- Lint passed with **0 errors**. Reports: `app/build/reports/lint-results-debug.html`.
- `git diff --check` found no whitespace errors.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Previously executed emulator checks

**18 Android instrumentation tests passed, zero failures/skips**, on the connected
Android 15 / API 35 `CloneApps_Guest_API35` emulator (`emulator-5556`) during the 0.4.x
release.

- `NotesPersistenceTest`: 11 tests cover idempotent merge/checkpoints, manual-edit
  protection and AI candidates, invalid-output rollback, database reopen, stale-editor
  rejection, unapplied-candidate supersession protection, topic-refresh eligibility,
  final commit before audio deletion with evidence retained, missing-coverage audio
  retention, no-speech retry and legacy transcript migration.
- `StorageIntegrityTest`: six tests cover migrations, saved-content/child preservation,
  close/reopen, playback/bookmark metadata and recovery of damaged/unstarted sessions.
- `RecordingFlowTest`: one test captures platform-encoded emulator audio from a visible
  Activity, pauses, saves a bookmark without advancing paused time, resumes, stops,
  validates saved media and recovers a readable segment with a corrupt trailing file.

Results: `app/build/reports/androidTests/connected/debug/index.html`.

## Commands

```powershell
$env:ANDROID_HOME = 'C:\Users\shree\AppData\Local\Android\Sdk'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
./gradlew.bat :app:connectedDebugAndroidTest
py -3 tools/check_migrations.py
```

## Limits of the evidence

- No real OpenRouter inference or paid STT request was made. MockWebServer transport
  tests use dummy tokens. The earlier pasted key is not embedded in the source/APK or
  used by tests. Provider compatibility and actual meeting-output quality still require
  review with the user's configured models.
- Citation/coverage validation establishes exact source linkage and mechanical accounting;
  it does not prove semantic entailment, correct agreement classification or
  comprehensive preservation of every fact. Long-window fixtures check planning/data
  handling and old-topic retrieval, not actual multi-hour model accuracy.
- No physical-device, 30-minute through 12-hour, Bluetooth accessory, locked-screen
  endurance, battery, CPU, thermal, interrupted-network or process-kill WorkManager
  qualification was completed. See [performance procedure](performance.md).
- V3 notes screens, export picker, Media3 playback, TalkBack, large fonts and tablet
  layouts do not yet have full UI acceptance coverage.
- The map is an expandable local topic tree, with saved collapse/title state and
  source-linked notes. Free graph layout/viewport, outside-knowledge expansion, Q&A,
  global FTS, transcript correction history and encrypted portable backup remain future
  scope. Live transcription is deferred by the user's explicit batch-provider choice.
