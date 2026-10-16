# Release preparation

Version 0.4.0 (version code 4) uses the supplied Meeting Atlas mark in the home header, adaptive launcher icon, pre-Android 8 launcher icons and Android monochrome icon. The app's Material accent and system bars use the forest-green palette.

## Build outputs

```powershell
$env:ANDROID_HOME = 'C:\Users\shree\AppData\Local\Android\Sdk'
./gradlew.bat :app:assembleRelease :app:bundleRelease
```

Outputs:

- `app/build/outputs/apk/release/app-release.apk` — signed for direct install and internal testing.
- `app/build/outputs/bundle/release/app-release.aab` — signed with the local upload key for Play Console internal/closed testing and Play App Signing setup.

SHA-256 checksums for the generated artifacts:

- APK: `3A5085DDD4AB8CEC01AA69258C34275B4843A7E142003107C8615970CE952D20`
- AAB: `2C8F6594737E6031872D58057DA6B270236F66B4300A1089EC289F1A208C0896`

APK signature verification passed with `apksigner`; the AAB signature verification passed with `jarsigner`. The signing certificate is self-signed as expected for a locally generated upload key. The release APK installed and launched on the Android 16 / API 36 emulator. The home header and supplied logo were visually inspected: [release home screen](screenshots/v4-home.png).

R8 code shrinking and resource shrinking are enabled for release. The APK and AAB are signed with alias `meeting-atlas-upload`. The signing configuration and PKCS12 upload key are in `.release/`, which Git ignores; the folder has local Windows account, SYSTEM and Administrators ACLs. Keep a separate secure backup of the keystore and `signing.properties`. Losing or replacing this upload key complicates future app updates. Never attach `.release/` to a pull request or share the password files. CI builds without the private key and cannot produce this signed artifact.

The package name is `com.meetingatlas`. The earlier 0.3.0 debug build is signed with Android's debug key and cannot be upgraded in place by this separately signed release. Play App Signing establishes the Play install-signing identity after enrollment.

## Google Play status

The release artifacts have not been uploaded or submitted. This workspace has no Play Console publishing connection or owner-provided store listing. To publish, the Play Console owner must create the listing, complete content and Data safety disclosures for microphone and transcript upload, provide privacy-policy/support information, enroll in Play App Signing and upload the AAB. The app targets API 36, which meets the current new-app target requirement ([Google Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-en)). If the developer account is a personal account created after 13 November 2023, Play also requires a closed test with at least 12 continuously opted-in testers for 14 days before production access ([testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-en)).

An emulator recording test passed, but physical-device long-session/battery qualification and human review of actual model output are outstanding. Do not describe those results as completed Play production qualification; see [verification](verification.md) and [performance](performance.md).
