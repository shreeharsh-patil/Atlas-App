# Atlas dictation UI: Android visual QA

The redesign lives in the Atlas Android app. It uses Atlas branding, local storage and the existing speech pipeline. The original meeting screens remain in the drawer under **Meetings** and **Meeting library**.

## Reference inventory

All reference assets are for review, not app resources. Reference images used in the comparison are public marketing exports; Wispr’s source code, logo, photography, illustrations and fonts are not included in Atlas.

| Screen or state | Source inspected | What the source establishes |
| --- | --- | --- |
| Language selection | [Official Play listing](https://play.google.com/store/apps/details?id=com.wispr.flowapp), fourth phone composition | White interior, early thin progress track, editorial serif heading, outlined search, separated choices, checkmarks, cream selected row, lavender bottom CTA. The export exaggerates the selected row and has no device-density metadata. |
| Idle bubble | [Official Play listing](https://play.google.com/store/apps/details?id=com.wispr.flowapp), third and sixth images; [Android marketing page](https://wisprflow.ai/android) video | Compact lavender control, dark vertical bars, on the trailing side above the current input. Marketing uses a rounded square; Atlas uses a circle with a larger accessible hit area. |
| Recording bubble | [Official Play listing](https://play.google.com/store/apps/details?id=com.wispr.flowapp), second, fifth and seventh images; [Android marketing video](https://wisprflow.ai/android) | Left lavender cancel, center lavender pill, right cream confirm, all with dark icons, narrow spaces and a thin edge. Center width is approximately 1.8 times the button diameter in the fifth Play image. |
| Bubble states in use | [First dictation](https://docs.wisprflow.ai/articles/5906198129-starting-your-first-dictation-on-android) and [size and shrink](https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android) | Tap/hold, cancel/submit, no replacement keyboard, four size stops, shrink after five seconds. Processing motion and error visuals in Atlas are independent implementations. |
| Intro, setup, test, permissions, privacy and completion | [Official Android setup](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings) | Identifies actual steps and dependencies. The public reference does not give matched raw screen captures for all nine screens. Atlas displays actual permission state and calls Android's system panels. |
| Home, history, and drawer | [Official Android navigation](https://docs.wisprflow.ai/articles/5096240724-navigating-the-wispr-flow-app-desktop-ios-and-android) | Establishes tabs, drawer actions, stats, repair cards, grouping by date and per-row menu. Matched raw imagery and dimensions were unavailable. |
| Dictionary and snippets | [Dictionary help](https://docs.wisprflow.ai/articles/4052411709-Teach-Flow-your-words-with-the-dictionary), [Snippets editor help](https://docs.wisprflow.ai/articles/4816874402-troubleshooting-guide-for-snippets-pasting-only-part-of-the-text-or-code) | Documents search, add/edit/delete, trigger and expansion fields. Layout details are an Atlas interpretation of the verified language/overlay styling. |
| Settings, Help, report and Account | [Official Android navigation](https://docs.wisprflow.ai/articles/5096240724-navigating-the-wispr-flow-app-desktop-ios-and-android), [bubble preferences](https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android) | Row groups and destinations are documented, but a matched capture is unavailable. Atlas has a local profile and provider key controls; report and feedback use the user's Android share destination. Atlas has no Wispr account or billing. |
| Dark theme | Play artwork depicts a dark host editor beneath the overlay; no equivalent full official dark app capture found | Atlas's dark theme is a separate extension, not a reproduction claim. |

Exact dominant color samples in the public 1000 × 1600 Play exports: lavender `#F0D7FF`, cream `#FFFFEB`, black ink `#1A1A1A`, selection `#E4E4D0`, outlined rows `#DDDBDE`, and white `#FFFFFF`. The signed-in Atlas paper color and dark variants are chosen for this app. Atlas uses the already bundled OFL Lora and Manrope as stand-ins. The overlay draws an original seven-bar graphic from real microphone levels; state animation and the processing spinner honor Android’s animator setting.

## Visual artifacts

- [50 Atlas captures](screenshots/atlas-flow/) are real emulator screens from the app and external editor fixture.
- [Visual overview](screenshots/atlas-ui-overview.png) is a contact sheet of those captures.
- [Official reference alongside Atlas](screenshots/atlas-ui-comparison.png) compares publicly visible language and bubble geometry. Normalizing the crop makes proportions easier to inspect; it does not make the marketing exports same-device goldens.

The base target is an Android 16 emulator at 1080 × 2400, 420 dpi, font scale 1.0. Capture with matching navigation, theme, permissions, provider settings and local history for screenshot regressions. The comparison tool masks the system status area and reports changed pixels for Atlas against Atlas, not similarity to Wispr Flow.

```powershell
# In an isolated Android emulator with the debug app and test APK installed.
python tools/atlas_visual_qa.py suite artifacts/ui-candidate --serial emulator-5562
python tools/atlas_visual_qa.py compare docs/screenshots/atlas-flow artifacts/ui-candidate --allow-subset --diff-dir artifacts/ui-diff

# Capture the real overlay in the test APK's independent editor.
# Use emulator-only credentials and no network; this check cancels before submitting.
python tools/atlas_visual_qa.py overlay artifacts/ui-overlay --serial emulator-5562
```

Use `snap` for other states. The suite visits real screens and can walk through actual system consent for a fresh isolated emulator. It does not clear app data, insert records or send any dictation request. On launch, Atlas reflects real readiness even if onboarding was skipped. API 33+ supports the original auto-insertion method; the manual-copy fallback remains.

Atlas exposes one recognition preference (Auto, English, Hindi, or Hinglish) through its existing providers. The public Play language screenshot depicts Wispr’s multi-select list. Atlas also keeps a 48 dp or larger touch region for its compact bubble even when the visible circle shrinks. Fonts, those layout choices, and the localized system keyboard and permissions are material differences from the published artwork.

## Verification

- Debug, instrumentation, and signed R8 release APKs built; both app APK signatures verified. Debug and release lint reported zero errors and the pre-existing launcher artwork warning. All 62 JVM tests, 20 connected instrumentation tests, and three SQLite checks passed.
- Captured portrait light and dark modes on Android 16, plus a 360 dp compact viewport at 130% font scale, the system's three-button configuration, and landscape. The startup frames were taken from an emulator screen recording; other captures are direct screenshots.
- Repeated five matched screen states with Android status icons masked: Home, dictionary list, snippets list, Settings, and Languages. All five were within tolerance, with no changed pixels above the comparison threshold. This result is an Atlas regression check.
- In the separate editor, tap and hold both started the real foreground microphone. Cancel and hold release cleared it. The regular keyboard stayed present. The overlay snapped to the screen edge and retained its position after reopening the editor; it hid in password, numeric, and payment fields. Added and opened stored dictionary and snippet entries through the UI. Dictation checks were local and did not use a cloud request.
- No live Wispr account, same-device authenticated Wispr capture, physical device or live cloud transcription validation was used for this visual pass.

## Source files

- `app/src/main/java/com/meetingatlas/dictation/FlowDesign.kt`: palette, typography, selection, fields, rows, buttons and dialogs.
- `app/src/main/java/com/meetingatlas/dictation/DictationScreen.kt`: Home, date-grouped history navigation, drawer, notices, local account, report/feedback and permissions bridge.
- `app/src/main/java/com/meetingatlas/dictation/FlowOnboarding.kt`: intro, language selection, native permission setup, privacy, practice and completion.
- `app/src/main/java/com/meetingatlas/dictation/FlowSettings.kt`: settings groups, live bubble preview and persistence, provider credentials and diagnostics.
- `app/src/main/java/com/meetingatlas/dictation/FlowLibrary.kt`: history, dictionary, snippets and their editors.
- `app/src/main/java/com/meetingatlas/dictation/FloatingBubbleController.kt`: system overlay geometry, rendering, state animation, amplitude, gestures, and idle shrink.
- `app/src/main/java/com/meetingatlas/dictation/DictationSettingsRepository.kt` and `app/src/main/java/com/meetingatlas/ui/MeetingAtlasApp.kt`: additive preferences and integration with existing app destinations.
- `app/src/test/java/com/meetingatlas/dictation/FlowHistoryTest.kt`, `tools/atlas_visual_qa.py` and `docs/screenshots/atlas-flow/`: date and visual regression tests and real captures.
- `README.md`, `docs/dictation.md`, this review, and the two comparison/overview PNGs: navigation and visual review artifacts.

The three old `ic_dictation_*.xml` vector resources were replaced by native drawing. No database schema, speech transport, recording service or meeting files changed in this visual pass.
