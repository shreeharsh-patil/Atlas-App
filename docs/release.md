# Release preparation

Version 0.4.1 (version code 5) refines the home layout and recording status display, uses a lazy recent-meetings list, and memoizes repeated note grouping and date formatting. Version 0.4.0 introduced the supplied Meeting Atlas mark in the home header, adaptive launcher icon, pre-Android 8 launcher icons and Android monochrome icon. The app's Material accent and system bars use the forest-green palette.

## Build outputs

```powershell
$env:ANDROID_HOME = 'C:\Users\shree\AppData\Local\Android\Sdk'
./gradlew.bat :app:assembleRelease :app:bundleRelease
```

Outputs:

- `app/build/outputs/apk/release/app-release.apk` — signed for direct install and internal testing.
- `app/build/outputs/bundle/release/app-release.aab` — signed with the local upload key for Play Console internal/closed testing and Play App Signing setup.

SHA-256 checksums for the generated artifacts:

- APK: `C6DE2A21AD892AF90EF1AAB2F545D206E00490BE53BCF80096254DD78B389995`
- AAB: `5BFCDB7B1F566073608A0C0E642503C5870528A539DA224EBAA87D36D996324C`

APK signature verification passed with `apksigner`; AAB verification passed with `jarsigner`. The signing certificate matches the locally protected upload key. The 0.4.1 APK installed and launched on the Android 16 / API 36 emulator. The updated home screen and supplied logo were visually inspected: [release home screen](screenshots/v4-ui-release-home.png).

Version 0.4.1 is published on [GitHub Releases](https://github.com/shreeharsh-patil/Atlas-App/releases/tag/v0.4.1). The APK and AAB are attached there.

R8 code shrinking and resource shrinking are enabled for release. The APK and AAB are signed with alias `meeting-atlas-upload`. The signing configuration and PKCS12 upload key are in `.release/`, which Git ignores; the folder has local Windows account, SYSTEM and Administrators ACLs. Keep a separate secure backup of the keystore and `signing.properties`. Losing or replacing this upload key complicates future app updates. Never attach `.release/` to a pull request or share the password files. CI builds without the private key and cannot produce this signed artifact.

The package name is `com.meetingatlas`. The earlier 0.3.0 debug build is signed with Android's debug key and cannot be upgraded in place by this separately signed release. Play App Signing establishes the Play install-signing identity after enrollment.

## Google Play status

The app has not been uploaded or submitted to Google Play. This workspace has no Play Console publishing connection or owner-provided store listing. To publish, the Play Console owner must create the listing, complete content and Data safety disclosures for microphone and transcript upload, provide privacy-policy/support information, enroll in Play App Signing and upload the AAB. The app targets API 36, which meets the current new-app target requirement ([Google Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-en)). If the developer account is a personal account created after 13 November 2023, Play also requires a closed test with at least 12 continuously opted-in testers for 14 days before production access ([testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-en)).

An emulator recording test passed, but physical-device long-session/battery qualification and human review of actual model output are outstanding. Do not describe those results as completed Play production qualification; see [verification](verification.md) and [performance](performance.md).
