# LocalDrop

Native Android video downloader: Kotlin, Jetpack Compose, Android 8.0+ (min SDK 26), compile/target SDK 35. All extraction, downloads, merging and MP3 conversion happen on the phone. There is no application backend, analytics SDK, advertising, account system or cloud storage.

**Validation status:** Source implementation supplied. The Android app has not been compiled or exercised on a device in the authoring environment, which has Java 8 and no Android SDK. See `VALIDATION.md` for checks and remaining device tests. Do not treat this as a tested release APK.

## Build step by step

1. Install Android Studio with support for Android Gradle Plugin 8.9.2. In SDK Manager install **Android SDK Platform 35**, **Build Tools 35.0.0**, and **Platform Tools**. Install an emulator image if you do not have a device.
2. Open this `LocalDrop` directory (the one containing `settings.gradle.kts`). Set **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** to **JDK 17**. A newer compatible bundled JDK can also run Gradle, but 17 is the documented baseline.
3. Allow Gradle sync to download dependencies from Google Maven, Maven Central, JitPack, and the Gradle distribution service. No API keys or server configuration are required. The included official Gradle wrapper pins 8.11.1 and verifies the distribution SHA-256.
4. Android Studio normally writes `local.properties` with `sdk.dir`. For command-line builds, configure `ANDROID_HOME` or add your local SDK path there. Do not commit this machine-specific file.
5. On Windows, in this directory:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

   On macOS/Linux:

   ```sh
   chmod +x gradlew
   ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

6. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Enable USB debugging, connect the phone, and run `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or use Android Studio's Run button.
7. Open LocalDrop, choose **Settings → Update extractor**, then share a public, downloadable video from a browser or another app. Test with media you own or have permission to download. Some sources block automated access regardless of extractor version.
8. For release distribution, configure your own signing key using Android Studio's Generate Signed Bundle / APK flow. Do not ship the debug key. Review dependency licensing and complete the device tests before distributing.

## Project layout

```text
LocalDrop/
├── settings.gradle.kts                 Repositories and app module
├── build.gradle.kts                    AGP and Kotlin/Compose plugin versions
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/                     Official wrapper and pinned distribution
├── README.md / VALIDATION.md / NOTICE.md / LICENSE
└── app/
    ├── build.gradle.kts                SDK levels and all dependencies
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     Share target, permissions, dataSync service
        │   ├── res/drawable/ic_download.xml
        │   └── java/app/localdrop/
        │       ├── MainActivity.kt     Share/clipboard intake, Compose sheets/screens/player
        │       ├── Extractor.kt        yt-dlp, NewPipe adapter, update/merge/MP3 commands
        │       ├── DownloadService.kt  Serial queue, notification controls, recovery
        │       ├── MediaPublisher.kt   MediaStore publication and legacy media scan
        │       ├── Store.kt            Atomic local history/queue and preferences
        │       └── LinkRules.kt        URL handling and user-facing errors
        └── test/java/app/localdrop/LinkRulesTest.kt
```

## Behavior

- **Share sheet:** exported `ACTION_SEND`, `text/plain` activity accepts links embedded in captions. The system/source app determines whether its share action supplies a URL; sharing a file or an internal app-only identifier is not supported. An incoming link opens a Compose modal bottom sheet.
- **Formats:** yt-dlp supplies title, thumbnail, duration and available streams. Separate video/audio streams are merged with the bundled FFmpeg; original audio and MP3 are offered where an audio stream exists. Settings' quality cap determines the recommended/default choice, and every extracted choice remains selectable. Merged outputs use MKV to accommodate differing codecs without transcoding video.
- **On-device extraction:** `youtubedl-android:0.18.1` bundles its own Python/yt-dlp/QuickJS runtime. **It does not use Chaquopy.** Adding Chaquopy would duplicate Python rather than integrate this library. The included FFmpeg companion supplies the native processor.
- **YouTube fallback:** if normal extraction fails for a genuine YouTube host, NewPipeExtractor supplies progressive HTTPS stream metadata. It is converted to a yt-dlp info JSON file; yt-dlp's generic transfer/FFmpeg pipeline then downloads and merges it without rerunning its YouTube extractor. Signed stream URLs refresh on each attempt. Known DRM/private/login failures do not trigger a bypass attempt. NewPipe needs an app dependency upgrade when its own extractor changes; the update button updates **yt-dlp only**.
- **Queue/history:** a serial foreground-service queue, atomic JSON persistence, and app-private partial files. Interrupted RUNNING/SAVING/QUEUED entries reopen as PAUSED. User-initiated Resume restarts the service. There is no boot receiver or hidden automatic restart.
- **Pause/resume/cancel:** pause stops the extractor process and preserves partial files; resume repeats the same format selection with `--continue`. Cancel deletes that job's private staging files after process termination. Resumption depends on range/fragment support at the source and may restart a transfer. Publication is a short, non-interruptible Saving phase; its controls are disabled. The paused notification provides Resume/Cancel; all queue items also have controls inside the app.
- **MediaStore:** first finish and merge in private storage; then publish with `IS_PENDING` on API 29+. Movies uses `MediaStore.Video`; Downloads uses `MediaStore.Downloads`. A journal allows retry to remove a stranded publication. API 26–28 writes a public file under runtime storage permission and invokes MediaScanner. Audio always uses Downloads. Video files in Movies are discoverable by Gallery; Downloads visibility and codec playback vary by Gallery app.
- **Clipboard:** checks plain text only while the app has foreground focus and offers a Check link button. It does not monitor the clipboard in the background or automatically download. Arbitrary HTTP(S) candidates must be checked by the extractor before support can be known.
- **Player:** Media3 plays saved local URIs, pauses when backgrounded and releases resources when closed. Device codec support determines playback; the download is not re-encoded for compatibility.
- **Wi-Fi only:** checks the active network before starting and during transfers, and binds yt-dlp to the Wi-Fi IPv4 address to avoid transfer fallback to mobile data. Loss pauses the job; reconnect and tap Resume. IPv6-only Wi-Fi is currently unsupported with this setting. Preview thumbnails, metadata inspection, NewPipe metadata and manual extractor updates can still use the active connection; this setting governs media transfers.
- **Update:** downloads the extractor from upstream over HTTPS, on-device. Updates are serialized with extraction and downloads. An update or preview requested during a download waits for it to finish. There is no application backend, but source sites/CDNs and upstream update hosting still require internet access.

## Android permissions and lifecycle

| Android version | Handling |
| --- | --- |
| 8–9 (26–28) | Runtime `WRITE_EXTERNAL_STORAGE` for public folder writes; media scanner registers output. |
| 10–12 (29–32) | Scoped MediaStore writes, no broad storage permission. |
| 13+ (33+) | Requests `POST_NOTIFICATIONS`; denial does not prevent an otherwise valid foreground download. Use in-app controls if the notification drawer hides it. |
| 14+ (34+) | Manifest declares `dataSync` service type and `FOREGROUND_SERVICE_DATA_SYNC`; service starts from visible UI or notification action. |
| 15 (35) | Implements the foreground-service timeout callback and leaves interrupted work resumable; no boot-started service. |

No `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_VIDEO`, accessibility service, VPN, cookie import or account credentials are required. App-owned output URIs remain readable without requesting permission to the entire media library. A bounded partial wake lock covers an active transfer.

## Limits and maintenance

- Extractor site support changes. No guarantee is made for every URL on YouTube, TikTok, Facebook, Instagram, Snapchat or any other service. Private, authenticated, DRM-protected and live streams are rejected or fail with a clear message; no DRM decryption is attempted. NewPipe fallback deliberately supports only progressive streams.
- Native FFmpeg/Python make the APK large. Four ABIs are configured for development; production can use ABI splits. Verify native-library 16 KB page-size compatibility on applicable devices before release.
- Transfers plus merged output plus the public copy can require several times the final file size. Cancel removes private partial data. Failed and paused jobs retain it for retry. Uninstall removes private history/partials, but published files remain.
- Source resolution/format IDs can change between inspection and download. If the selected format disappears, inspect the link again and choose an available format.
- History is local only and does not import existing Gallery items. Removing or moving a file externally leaves a history entry; the player reports that it cannot open the file.
- The queue's small atomic JSON store is suitable for this project. A larger production library would benefit from Room, pagination and more extensive migration/error recovery.

## Official references

- [youtubedl-android documentation and dependencies](https://github.com/yausername/youtubedl-android)
- [Pinned extractor wrapper API](https://github.com/yausername/youtubedl-android/blob/0.18.1/library/src/main/java/com/yausername/youtubedl_android/YoutubeDL.kt)
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android shared media storage](https://developer.android.com/training/data-storage/shared/media)
- [Android clipboard restrictions](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data)
