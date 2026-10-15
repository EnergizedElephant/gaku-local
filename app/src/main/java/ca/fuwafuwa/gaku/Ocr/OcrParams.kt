package ca.fuwafuwa.gaku.Ocr

import android.graphics.Bitmap

data class OcrParams(val bitmap: Bitmap,
                     val originalBitmap: Bitmap,
                     val box: BoxParams,
                     val offsetX: Int,
                     val offsetY: Int,
                     val instantMode: Boolean,
                     // Read with manga-ocr instead of ML Kit ("Read again")
                     val mangaOcr: Boolean = false)
{
    override fun toString() : String {
        return "Box: $box InstantOCR: $instantMode Offset: ($offsetX, $offsetY) MangaOcr: $mangaOcr"
    }
}




