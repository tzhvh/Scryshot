# Scryshot

A modernized personal fork of [ScreenshotGo](https://github.com/mozilla-tw/ScreenshotGo). This is an Android app that captures, organizes, and searches screenshots by their text content using on-device OCR.

## About

ScreenshotGo was built by Benjamin Cheng, Roger, Morpheus, Nevin Chen, Teng-pao Yu, weslyhuang, and maliu. The project was officially [archived](https://github.com/mozilla-tw/ScreenshotGo) in 2021.

This fork carries their work forward — updating the toolchain, migrating off deprecated APIs, and keeping the app functional on modern Android (targetSdk 35, Android 14/15). It is **not affiliated with or endorsed by Mozilla**. All credit for the original design and implementation belongs to the Mozilla Taiwan team; this is maintenance and modernization on a personal fork.  

The project was renamed to avoid infringing on Mozilla's trademarks and to distinguish it from the original app.

## Status

The modernization effort is complete: the project builds on Gradle 8.11 / AGP 8.7 / Kotlin 2.1 with all internal Mozilla infrastructure dependencies (telemetry, Adjust, Fabric/Crashlytics, FCM) removed, scoped storage support via MediaStore, and `ActivityResultContracts`-based permissions. The app runs on Android 14/15 with functional parity to the 2018 release.

Work in progress: vector search migration (SAF, zvec, hybrid OCR + image embeddings) is in progress. 

## License

[MPL 2.0](LICENSE) and same as the original project. The MPL 2.0 notice remains in every source file where it appeared originally; modifications carry the same license.
