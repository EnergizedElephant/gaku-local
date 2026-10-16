package ca.fuwafuwa.gaku.Ocr

import android.graphics.Bitmap

data class OcrParams(val bitmap: Bitmap,
                     val originalBitmap: Bitmap,
                     val box: BoxParams,
                     val offsetX: Int,
                     val offsetY: Int,
                     val instantMode: Boolean,
                     val engine: OcrEngine = OcrEngine.DEFAULT)
{
    override fun toString() : String {
        return "Box: $box InstantOCR: $instantMode Offset: ($offsetX, $offsetY) Engine: $engine"
    }
}

enum class OcrEngine
{
    // manga-ocr, or ML Kit if this build doesn't include its model
    DEFAULT,
    // The results window's button to read the same crop with the other one
    MANGA_OCR,
    ML_KIT
}




