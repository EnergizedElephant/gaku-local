# gaku-offline

A privacy-hardened fork of [Gaku](https://github.com/ExecuteOrd66/Gaku), a Japanese OCR dictionary for Android. Drag a box over Japanese text in any app, and Gaku reads it on the device and looks the words up in a built-in dictionary. It's made for reading raw manga, games and anything else you can't select and copy.

The app is still called Gaku. This is a personal fork and isn't on any app store, so to use it, [build it yourself](#building).

## Where it comes from

| Project | What it brought |
|---|---|
| [Kaku](https://github.com/0xbad1d3a5/Kaku) by 0xbad1d3a5, 2016–2022 | The app itself: the floating capture box, OCR with Tesseract, JMdict and KANJIDIC look-ups with deinflection, instant mode, fixing misread characters, and looking up text selected in other apps. |
| [Gaku](https://github.com/ExecuteOrd66/Gaku) by Carl Oosthuizen, December 2025 | One migration commit: the rename to Gaku, Google ML Kit in place of Tesseract, crash fixes for the edit window, kanji alternatives and rotation, and a build updated for current Android. |
| gaku-offline, this repo, October 2026 | Everything under [What this fork changes](#what-this-fork-changes). |

Each of this fork's changes is explained in its commit message. They're the commits after Gaku's `5cb8f2e` ("Migrate Kaku to Gaku"), so `git log 5cb8f2e..` lists them.

## What this fork changes

### Privacy and security

- **No network access** in the offline build. It has no internet or network-state permission. Ads were switched off, but Gaku still carried the Google Mobile Ads SDK, with an auto-starting initializer, WorkManager and the advertising ID permission. It's gone.
- **Screen captures never touch storage.** Gaku saved the whole screen and the crop as PNG files whenever a capture check failed, and its clean-up never deleted them. Nothing is written now, and captures left by earlier builds are deleted at startup.
- **Nothing on screen reaches the log.** Recognised text is never logged, and release builds strip all debug logging.
- **Library telemetry is off.** ML Kit's usage-reporting backend is unregistered, ONNX Runtime's Microsoft telemetry is removed and disabled, and ML Kit only starts when you ask for an ML Kit read.
- **Less exposed.** App data is excluded from cloud backup and device transfer, the capture service isn't exported, and text shared from another app can't start screen capture or crash Gaku.
- **No rating prompts.** They pointed at a Play Store listing that doesn't exist and at a placeholder email address.

### Reading

- **manga-ocr by default.** [manga-ocr](https://github.com/kha-white/manga-ocr) runs on the device with ONNX Runtime. It reads a whole bubble at once, vertical or horizontal, and leaves furigana out. *Read with ML Kit* in the results rereads the same crop with ML Kit, which copes better with a box spanning several bubbles.
- **Right reading order.** Vertical columns read right to left. ML Kit lists them left to right.
- **Furigana.** With ML Kit, furigana is left out of the text and used to correct the kanji beside it, matching inflected words by their dictionary form.
- **Fixing misreads again.** Each character offers alternatives: manga-ocr's next guesses, visually similar kanji from Lars Yencken's data, and look-alike kana. The edit window shows the character in context.

### A local LLM, in the lan build

- **Two builds.** `offline` is the app as described everywhere else here, with no network permission. `lan` adds one, so it can ask an [Ollama](https://ollama.com) server on your own network to translate the text or explain it.
- **Translation from your own model.** With a server set, *Translate* shows the model's translation above the dictionary results, and *Google* opens Google Translate instead.
- **Ask about a sentence.** *Ask* opens a chat about the text. The model is also told the word you last looked up, its dictionary entry and the translation, if any. Buttons ask for the grammar, a word-by-word breakdown or the nuance, and you can type follow-up questions.
- **Local network only.** The lan build refuses servers outside private, link-local and Tailscale addresses, and only connects when you tap *Connect*, *Translate* or *Ask*.

### Capture

- **Single-app sharing works.** On Android 14 and later you can share one app instead of the whole screen. Captures are matched by frame time instead of by spotting the box's red border, which never appears when only one app is shared.
- **Fewer failed captures.** A square virtual display keeps rotation from misaligning captures, the box is located correctly on Android 15, and captures over dark content no longer time out.
- **Hiding keeps the session.** Capture pauses while Gaku is hidden, and showing it again neither asks for consent nor crashes.
- **Smaller app.** Kaku's unused Tesseract data is gone, which took the APK from 81 MB to 50 MB before manga-ocr's model was added.

### Interface

- **Controls in the app.** The start screen says whether Gaku is off, running or hidden, with Start, Show, Hide and Stop buttons.
- **The box stays put.** Hiding and showing Gaku, stopping it or restarting the phone no longer sends the capture box back to the middle of the screen.
- **Permission prompts that recover.** They come one at a time, and declining one leaves a button to try again instead of closing the app.
- **Buttons instead of hidden gestures.** The results window has Translate, Copy, Read with ML Kit or manga-ocr, Instant and close buttons, and each character has its alternatives, Edit and Delete. The results open with the first word looked up, so these are in view without a tap. The row scrolls on narrow screens.
- **Back closes the results**, instead of going to the app underneath and closing the page you were reading.
- **A Quick Settings tile** shows, hides or starts Gaku, and the notification's buttons are labelled.
- **No freezes while reading.** A double-tap during a read says so, and hiding Gaku mid-read is instant.
- **An up-to-date tutorial**, and no more beta screen on every launch.

### Build

- Release builds are minified with R8.
- The manga-ocr model is downloaded by a script, pinned to a commit and checked against SHA-256 hashes. It's kept out of git.
- Unused dependencies are out of the app: kuromoji, now only used by a test, the JitPack repository and the legacy support library.

## Privacy

- Gaku only captures the screen while it's shown. When it's hidden it keeps the capture session but takes no frames, so Android's screen-sharing indicator stays on until you stop Gaku.
- Captures and recognised text stay in memory. They're never written to storage or logged.
- Reading and the dictionary run on the device. The offline build has no internet permission.
- In the lan build, text goes to your Ollama server when you tap *Translate* or *Ask*: the recognised text, the word you last looked up with its dictionary entry, and what you type in the chat. Conversations stay in memory and end when you open another sentence.
- Otherwise, text leaves the device only when you open Google Translate, which gets the text, or when you copy it.
- One exception: when you use *Read with ML Kit*, ML Kit can report usage to Google Play services, which has its own network access. From its code, these reports are metadata such as call timings, not images or text.

## Using it

**First launch.** Swipe through the tutorial, then grant:

- *Display over other apps*, which is required for the capture box and results.
- *Notifications*, which is optional and gives you a notification with Hide, Show, Instant and Shutdown buttons.
- *Screen capture*, which Gaku asks for each time it starts. On Android 14 and later, choose *Entire screen*. If you share a single app, Gaku can only read it while it fills the screen.

**Reading.** Drag the box over Japanese text, and resize it from its bottom-right corner. It stays where you leave it, even after hiding or restarting Gaku. Double-tap the box to read it. With instant mode on, a small box is read as soon as you let go of it, which suits single words.

**The results.** They open with the first word looked up. Tap any character to look it up from that point instead. The selected character's alternatives appear below it: tap one to swap it in, or use *Edit* to type or handwrite the right one, or *Delete*. *Translate* opens Google Translate's pop-up, *Copy* copies the text, and *Read with ML Kit* rereads the crop with the other reader. Tap outside the results, press Back, or tap ✕ to close them.

In the lan build with a server set, *Translate* shows your model's translation, *Google* opens Google Translate, and *Ask* opens a chat about the sentence. Close the chat with ✕ or *Back* to return to the results. *Stop* ends an answer early.

The older gestures still work: on a character, swipe down for alternatives, up and left to edit, or up and right to delete, and long-press to copy all the text.

**Showing, hiding and stopping.** Use the start screen, the notification or the Quick Settings tile. Turning the screen off hides Gaku. *Stop* ends the capture session, and *Start* asks for consent again.

**A local LLM (lan build).** Run Ollama where the tablet can reach it, listening on the network rather than only on its own machine (`OLLAMA_HOST=0.0.0.0`). On Gaku's start screen, type the server's address, such as `192.168.1.10` (port 11434 is assumed), tap *Connect* and choose a model. To turn it off, clear the address and tap *Connect*.

If the tablet and the server are on different networks, there are two other ways to connect:

- **Over USB.** On the computer running Ollama, run `adb reverse tcp:11434 tcp:11434` and set the server to `127.0.0.1`. This works until the cable is unplugged.
- **With [Tailscale](https://tailscale.com).** Gaku accepts Tailscale addresses (100.64.0.0/10). Rather than putting the tablet in your own tailnet, sign it into a separate Tailscale account and share just the server with that account. Then, in your access policy, allow that account to reach only port 11434 on the server. Tailscale's default policy lets everything in (`"src": ["*"]`), and that includes accounts you've shared with, so narrow it to `autogroup:member`. If your rules name the server by a host defined in the policy, check that the host has the server's current Tailscale address.

**Text you can select.** Select Japanese text in any app and choose *Gaku* from the menu, or share it to Gaku, to look it up without OCR.

## Building

Use JDK 17 or 21: newer JDKs can fail with "Unsupported class file major version". You also need the Android SDK with platform 34. Point the build at the SDK with `sdk.dir` in `local.properties`, which Android Studio writes, or with `ANDROID_HOME`.

```sh
tools/fetch_manga_ocr.sh                # the manga-ocr model, ~117 MB, pinned and SHA-256-checked
bash ./gradlew assembleOfflineDebug     # gradlew isn't marked executable
adb install -r app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

- There are two builds: `offline`, with no network permission, and `lan`, which adds the Ollama features (`assembleLanDebug`, `app/build/outputs/apk/lan/debug/app-lan-debug.apk`). They share an application ID, so either installs over the other. The flavour manifests in `app/src/offline` and `app/src/lan` set the network permissions.

- Without the model, the app still builds and runs but reads with ML Kit only, and *Read with manga-ocr* says the model is missing.
- The build includes native libraries for arm64-v8a, for devices, and x86_64, for the emulator.
- `bash ./gradlew testOfflineDebugUnitTest --tests 'ca.fuwafuwa.gaku.Ocr.*' --tests 'ca.fuwafuwa.gaku.Llm.*'` runs the OCR tests for reading order and furigana, and the LLM client's tests. `GenerateDictionary` isn't a real test: it builds the dictionary database from a JMdict XML file at a hard-coded Windows path, so it fails elsewhere.
- `bash ./gradlew assembleOfflineRelease` (or `assembleLanRelease`) builds a minified, unsigned APK. Sign it with your own key using `apksigner`. R8's keep rules for ORMLite and ONNX Runtime are in `app/proguard-rules.pro`. R8 mistakes only show up at runtime, so try a release build on a device after changing dependencies.
- `tools/similar_kanji.py` rebuilds `app/src/main/assets/similar_kanji.txt` from Yencken's `jyouyou__strokeEditDistance.csv`.

## Credits and licences

- [Kaku](https://github.com/0xbad1d3a5/Kaku) by 0xbad1d3a5, under the BSD 3-Clause licence, which this fork keeps. See [LICENSE](LICENSE).
- [Gaku](https://github.com/ExecuteOrd66/Gaku) by Carl Oosthuizen.
- The dictionary includes material from the JMdict and KANJIDIC2 dictionary files, used in accordance with the licence provisions of the [Electronic Dictionary Research and Development Group](https://www.edrdg.org/edrdg/licence.html), CC BY-SA 4.0. The bundled database dates from February 2019.
- The deinflection rules in `deinflect.dat` are by Jonathan Zarate (polarcloud.com), the author of Rikaichan, as credited in the file.
- [manga-ocr](https://github.com/kha-white/manga-ocr) by Maciej Budyś, Apache-2.0, using the int8 ONNX export from [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX), run with [ONNX Runtime](https://onnxruntime.ai/), MIT.
- [Google ML Kit](https://developers.google.com/ml-kit) text recognition for Japanese, under Google's ML Kit terms.
- Visually similar kanji: Lars Yencken's [kanji confusion data](https://lars.yencken.org/datasets/kanji-confusion/) (stroke edit distance; Yencken & Baldwin, 2008), licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
- The start screen shows fan art of Kobayakawa Sae, with each image credited to its artist in the app. The artwork belongs to the artists and isn't covered by this repo's licence.
