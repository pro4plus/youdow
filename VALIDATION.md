# Validation record

## Completed in the authoring environment

- Inspected the pinned youtubedl-android execution/update/request APIs and the NewPipe request/response and stream APIs against official upstream sources.
- Confirmed HTTP 200 for the pinned youtubedl-android 0.18.1 and NewPipeExtractor v0.26.5 Maven POMs.
- Retrieved the official Gradle 8.11.1 wrapper; pinned the Gradle distribution SHA-256 published by Gradle.
- Compiled `LinkRules.kt` and `LinkRulesTest.kt` using Kotlin 2.1.20 outside Gradle and ran JUnit 4.13.2: **4 tests passed** (share-text parsing, rejecting non-web/credential-bearing URLs, exact YouTube host matching, and access-error classification). This does not compile the Android app.
- Reviewed the manifest, permission gates, scoped storage publication and foreground-service timeout handling against Android documentation.

## Not yet verified

Android compilation, Android Lint, emulator/device execution, extractor site behavior, foreground process termination, stream merging, MediaStore visibility and actual downloads have not been run here. The machine has Java 8 and no Android SDK. A source review is not evidence that the app builds or works on a device.

## Build checks

Run with JDK 17 and SDK 35:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Device acceptance checklist

Use your own media or authorized public samples. Cover API 26/28, 29, 33, 34 and 35; arm64 hardware and x86_64 emulator. Include a device using 16 KB memory pages if targeting it.

1. Share a URL with caption text from a browser; verify thumbnail/title/duration and actual format choices. Repeat with each desired source app because share payloads vary.
2. Download a progressive video; play locally after turning networking off. Confirm Movies/LocalDrop visibility in Gallery and Downloads/LocalDrop visibility in Files.
3. Download separate video/audio streams; confirm both tracks exist and play in the merged result. Test original audio and MP3 conversion.
4. Pause near startup, mid-transfer and while processing. Resume, verify preserved partial data when the source supports ranges, and confirm there is one completed public file.
5. Cancel the active job and a waiting job. Confirm canceled private staging files are removed and later queue entries progress. Repeat rapid Pause/Resume/Cancel taps.
6. Queue three items, background the app, turn the screen off, then inspect notification progress and controls. Deny notification permission and confirm in-app controls still work.
7. Kill the app process during download and during MediaStore publication; reopen and resume. Confirm interrupted rows are paused, incomplete public entries are cleaned on retry, and the final URI plays.
8. Deny/regrant legacy storage permission on API 26–28. Deny notification permission on API 33+. Verify actionable errors without broad media-read permission prompts.
9. Enable Wi-Fi only; start with cellular active, then with Wi-Fi active, then disconnect Wi-Fi mid-transfer. Confirm pause and no cellular media transfer. Reconnect and manually resume.
10. Update the extractor while idle, offline and while downloading; verify serial execution and visible failure/success states.
11. Exercise YouTube fallback with a test build that forces a non-access yt-dlp extraction failure. Verify fresh signed URLs on resume and actual audio/video merging.
12. Test private/login-required/unsupported/DRM/live links, low storage, expired formats and external deletion of a saved file.
13. Copy a link while LocalDrop is foregrounded; verify the offer. Background LocalDrop and verify it does not read the clipboard or start a download.
14. On API 35, use Android's foreground-service timeout testing controls and verify the app stops promptly and leaves work resumable.
