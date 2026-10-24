# AllTrans

AllTrans is an Xposed/LSPosed module that translates Android app interfaces at runtime. You do not need to modify APKs or copy text into another app — supported text on screen is replaced with a translation in the language you choose.

## Overview

- **Package name:** `chanhnh.alltrans`
- **Version:** `3.0.0` (`versionCode` 300)
- **Minimum Android:** Android 10 (API 29)
- **Compile/target SDK:** 36
- **Platform:** LSPosed 2.1+ or an Xposed environment compatible with the libxposed API
- **UI:** Material 3, light and dark themes
- **App UI locales:** English, Vietnamese, Simplified Chinese, Traditional Chinese (Taiwan)
- **Translation providers:** Google Translate, Microsoft Translate, VietPhrase

Default settings:

- Translation provider: `Google Translate`
- Translate from: `Auto Detect`
- Translate to: `Vietnamese`

## Features

- Real-time in-app translation without modifying APKs
- Translates normal text, text hints (`setHint`), notifications, and `WebView` content
- Extended translation for custom views and content drawn outside the normal `TextView` path
- Global settings plus per-app overrides
- Flexible source/target language selection with automatic language detection
- Translation cache with automatic invalidation when the provider, language, or engine changes
- `VietPhrase` offline Chinese-to-Vietnamese translation using local dictionaries
- `VietPhrase` rule engine for names, pronouns, numbers/dates, chapter labels, and sentence formatting
- Debug mode with `logcat` output

## Requirements

| Component | Requirement |
| --- | --- |
| Device | Android 10 or newer (API 29+) |
| Module | LSPosed 2.1+ or an Xposed environment compatible with the libxposed API |
| System access | A device with LSPosed installed (usually requires root/Magisk, KernelSU, or APatch) |
| Internet | Required for Google Translate and Microsoft Translate; not required for VietPhrase |
| Build | JDK 17, Android SDK 36 |

## Installation

1. Build or download the AllTrans APK.
2. Install the APK on your device.
3. Open LSPosed and enable the **AllTrans** module.
4. Select the apps you want to translate in **Scope**.
5. Reboot the device or force-stop the target apps.
6. Open AllTrans and configure the provider and languages.

> If a target app cannot query providers directly, you can also add **System Framework** to the module scope to use the proxy path.

## Usage

1. Open the **Global** tab and choose a translation service and languages.
2. Turn on the **Master On/Off** switch.
3. Open the **Apps** tab and enable translation for the apps you want.
4. Force-stop and reopen the target app.

For app-specific settings, open the app from the **Apps** tab, enable **OverRide Global Settings**, and adjust the provider, languages, and advanced options.

## Translation providers

| Provider | ID | Internet | Notes |
| --- | --- | --- | --- |
| Google Translate | `g` | Yes | Uses Google language codes |
| Microsoft Translate | `edge` | Yes | Uses the Edge endpoint and Microsoft/Edge native language codes |
| VietPhrase | `vietphrase` | No | Only supports `zh → vi`; uses local dictionaries in `vietphrase/` |

### Microsoft Translate

Microsoft Translate uses the Edge endpoint and Edge-native language codes. If the selected language is not available after switching providers, AllTrans resets it to the default language instead of sending an invalid code.

### VietPhrase

- Only translates from Chinese (`zh`) to Vietnamese (`vi`)
- Uses local dictionaries and works without an Internet connection
- The rule engine handles names, pronouns, numbers/dates, chapter labels, and sentence formatting
- The rule engine and some pronoun data are adapted from duongden's `vietphrase-translator` project (GPL-3.0). See `vietphrase/README.txt`

## Translation cache

- Stores translations by text to avoid repeated provider calls
- Can be cleared from global or per-app settings
- Automatically invalidated when the provider, language, or translation engine changes
- Force-stop the target app after clearing the cache

## Privacy

- AllTrans has no proprietary backend server and does not collect analytics.
- Google Translate and Microsoft Translate send the text to the corresponding service.
- VietPhrase processes everything locally on the device.
- `QUERY_ALL_PACKAGES` is used to show the app list.
- `INTERNET` is used for online translation providers.

## How it works

- Hooks text rendering points such as `TextView.setText`, `setHint`, `StaticLayout.Builder.build()`, layout constructors, framework View methods, and custom View setters.
- Supports notifications and `WebView` content.
- Providers are queried through internal `ContentProvider` endpoints:
  - `content://chanhnh.alltrans.GtransProvider`
  - `content://chanhnh.alltrans.EdgeProvider`
  - `content://chanhnh.alltrans.VietPhraseProvider`
- The selected provider is used explicitly. If it is unavailable or returns an empty result, the original text is kept.
- Provider queries try the proxy path first, then fall back to a direct query.

## Build

This project uses a Gradle Android application module, Kotlin, and Java 17.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APK outputs:

- `app/build/outputs/apk/debug/AllTrans-v3.0.0-debug.apk`
- `app/build/outputs/apk/release/AllTrans-v3.0.0-release.apk`

To sign a release build, configure `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` in `gradle.properties`.

## Troubleshooting

- If an app is not translated, check the module scope, the master switch, and the per-app switch, then force-stop the target app.
- If Google Translate or Microsoft Translate fails, check the Internet connection and try the other provider.
- VietPhrase only supports `zh → vi`.
- Games and apps that draw text directly with canvas may not be hooked.
- Enable **Debug Mode** and inspect `logcat` with the `AllTrans` tag when reporting bugs.

## Credits

- Original project: [akhilkedia/AllTrans](https://github.com/akhilkedia/AllTrans)
- VietPhrase rules and data adapted from [duongden/script-vietphrase-translator](https://github.com/duongden/script-vietphrase-translator)

## License

GPL-3.0-or-later. See the project's `LICENSE.GPL` for details.
