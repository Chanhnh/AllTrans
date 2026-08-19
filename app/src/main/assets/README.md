# AllTrans

AllTrans is an Xposed/LSPosed module that translates text inside Android apps at runtime.

It works like webpage translation in a browser, but for app UI text. You choose a source language and a target language, enable translation for selected apps, then restart those apps to apply translation.

## Overview

- Package name: `chanhnh.alltrans`
- Version: `2.0.0`
- Min Android SDK: `29`
- Compile/target SDK: `36`
- Translation providers:
  - `Google Translate`
  - `Microsoft Translate`

The app UI includes these translation locales:

- English
- Vietnamese
- Chinese

Default translation settings:
- Translation providers: `Google Translate`
- Translate from: `Auto Detice`
- Translate to: `Vietnamese`

## Main features

- Global and per-app translation settings
- Runtime translation hooks for normal text, hints, notifications, and WebView content
- Optional aggressive text replacement mode for apps that do not translate cleanly
- Translation cache with manual clear and automatic clear when the provider changes
- Per-app override settings

## How to use

1. Install the module on a device with Xposed/LSPosed-compatible environment.
2. Enable the module for the apps you want to translate.
3. Open AllTrans.
4. In `Global`, choose the provider and default languages.
5. In `Apps`, enable translation for the target app.
6. Restart the target app.

If a specific app needs different settings:

1. Open that app from the `Apps` list.
2. Enable `OverRide Global Settings`.
3. Adjust provider, source language, target language, and advanced options for that app only.

## Build

This project uses:

- Gradle Android application module
- Kotlin
- Java 17 toolchain

Typical debug build:

```bash
./gradlew :app:assembleDebug
```

## Known notes

- Many games still will not translate correctly because of how they render text.
- When changing the translation provider, AllTrans marks translation cache for clearing automatically.

## License

GPL-3.0-or-later.
