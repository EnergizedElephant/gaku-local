# Gaku (Picture-based Japanese OCR)

**Gaku** is a fork of [Kaku](https://github.com/0xbad1d3a5/Kaku) android application. I do not intend to maintain this project, so feel free to contribute however you see fit. Note that the codebase was largely vibe-coded to produce a minimum-viable-product, as I am not an android developer. I'm sorry if the changes cause any future maintainers headache.


### Why this fork?
The original Gaku application relies on older OCR technology. Gaku replaces the OCR engine with **Google MLKit**, providing:
- Significantly faster recognition.
- Higher accuracy on Japanese text.
- Modern Android support (Android 13/14 compatibility).

This, unfortunately, does means there is no "kanji alternative" selection screen, so if a character is misread, it will not be possible to correct it (although this is a lesser issue with the Google OCR). I might consider adding back the updated Tesseract OCR backend to provide this functionality.

### Credits & License
This project is based on **Kaku**, originally developed by [0xbad1d3a5](https://github.com/0xbad1d3a5).

"Read again" reads a capture with [manga-ocr](https://github.com/kha-white/manga-ocr) by Maciej Budyś (Apache-2.0), using the int8 ONNX export from [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) and running on the device with [ONNX Runtime](https://onnxruntime.ai/) (MIT). The model (~117 MB) isn't in git: run `tools/fetch_manga_ocr.sh` before building to include it. Gaku needs Android 7.0 (API 24) or later for ONNX Runtime.

The visually similar kanji offered as alternatives (`app/src/main/assets/similar_kanji.txt`) come from Lars Yencken's [kanji confusion data](https://lars.yencken.org/datasets/kanji-confusion/) (stroke edit distance; Yencken & Baldwin, 2008), licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). `tools/similar_kanji.py` rebuilds the file from `jyouyou__strokeEditDistance.csv`.

Licensed under the **BSD 3-Clause License**.
See the [LICENSE](LICENSE) file for the full original license text.
