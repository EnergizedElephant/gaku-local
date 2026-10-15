#!/bin/bash
# Downloads the manga-ocr model (Apache-2.0, https://github.com/kha-white/manga-ocr) into
# app/src/main/assets/manga-ocr/ for the "Read again" button. The files are ~117 MB, so they're kept
# out of git (app/src/main/assets/* is ignored); without them the button says the model is missing.
#
# int8 ONNX export from onnx-community/manga-ocr-base-ONNX and the tokenizer vocabulary from
# kha-white/manga-ocr-base, both pinned to a commit and checked against SHA-256 hashes.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT=app/src/main/assets/manga-ocr
ONNX=https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/f9023406bb2f6b17df67bc4a327c56ecd20611f0
ORIG=https://huggingface.co/kha-white/manga-ocr-base/resolve/aa6573bd10b0d446cbf622e29c3e084914df9741

mkdir -p "$OUT"
fetch() { # url file sha256
    if [ -f "$OUT/$2" ] && echo "$3  $OUT/$2" | sha256sum -c --status; then
        echo "$2: already present"
        return
    fi
    curl -fL --retry 3 -o "$OUT/$2.part" "$1"
    echo "$3  $OUT/$2.part" | sha256sum -c --quiet
    mv "$OUT/$2.part" "$OUT/$2"
}

fetch "$ONNX/onnx/encoder_model_int8.onnx" encoder_model_int8.onnx ddd1af56963093795705fa38da6ce7e6567d1658e7c7359db7e13fcd37dbf279
fetch "$ONNX/onnx/decoder_model_int8.onnx" decoder_model_int8.onnx 2e7177d2b0a59f1c612b694ed70c13971bee765cc2b2bc7bc9376e4753652f27
fetch "$ORIG/vocab.txt" vocab.txt 344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d
