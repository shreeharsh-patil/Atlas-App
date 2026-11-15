# Atlas voice dictation

For the subsequent layout and overlay redesign, see the [Android visual QA review](dictation-ui.md). The build results and checksums below describe the original dictation implementation pass.

Dictation is a new feature in the existing `com.meetingatlas` application. The **Meetings** and **Library** tabs open the existing recording, playback, and notes screens. Meeting files and Room schema versions 1–4 are unchanged.

## Build and install

Use JDK 17, Android SDK 36 and the repository wrapper. From the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
./gradlew.bat :app:lintDebug
python tools/check_migrations.py
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat :app:connectedDebugAndroidTest
./gradlew.bat :app:lintRelease :app:assembleRelease -PallowUnsignedRelease=true
```

Use a test device or emulator for instrumentation. The debug and release packages use the same package name; Android requires matching signatures for an in-place update. If the installed release uses a different signing key, use a separate device or build with the original release key to preserve existing data.

On **Dictation → Home**, review and accept the disclosure, grant microphone access, allow Atlas to display over other apps, then enable **Atlas voice dictation** in Android Accessibility settings. Recording notifications are optional on Android 13+ and give you another visible way to cancel while recording. Choose Bluetooth input only if needed and grant Nearby devices then.

In **Dictation → Settings**, save a speech model ID and the API key for your selected provider. OpenRouter shares its existing encrypted key with Meeting Atlas, but each feature has its own model settings. Choose a model from the [OpenRouter STT catalog](https://openrouter.ai/collections/speech-to-text-models) (for example, `openai/whisper-large-v3`), or a documented [OpenAI speech model](https://developers.openai.com/api/docs/guides/speech-to-text) (for example, `gpt-transcribe`). Account access, prices, and language coverage vary. Light is selected by default; save an explicit compatible chat model to use Light or Medium cleanup. Without a chat model, Atlas inserts the raw transcript. No alternate model is chosen automatically.

Run the microphone test, then open a regular text field in another app. Tap the bubble to record, then tap the check mark to submit, or hold it and release to submit. Drag it to move it. If insertion is unavailable, tap the result bubble if visible or reopen Atlas and use **Latest transcript → Copy**, then paste manually. If Atlas could not verify the insert, check the destination first. After a failed upload, **Retry audio** reuses the pending recording and shows its text in Atlas. Retry never targets a different field or automatically resends a result.

## Audit and fixes

| Area | Finding and change |
| --- | --- |
| Activity, manifest and UI | The original Activity owned navigation and meeting recovery, but there was no external launch surface, accessibility service, overlay, or input connection. Dictation now has a separate foreground service, a non-focusable application overlay, onboarding, and a Dictation home. Meeting screens remain on their existing paths. |
| Meeting capture and playback | Long recordings use segmented AAC and a process-wide audio owner. The service used to stop an existing capture when it received a duplicate start command. Active recording now survives that command, and a duplicate-start instrumentation assertion covers it. Dictation and meeting capture cannot take the microphone together. Playback continues to honor the capture owner; dictation does not use the meeting recorder or journal. |
| Speech transport | Meeting STT expects timed statements and uses long HTTP timeouts. A 2xx provider error or response without transcript fields could be mistaken for silence; Atlas now rejects that response. An accepted AAC timestamp just beyond the chunk end could create an invalid clamp interval, covered by a new regression test. Dictation uses a bounded WAV request, a 30 s call timeout, reusable HTTP connections, strict text response validation, and cancellable uploads/body reads. |
| Notes and scheduling | Structured extraction, evidence validation, Room checkpoints, and the existing retry policy remain in the meeting pipeline. WorkManager operations now suspend, and enqueue cancellation propagates without persisting a misleading failure. |
| Data and credentials | Dictation has its own Room database and DataStore preferences. Existing meeting migrations are unchanged. OpenRouter keeps its existing ciphertext slot; OpenAI has a separate Keystore-encrypted slot. Malformed ciphertext is treated as a key that needs re-entry. |
| QA and release | Existing CI already builds debug, test, and R8 release variants, runs host tests, and checks meeting migrations. Dictation adds policy, PCM, provider and on-device tests. Compiling the instrumentation APK does not test cross-app behavior. |

## Runtime behavior and completion

| Capability | Implementation and limits |
| --- | --- |
| Floating microphone | Tap, hold-to-talk, Done, Cancel, drag, sizes from 48 to 72 dp, opacity, live audio meter, status and retry. The overlay is shown only over eligible focused fields on the primary display. Android may disallow overlays or background microphone service starts; repair is available in the app, including an in-app test. |
| Capture | Mono PCM16, typically 16 kHz, with hardware-rate fallbacks, energy-based no-speech detection, outer silence trimming, and a 45 s audio cap. Supports system and phone input selection; explicit Bluetooth routing uses Android 12+ APIs. Route loss, calls, silenced capture and microphone errors end the session without an automatic retry. |
| Providers and languages | Batch file transcription via OpenRouter and direct OpenAI. Supports English and Hindi language hints. Auto uses model detection; direct OpenAI `gpt-transcribe` sends both English and Hindi hints in Hinglish mode. Other batch models use auto-detection for mixed speech. Live streaming, partial transcripts, and on-device STT are deferred. No inference runs without a configured provider and model. |
| Cleanup and context | None, Light, and Medium. App/IME metadata is mapped locally to a general writing style, including chat, email, notes, search, and code. A configured text model gets the transcript, style instruction and up to 50 vocabulary hints. Timeout, malformed output, a changed numeric sequence, or provider errors fall back to the raw transcript. Model editing can still introduce errors; review important text. You can opt into exact whole-utterance snippet expansion separately. |
| Automatic insertion | Android 13+ accessibility input connection, without replacing the keyboard or calling ACTION_SET_TEXT or paste. Atlas checks the original application/window/node, IME connection generation, draft fingerprint and selection, plus a per-dictation session ID. It rejects active keyboard composition, invalid UTF-16 boundaries, and missing or stale editor state. Android provides no success flag for commit, so Atlas checks the resulting text and cursor, never attempts a second commit, and offers manual copy if it cannot confirm. Android 8–12 use manual copy. Some web, rich text, or custom editors may not expose the information required for automatic insertion. |
| Privacy and recovery | History and audio retention default off. A failed retryable clip stays in memory for at most two minutes; the latest result stays for at most three minutes. Opted-in history expires on app use and periodic Android housekeeping; max 1,000 entries and 20 retained WAVs. No clipboard writes without a Copy action. User exclusion list plus Android hints, input types, platform sensitivity, no-personalized-learning flags and known restricted packages are used to block sensitive fields. A third-party app can fail to label a sensitive field; exclude that app in Settings or avoid dictating sensitive content. |
| Metrics | Home reports stop-to-confirmed-insert p50/p95 over up to 100 samples in the current process plus median capture finalization, recognition, cleanup and insertion times. Unconfirmed/manual outcomes are excluded. No user speech, draft text, or credentials are logged. |

Voice commands that edit existing selected text are deferred. The optional snippets insert explicitly configured text; they do not execute commands in an external app.

Cloud requests contain only submitted audio, optional language hints, and the user's selected model. Cleanup is a separate request as disclosed in onboarding. Draft fingerprinting and target classification stay local. The release uses HTTPS and disables redirects and automatic HTTP retries for dictation. No new credentials are embedded in the APK.

## Verification on 15 November 2026

- Debug, instrumentation and release APKs built. The release exercised R8 and resource shrinking; both app APK signatures passed `apksigner verify`.
- APK SHA-256: debug `F68A5BB16C3965A309207174E5F778EF8495333DC7C5D3834904423DCE3F162D`; release `376A4A430E443B9F7701529A65E326CD53100116A5DABA7BA6D4C3444EFD17F4`.
- Debug and release lint: zero errors; one pre-existing launcher artwork warning each.
- 60 JVM tests passed, including Unicode/multiline transport, the current OpenAI multipart contract, cancellation, error redaction, cleanup fallback, no-speech detection, sensitive fields and stale session rejection.
- Three host SQLite migration checks passed. The separate dictation database is version 1; no meeting migration changed.
- 20 AndroidJUnitRunner tests passed on an isolated Android 16 / API 36 emulator, including actual platform microphone start/cancel/release, retained-audio recovery, meeting capture with a duplicate start, notes persistence and Android Room migration tests. Both a direct ADB run and `:app:connectedDebugAndroidTest` passed on the final APK. The connected runner needed an online run to fetch its missing UTP dependencies.
- In the separate editor fixture on Android 16, the bubble appeared for two regular fields while the keyboard remained visible and was absent from password, numeric and payment-hint fields. It stayed above the keyboard after correcting the cutout offset. A bubble tap started a microphone foreground service while the fixture remained the resumed app. Both **Cancel** and switching focus to the password field released capture. These were capture-only checks with network disabled and no inference.
- No real cloud inference, third-party app speech insertion, physical microphone accessory test, or representative latency/CPU/memory profile was executed. The test results above do not establish the cross-app acceptance criteria.

## Qualification on Android 13–16

Use disposable drafts and non-sensitive test speech. On each Android version and device, log the app and keyboard versions, speech model, result, and whether Atlas confirmed the insert.

The test APK also contains a separate editor process with prefilled text, a second text field, a password, a numeric field and a payment hint. To use it after installing the debug app:

```powershell
adb install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am start -n com.meetingatlas.test/com.meetingatlas.fixtures.EditorFixtureActivity
```

1. **WhatsApp:** Start with a draft with words on both sides of the cursor. Tap to record, dictate a short sentence, tap Done, and check that the text appears at the cursor. Repeat with a word selected and verify only that selection changed. Keep typing with the same keyboard.
2. **Chrome:** Test an ordinary HTTPS page with a multiline textarea; hold to record, release to submit, and check existing text and line breaks. Open a password field and a numeric input: no bubble should appear.
3. **Gmail:** Dictate into the message body between existing paragraphs; check paragraph layout and emoji/Unicode. Exercise a short English, Hindi, and mixed utterance with a model that advertises the relevant language coverage.
4. **Cleanup:** Set Light and a chat model. Speak “hey um can you send me the project file no wait the final project file tomorrow morning.” Compare None versus Light and check correction, punctuation, and meaning. Try a failed chat-model request; the raw transcript should still remain available.
5. **Cancel and focus:** Cancel during capture and during cloud processing. Verify no text appears. Change fields, move the cursor, or switch apps while processing; the old result may appear in Atlas for manual recovery but must never insert in the new field. Repeat with duplicate taps and rapid start/cancel cycles.
6. **Network:** Lose connectivity after speaking but before Done. Verify a recoverable error and no insertion. Restore the network, tap Retry, check text in Atlas, and verify there is no automatic insertion after retry. With retention on, try retrying a stored failed clip after process restart.
7. **Sensitive editors:** Test a password, numeric PIN, verification-code hint, payment screen, an incognito text field, and a package you manually exclude. Verify that the bubble stays hidden for the exposed sensitivity signals.
8. **Meetings:** Capture, pause, resume, bookmark, stop and play. Reopen the app and verify persistence, then run existing meeting notes with the configured meeting models. During an active meeting, try Dictation and verify it cannot take microphone ownership.
9. **Recovery:** Disable overlay/accessibility, revoke the microphone, rotate, lock the device, force stop and reopen. Try Bluetooth connect/disconnect and an incoming call during recording. Verify microphone status clears, no stale inserts occur, and Home identifies missing permissions. Repeat on a Xiaomi/Redmi/POCO or Samsung device if targeted.
10. **Performance:** Collect at least 30 short successful inserts per device/provider/network. Record sample count, p50/p95, and stage medians from Home. Compare cleanup off/on and Wi-Fi/cellular. In Android Studio Profiler, check memory during the cap-length capture, CPU during capture and idle overlay, network bytes, and the service/notification disappearing after Done or Cancel.

Before Play submission, complete the Accessibility API declaration, listing disclosure, privacy policy, Data safety form and demonstration video. Review the [Google Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491) and [Android foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Atlas is not registered as a disability accessibility tool. Actual Play review, physical device qualification, and real-provider accuracy/latency remain separate release gates.

## File inventory

Added:

```text
app/schemas/com.meetingatlas.dictation.DictationDatabase/1.json
app/src/androidTest/AndroidManifest.xml
app/src/androidTest/java/com/meetingatlas/dictation/DictationCaptureTest.kt
app/src/androidTest/java/com/meetingatlas/fixtures/EditorFixtureActivity.java
app/src/main/java/com/meetingatlas/dictation/AtlasAccessibilityService.kt
app/src/main/java/com/meetingatlas/dictation/DictationAudioEngine.kt
app/src/main/java/com/meetingatlas/dictation/DictationCoordinator.kt
app/src/main/java/com/meetingatlas/dictation/DictationHistoryRepository.kt
app/src/main/java/com/meetingatlas/dictation/DictationPolicy.kt
app/src/main/java/com/meetingatlas/dictation/DictationRetentionWorker.kt
app/src/main/java/com/meetingatlas/dictation/DictationScreen.kt
app/src/main/java/com/meetingatlas/dictation/DictationService.kt
app/src/main/java/com/meetingatlas/dictation/DictationSettingsRepository.kt
app/src/main/java/com/meetingatlas/dictation/FloatingBubbleController.kt
app/src/main/java/com/meetingatlas/dictation/SpeechToTextEngine.kt
app/src/main/java/com/meetingatlas/dictation/TextInsertionManager.kt
app/src/main/res/drawable/ic_dictation_done.xml
app/src/main/res/drawable/ic_dictation_error.xml
app/src/main/res/drawable/ic_dictation_mic.xml
app/src/main/res/xml/atlas_accessibility.xml
app/src/test/java/com/meetingatlas/dictation/DictationPolicyTest.kt
app/src/test/java/com/meetingatlas/dictation/DictationTransportTest.kt
docs/dictation.md
```

Modified:

```text
README.md
app/proguard-rules.pro
app/src/androidTest/java/com/meetingatlas/recording/RecordingFlowTest.kt
app/src/main/AndroidManifest.xml
app/src/main/java/com/meetingatlas/MainActivity.kt
app/src/main/java/com/meetingatlas/ai/OpenRouterClient.kt
app/src/main/java/com/meetingatlas/ai/OpenRouterSettingsStore.kt
app/src/main/java/com/meetingatlas/notes/NotesScheduler.kt
app/src/main/java/com/meetingatlas/notes/OpenRouterNotesEngine.kt
app/src/main/java/com/meetingatlas/recording/AudioWorkCoordinator.kt
app/src/main/java/com/meetingatlas/recording/MeetingRecordingService.kt
app/src/main/java/com/meetingatlas/ui/MeetingAtlasApp.kt
app/src/main/res/values/strings.xml
app/src/test/java/com/meetingatlas/notes/TranscriptAndViewsTest.kt
```

No files removed. The unrelated change already present in `gradle.properties` is outside this inventory.
