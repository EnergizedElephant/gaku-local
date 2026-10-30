# Contributing

Thanks for wanting to improve Gaku. This page covers building it, testing it, how the code is laid out, and how changes are made here.

Most of this fork was written by [Claude Code](https://claude.com/claude-code), Anthropic's AI coding assistant: its commits carry a `Co-Authored-By` line. Contributions are welcome however you write them, with AI help or without, as long as you've tried them on a device.

## Building

Use JDK 17 or 21: newer JDKs can fail with "Unsupported class file major version". You also need the Android SDK with platform 34. Point the build at the SDK with `sdk.dir` in `local.properties`, which Android Studio writes, or with `ANDROID_HOME`.

```sh
tools/fetch_manga_ocr.sh                # the manga-ocr model, ~117 MB, pinned and SHA-256-checked
bash ./gradlew assembleOfflineDebug     # gradlew isn't marked executable
adb install -r app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

- There are two builds: `offline`, with no network permission, and `lan`, which adds the Ollama features (`assembleLanDebug`, `app/build/outputs/apk/lan/debug/app-lan-debug.apk`). They share an application ID, so either installs over the other.
- Without the model, the app still builds and runs, but reads with ML Kit only, and *Read with manga-ocr* says the model is missing.
- Debug builds include native libraries for arm64-v8a, for devices, and x86_64, for the emulator. Release builds have arm64-v8a only: add `-Px86_64` to the Gradle command to try one on the emulator. 32-bit devices aren't supported.

## Tests

```sh
bash ./gradlew testOfflineDebugUnitTest --tests 'ca.fuwafuwa.gaku.Ocr.*' --tests 'ca.fuwafuwa.gaku.Llm.*' --tests 'ca.fuwafuwa.gaku.Anki.*'
```

This runs the tests for reading order and furigana, the Ollama client and Anki cards. `--tests` filters only apply to the task just before them, so run each flavour's test task on its own. `GenerateDictionary` isn't a real test: it builds the dictionary database from a JMdict XML file at a hard-coded Windows path, so it fails elsewhere.

The unit tests don't cover the overlay windows, screen capture or the start screen, so try those changes on a phone or the emulator.

## Release builds

`bash ./gradlew assembleOfflineRelease assembleLanRelease` builds minified, unsigned APKs in `app/build/outputs/apk/<flavour>/release/`.

- R8's keep rules for ORMLite and ONNX Runtime are in `app/proguard-rules.pro`. R8 mistakes only show up at runtime, so try a release build on a device after changing dependencies.
- Sign with your own key: `zipalign -p 4` the APK, then `apksigner sign --ks <your key>`.

For maintainers publishing a release:

- Raise `version` in `app/build.gradle`. `versionCode` follows from it.
- Name the signed APKs `gaku-offline-<version>.apk` and `gaku-lan-<version>.apk`, and attach both to a GitHub release with a few lines on what changed.
- Keep the release key, and its password, backed up outside the repo. Android only installs an update signed with the same key, so if the key is lost, everyone has to uninstall and reinstall.

## How the code is laid out

Everything is under `app/src/main/java/ca/fuwafuwa/gaku/`:

- `MainActivity.kt` and `MainStartFragment.kt`: the start screen, the permission prompts, and the Ollama and AnkiDroid settings.
- `MainService.java`: the foreground service that holds the screen capture, the notification and show/hide. `ShowHideTileService.kt` is the Quick Settings tile.
- `Windows/`: the overlay windows. `Window.java` is their base and `WindowCoordinator.kt` creates them. `CaptureWindow.kt` is the box, `InformationWindow.java` the results, and `EditWindow.kt`, `KanjiChoiceWindow.kt`, `InstantKanjiWindow.kt`, `InstantInfoWindow.kt` and `LlmChatWindow.kt` the rest.
- `Ocr/`: `OcrRunnable.kt` runs a read with manga-ocr or ML Kit and puts the text in reading order, using furigana to correct kanji. `MangaOcr.kt` runs the model, and `OcrCorrection.kt` holds the look-alike kana.
- `Search/`, `Deinflictor/` and `Database/`: dictionary look-ups, deinflection and the bundled SQLite database (through ORMLite). `XmlParsers/` builds that database from the JMdict and KANJIDIC XML files.
- `Llm/`: the Ollama client and its prompts, for the lan build.
- `Anki/`: adding notes through AnkiDroid's content provider.
- `PassthroughActivity.kt`: text selected or shared in other apps.
- `Tutorial*.kt`: the tutorial. Its videos are `app/src/main/res/raw/tut_*.mp4`, recorded on the emulator over pages of synthetic text.

Elsewhere:

- The two builds' manifests in `app/src/offline` and `app/src/lan` set the network permissions, and `BuildConfig.LLM` switches the lan build's features on.
- `tools/fetch_manga_ocr.sh` downloads the model, and `tools/similar_kanji.py` rebuilds `app/src/main/assets/similar_kanji.txt` from Yencken's `jyouyou__strokeEditDistance.csv`.

## Making a change

- Keep each commit to one change, and say in its message what changed and why, the way the existing history does. `git log v1.4.00-beta..` shows this fork's commits.
- Try the change on a real device or the emulator, and on a release build if it touches dependencies, reflection or native code.
- If what the app does changes, update the [README](README.md) and [CHANGES.md](CHANGES.md).
- Never commit screenshots of real manga or other people's artwork. Test with synthetic pages, as the tutorial videos do.

## Reporting a bug

Use the [bug report form](../../issues/new/choose). The phone model, Android version, which build and what you did make a bug much easier to find.
