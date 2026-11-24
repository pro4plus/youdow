# Validation record

## Completed in the authoring environment

- Inspected the pinned youtubedl-android execution/update/request APIs and the NewPipe request/response and stream APIs against official upstream sources.
- Confirmed HTTP 200 for the pinned youtubedl-android 0.18.1 and NewPipeExtractor v0.26.5 Maven POMs.
- Retrieved the official Gradle 8.11.1 wrapper; pinned the Gradle distribution SHA-256 published by Gradle.
- Compiled `LinkRules.kt` and `LinkRulesTest.kt` using Kotlin 2.1.20 outside Gradle and ran JUnit 4.13.2: **4 tests passed** (share-text parsing, rejecting non-web/credential-bearing URLs, exact YouTube host matching, and access-error classification). This does not compile the Android app.
- Reviewed the manifest, permission gates, scoped storage publication and foreground-service timeout handling against Android documentation.

## v1.9 changes (2026-11-24)

- Root cause of the CNN "No downloadable formats" report: the latest yt-dlp (2026.11.23) extracts the CNN Arabic article fine (one mp4 format), but the phone's bundled extractor was older and the app never retried with a fresh one — it only showed the error. `Extractor.inspect()` now updates the extractor and retries once automatically when no formats are found (`LinkRules.needsExtractorRefresh`).
- `FormatRules` (new, unit-tested): HLS/DASH-only formats with a `manifest_url` or `fragments` list count as downloadable; the old filter required a direct https `url` and dropped them.
- Error sheet offers a blind "تحميل مباشر بأفضل جودة" download (`-f best`, no preview) when inspection fails.
- In-app browser detection now covers news sites (CNN, Al Jazeera, BBC, France 24, Sky News Arabia, Al Arabiya, Al Hadath, Euronews, RT) in addition to the previous platforms.
- Release signing: permanent keystore (alias `localdrop`, RSA 2048, valid to 2051-11-18, SHA-256 `96:E2:B7:27:...:46:E7`); encrypted `android/release.keystore.enc` in the repo, decrypted in CI from the `KEYSTORE_PASSPHRASE` secret; `signingConfigs.release` in `app/build.gradle.kts` reads `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` from the environment. Plaintext keystore is git-ignored and kept only in the owner's backup.
- Unit tests: 32 total (was 26), all passing locally.

## v1.8 changes (2026-11-24)

- `LinkRules` now recognises 30+ platforms, normalises short links, strips tracking parameters, detects playlists and direct media files, and names the platform in error messages. New unit tests cover platform names, suffix spoofing, normalisation, bare domains, batch extraction, playlist/direct detection and platform-aware errors.
- `Extractor` short-circuits direct media links, unwraps playlist JSON to its first video, synthesises a format for single-URL sources, and retries fragments up to 10 times.
- Quality sheet offers 2160p/1440p/1080p/720p/480p + MP3; settings offer a 1440p cap.
- Batch paste (one link per line) queues each link with the default quality.
- History: delete single finished/failed/cancelled entries, clear all finished entries, share a completed file.
- In-app browser video detection covers X, Reddit, Pinterest, Twitch, Dailymotion, SoundCloud, Threads, Telegram, Bilibili and Rumble in addition to the previous platforms.
- NewPipeExtractor upgraded v0.26.5 → v0.27.1 (JitPack POM verified 200). youtubedl-android stays at 0.18.1, the latest release; yt-dlp itself updates nightly on-device to the latest release.
- CI now runs `:app:testDebugUnitTest` before building the debug APK.

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
