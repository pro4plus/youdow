# Third-party notices

LocalDrop source is provided under GPL-3.0-or-later to accommodate its NewPipeExtractor integration. See LICENSE. This notice is not a replacement for the complete notices and corresponding-source obligations of distributed dependencies.

- NewPipeExtractor: GPL-3.0-or-later — https://github.com/TeamNewPipe/NewPipeExtractor
- youtubedl-android, Python, yt-dlp, QuickJS and bundled native dependencies: inspect the pinned upstream distribution and included licenses — https://github.com/yausername/youtubedl-android
- FFmpeg: licensing depends on the bundled build configuration and enabled components — https://ffmpeg.org/legal.html
- AndroidX, Kotlin, OkHttp, Coil and Gradle: retain their upstream license notices when redistributing.

Before shipping binary builds, collect the exact transitive/native license texts and make corresponding source available as required by the applicable licenses. Do not assume that every native component uses the wrapper library's license.
