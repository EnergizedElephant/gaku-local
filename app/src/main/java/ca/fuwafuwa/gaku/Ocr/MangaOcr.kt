package ca.fuwafuwa.gaku.Ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * manga-ocr (https://github.com/kha-white/manga-ocr, Apache-2.0), run on the device with ONNX Runtime:
 * a ViT encoder and a two-layer BERT decoder trained on manga. It reads a whole crop at once, vertical
 * or horizontal, and leaves furigana out. The model files come from tools/fetch_manga_ocr.sh.
 */
class MangaOcr private constructor(private val env: OrtEnvironment,
                                   private val encoder: OrtSession,
                                   private val decoder: OrtSession,
                                   private val vocab: List<String>) : AutoCloseable
{
    /**
     * Reads [bitmap]. For each character, the decoder's likeliest characters with their probabilities,
     * best (the one read) first.
     */
    fun read(bitmap: Bitmap): List<List<Pair<String, Double>>>
    {
        val chars = ArrayList<List<Pair<String, Double>>>()
        val ids = arrayListOf(START_TOKEN)

        encode(bitmap).use { hidden ->
            while (ids.size < MAX_TOKENS)
            {
                val logits = nextTokenLogits(ids, hidden)
                banRepeatedTrigrams(ids, logits)
                val probabilities = softmax(logits)

                val ranked = probabilities.indices.sortedByDescending { probabilities[it] }.take(ALTERNATIVES)
                val next = ranked[0]
                if (next == END_TOKEN) break
                ids.add(next)

                if (!isSpecial(next))
                {
                    chars.add(ranked.filterNot { isSpecial(it) }.map { toFullWidth(vocab[it]) to probabilities[it].toDouble() })
                }
            }
        }

        return chars
    }

    override fun close()
    {
        encoder.close()
        decoder.close()
    }

    private fun encode(bitmap: Bitmap): OnnxTensor
    {
        OnnxTensor.createTensor(env, preprocess(bitmap), longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())).use { pixels ->
            encoder.run(mapOf("pixel_values" to pixels)).use { output ->
                // Copied out, since the output's memory goes with it
                val hidden = output[0] as OnnxTensor
                val shape = hidden.info.shape
                val copy = ByteBuffer.allocateDirect(hidden.floatBuffer.remaining() * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                copy.put(hidden.floatBuffer).rewind()
                return OnnxTensor.createTensor(env, copy, shape)
            }
        }
    }

    private fun nextTokenLogits(ids: List<Int>, hidden: OnnxTensor): FloatArray
    {
        val idBuffer = LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() })
        OnnxTensor.createTensor(env, idBuffer, longArrayOf(1, ids.size.toLong())).use { input ->
            decoder.run(mapOf("input_ids" to input, "encoder_hidden_states" to hidden)).use { output ->
                // Logits are [1, tokens, vocabulary]; only the last token's matter
                val logits = (output[0] as OnnxTensor).floatBuffer
                val offset = (ids.size - 1) * vocab.size
                return FloatArray(vocab.size) { logits.get(offset + it) }
            }
        }
    }

    /**
     * Greyscale (manga-ocr converts to "L" and back to RGB), 224x224, scaled to -1..1, channels first.
     */
    private fun preprocess(bitmap: Bitmap): FloatBuffer
    {
        val scaled = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        val plane = IMAGE_SIZE * IMAGE_SIZE
        val buffer = FloatBuffer.allocate(3 * plane)
        for ((i, p) in pixels.withIndex())
        {
            val grey = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            val value = (grey / 255f - 0.5f) / 0.5f
            buffer.put(i, value)
            buffer.put(plane + i, value)
            buffer.put(2 * plane + i, value)
        }
        return buffer
    }

    /**
     * manga-ocr generates with no_repeat_ngram_size=3: no three tokens may appear twice.
     */
    private fun banRepeatedTrigrams(ids: List<Int>, logits: FloatArray)
    {
        if (ids.size < 2) return
        val a = ids[ids.size - 2]
        val b = ids[ids.size - 1]
        for (i in 0 until ids.size - 2)
        {
            if (ids[i] == a && ids[i + 1] == b) logits[ids[i + 2]] = Float.NEGATIVE_INFINITY
        }
    }

    private fun softmax(logits: FloatArray): FloatArray
    {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    private fun isSpecial(id: Int) = id < FIRST_TEXT_TOKEN

    /**
     * manga-ocr's output is full width (jaconv.h2z for ASCII and digits).
     */
    private fun toFullWidth(token: String): String
    {
        return token.map { if (it in '!'..'~') (it + 0xFEE0) else it }.joinToString("")
    }

    companion object
    {
        private const val TAG = "MangaOcr"
        private const val ASSET_DIR = "manga-ocr"
        private const val IMAGE_SIZE = 224
        private const val MAX_TOKENS = 300
        private const val ALTERNATIVES = 6
        private const val START_TOKEN = 2 // [CLS]
        private const val END_TOKEN = 3 // [SEP]
        // [PAD] [UNK] [CLS] [SEP] [MASK] and <unused0> to <unused9>
        private const val FIRST_TEXT_TOKEN = 15

        /**
         * Loads the model, memory-mapped from the APK (the .onnx assets are stored uncompressed).
         * @throws java.io.FileNotFoundException if this build doesn't include it
         */
        fun load(context: Context): MangaOcr
        {
            val env = OrtEnvironment.getEnvironment()
            try
            {
                // ONNX Runtime can report usage to Microsoft. Its initializer is removed in the manifest
                // too, so nothing of it starts; this covers the environment itself
                env.setTelemetry(false)
            }
            catch (e: Exception)
            {
                Log.w(TAG, "Couldn't turn off ONNX Runtime telemetry", e)
            }
            val encoder = env.createSession(mapAsset(context, "$ASSET_DIR/encoder_model_int8.onnx"), OrtSession.SessionOptions())
            val decoder = env.createSession(mapAsset(context, "$ASSET_DIR/decoder_model_int8.onnx"), OrtSession.SessionOptions())
            val vocab = context.assets.open("$ASSET_DIR/vocab.txt").bufferedReader().readLines()
            return MangaOcr(env, encoder, decoder, vocab)
        }

        private fun mapAsset(context: Context, name: String): ByteBuffer
        {
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }
    }
}
