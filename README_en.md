# AllTrans

[Tiếng Việt](README.md)

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
- Translation cache to reduce repeated requests and speed up rendering
- Automatic cache invalidation when the provider, language, or translation engine changes
- `VietPhrase` offline Chinese-to-Vietnamese translation using local dictionaries
- `VietPhrase` rule engine for names, pronouns, numbers/dates, chapter labels, and sentence formatting
- Debug mode with `logcat` output for troubleshooting

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

### Global settings

1. Open the **Global** tab.
2. Choose a **Translation Service**.
3. Choose the **source language** and **target language**.
4. Turn on the **Master On/Off** switch.
5. Open the **Apps** tab and enable translation for the apps you want.
6. Force-stop and reopen the target app.

### Per-app settings

1. Open the target app from the **Apps** tab.
2. Enable **Translate This App**.
3. Enable **OverRide Global Settings** if you want app-specific configuration.
4. Choose the provider, languages, and advanced options.
5. Force-stop the target app to apply the changes.

### Main settings

| Setting | Description |
| --- | --- |
| Master On/Off | Enables or disables the whole module |
| Translation Service | Selects Google Translate, Microsoft Translate, or VietPhrase |
| Translate From Language | The app's current language; can be auto-detected |
| Translate To Language | The language you want the app translated into |
| Translate Normal Text | Translates text set with `setText` |
| Translate Text Hints | Translates text set with `setHint` |
| Extended View Translation | Adds hooks for custom views, layout constructors, and framework View methods |
| Translate Text in WebView | Translates content inside `WebView` |
| Translate Notifications | Translates notification text |
| Use Caching | Stores translations for reuse |
| Delay | Wait time before replacing text with the translation |
| Delay WebView | Wait time before starting WebView translation |
| Scroll Long Translated Text | Scrolls automatically when a translation is too long |
| Debug Mode | Writes detailed logs to `logcat` |

## Translation providers

| Provider | ID | Internet | Notes |
| --- | --- | --- | --- |
| Google Translate | `g` | Yes | Uses Google language codes |
| Microsoft Translate | `edge` | Yes | Uses the Edge endpoint and Microsoft/Edge native language codes |
| VietPhrase | `vietphrase` | No | Only supports `zh → vi`; uses local dictionaries in `assets/vietphrase` |

### Google Translate

Suitable for general-purpose translation with a broad language list. Requires an Internet connection.

### Microsoft Translate

Uses the Edge endpoint and Edge-native language codes. Switching between Google Translate and Microsoft Translate updates the language list accordingly. If the currently selected language is not available in the new list, AllTrans resets it to the default language instead of sending an invalid code.

### VietPhrase

- Only translates from Chinese (`zh`) to Vietnamese (`vi`)
- Uses local dictionaries and works without an Internet connection
- The rule engine handles names, pronouns, numbers/dates, chapter labels, and sentence formatting
- Useful for fast, stable, offline translation
- The rule engine and some pronoun data are adapted from duongden's `vietphrase-translator` project (GPL-3.0)

## Translation cache

- The cache stores translations by text to avoid repeated provider calls
- You can clear it manually from global settings or per-app settings
- AllTrans automatically marks the cache for clearing when the provider or language changes
- The cache is also refreshed when the translation engine version changes
- After clearing the cache, force-stop the target app to apply the change

## Languages

The AllTrans UI is available in four locales:

- English
- Vietnamese
- Simplified Chinese
- Traditional Chinese (Taiwan)

The translation language list depends on the selected provider. VietPhrase only supports `zh → vi`.

## Privacy

- AllTrans has no proprietary backend server and does not collect analytics.
- When using Google Translate or Microsoft Translate, the text to be translated is sent to the corresponding service.
- VietPhrase processes everything locally on the device.
- The `QUERY_ALL_PACKAGES` permission is used to show the app list for selection.
- The `INTERNET` permission is used for online translation providers.

## How it works

- Hooks text rendering points such as `TextView.setText`, `setHint`, `StaticLayout.Builder.build()`, layout constructors, framework View methods, and custom View setters.
- Adds support for notifications and `WebView` content.
- Each provider is queried through an internal `ContentProvider`:
  - `content://chanhnh.alltrans.GtransProvider`
  - `content://chanhnh.alltrans.EdgeProvider`
  - `content://chanhnh.alltrans.VietPhraseProvider`
- The selected provider is used explicitly. If the provider is unavailable or returns an empty result, AllTrans keeps the original text.
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

To sign a release build, configure these properties in `gradle.properties`:

```properties
RELEASE_STORE_FILE=/path/to/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## Project structure

| Component | Purpose |
| --- | --- |
| `Alltrans.kt` | Module entry point and SettingsProvider proxy hook |
| `AttachBaseContextHookHandler.kt` | Reads preferences, manages cache, and applies hooks |
| `GetTranslateToken.kt` | Dispatches providers and handles cache/fallback |
| `EdgeProvider.kt` | Microsoft Translate |
| `GtransProvider.kt` | Google Translate |
| `VietPhraseProvider.kt` | ContentProvider for VietPhrase |
| `VietPhraseTranslator.kt` | Loads dictionaries and the VietPhrase engine |
| `VietPhraseTranslationEngine.kt` | Translation pipeline and rule engine |
| `PreferenceList.kt` | Reads global and per-app preferences |
| `GlobalPreferencesFragment.kt` | Global settings UI |
| `LocalPreferenceFragment.kt` | Per-app settings UI |
| `app/src/main/assets/vietphrase/` | VietPhrase dictionaries and data |

## Troubleshooting

**The app is not being translated**

- Check that the AllTrans module is enabled in LSPosed.
- Check that the target app is included in the module scope.
- Check that **Master On/Off** and **Translate This App** are enabled.
- Force-stop and reopen the target app.
- Enable **Debug Mode** and inspect `logcat`.

**Google Translate or Microsoft Translate is not working**

- Check the Internet connection.
- Try switching between Google Translate and Microsoft Translate.
- If you use a proxy, check the system proxy configuration.

**VietPhrase is not translating**

- VietPhrase only supports `zh → vi`.
- Check that the dictionaries in `assets/vietphrase` are present.

**Some content is still not translated**

- Games or apps that draw text directly with canvas often cannot be hooked.
- Try enabling **Extended View Translation**.
- Try increasing **Delay** or **Delay WebView**.

**The target app crashes after translation**

- Disable **Scroll Long Translated Text**.
- Disable **Translate Notifications** or **Translate Text in WebView** if not needed.
- Clear the translation cache and force-stop the target app.

## Contributing

- Fork the repository, create a branch, and open a pull request.
- When reporting a bug, include:
  - Android version
  - LSPosed version
  - Target app name and version
  - `logcat` output with the `AllTrans` tag
  - Steps to reproduce

## Credits

- Original project: [akhilkedia/AllTrans](https://github.com/akhilkedia/AllTrans)
- VietPhrase rules and data adapted from [duongden/script-vietphrase-translator](https://github.com/duongden/script-vietphrase-translator)

## License

GPL-3.0-or-later. See `LICENSE.GPL` for details.
