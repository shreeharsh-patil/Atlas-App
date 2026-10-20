# Release preparation

Version 0.5.0 (version code 6) is the design and reliability release. It introduces the
Meeting Atlas design system — Lobster and Lobster Two for display type, Lora for reading
and true italics, Manrope for interface chrome — a hero-led home screen, raised cards and
a brass accent, plus a wide correctness pass over recording, playback, notes and the build.

## Build outputs

```powershell
$env:ANDROID_HOME = 'C:\Users\shree\AppData\Local\Android\Sdk'
./gradlew.bat :app:assembleRelease :app:bundleRelease
```

Outputs:

- `app/build/outputs/apk/release/app-release.apk` — signed for direct install and internal testing.
- `app/build/outputs/bundle/release/app-release.aab` — signed with the local upload key for Play Console internal/closed testing and Play App Signing setup.

SHA-256 checksums for the generated artifacts:

- APK: `1E60A37FA443794D8F1D113979CFDE334692294069BBD012A356F6452A249AAC`
- AAB: `737DF2C92D0B3574F2A22BA8E411725AC20D59BD84F0BECBABA7DF081DCF575E`

APK signature verification passed with `apksigner`; the signing certificate matches the
locally protected upload key (`CN=Meeting Atlas Local Release, OU=Android Upload Key`).

## What changed in 0.5.0

### Design system

- `ui/theme/Type.kt` defines four families: **Lobster** (brand wordmark and screen
  titles), **Lobster Two** (true italic display accents and pull quotes), **Lora**
  (reading face, upright and italic) and **Manrope** (buttons, chips, counters, metadata).
- `ui/theme/Theme.kt` adds a complete `Typography` scale, a corner-radius `Shapes` scale
  and a brand palette (`AtlasColors`) with a brass accent, a hero gradient and a live
  recording colour.
- `ui/AtlasComponents.kt` adds the shared building blocks: `AtlasCard`,
  `AtlasClickableCard`, `AtlasHero`, `AtlasEyebrow`, `AtlasSectionHeader`, `AtlasPill`,
  `AtlasQuote`, `AtlasEmptyState`, `AtlasGeneratedBadge` and `AtlasMetaRow`.
- The home screen is now a hero panel over a deep green wash with the supplied mark, a
  large Lobster headline and a raised card grid. Every hard-coded `sp`, `letterSpacing`
  and `FontWeight` override was removed in favour of the theme.
- The four bundled families are SIL Open Font License 1.1; the licence texts live in
  `third_party_fonts/`.

### Correctness

- **Recording:** the foreground service is promoted before any early return (an app kill
  was possible), the service publishes a terminal state when it is destroyed (the UI no
  longer freezes on a "live" capture that has ended), a failed segment rollover no longer
  marks an intact recording as interrupted, `CancellationException` is never swallowed,
  and `MediaRecorder.prepare/start/stop` moved off the main thread.
- **Playback:** the player is pinned outside the notes list (it used to stop when
  scrolled away), seeks issued before the controller connects are replayed, per-meeting
  state no longer leaks between meetings, `COMMAND_PLAY` is blocked while recording, and
  the final playback position is written synchronously.
- **Notes:** a succeeded job is no longer downgraded to failed, the revision bump is
  atomic, model quotes are compared after whitespace and typographic normalisation (this
  was the most likely cause of "no notes at all"), provider timestamps are clamped rather
  than rejected, an unreadable audio slice is skipped instead of dead-ending the meeting,
  and the audio-retention choice now also applies to interrupted recordings.
- **Data:** the Keystore-backed key can no longer report "saved" while being unreadable,
  the processing and recovery locks are owner-guarded, deleted meetings cannot leave
  orphaned files behind, and the Room schema state is updated with targeted queries.
- **Build:** app-owned R8 rules keep persisted enum names, CI builds and lints the release
  variant and uploads artifacts, the migration checker derives its target version from
  the exported schemas, and a missing release signing key is reported loudly.

## Release checklist

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run `./gradlew.bat :app:assembleRelease :app:bundleRelease` and record the new checksums here.
3. Run `apksigner verify --print-certs` on the APK and `jarsigner -verify` on the AAB.
4. Install the release APK on a device and walk through the recording, playback and notes flows.
5. Update `docs/verification.md` with the checks that were actually executed.
6. Tag the release and attach the APK and AAB to the GitHub release.

R8 code shrinking and resource shrinking are enabled for release. The APK and AAB are
signed with alias `meeting-atlas-upload`. The signing configuration and PKCS12 upload
key are in `.release/`, which Git ignores; the folder has local Windows account, SYSTEM
and Administrators ACLs. Keep a separate secure backup of the keystore and
`signing.properties`. Losing or replacing this upload key complicates future app
updates. Never attach `.release/` to a pull request or share the password files. CI
builds without the private key and cannot produce this signed artifact; it passes
`-PallowUnsignedRelease=true` to acknowledge the unsigned output.

The package name is `com.meetingatlas`. Builds signed with Android's debug key cannot be
upgraded in place by this separately signed release. Play App Signing establishes the Play
install-signing identity after enrollment.

## Google Play status

The app has not been uploaded or submitted to Google Play. This workspace has no Play
Console publishing connection or owner-provided store listing. To publish, the Play
Console owner must create the listing, complete content and Data safety disclosures for
microphone and transcript upload, provide privacy-policy/support information, enroll in
Play App Signing and upload the AAB. The app targets API 36, which meets the current
new-app target requirement ([Google Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-en)). If the developer account is a personal account created after 13 November 2023, Play also requires a closed test with at least 12 continuously opted-in testers for 14 days before production access ([testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-en)).

An emulator recording test passed, but physical-device long-session/battery qualification
and human review of actual model output are outstanding. Do not describe those results as
completed Play production qualification; see [verification](verification.md) and
[performance](performance.md).
