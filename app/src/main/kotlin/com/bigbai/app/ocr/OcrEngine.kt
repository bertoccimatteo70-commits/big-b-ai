package com.bigbai.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class OcrResult(
    val text: String,
    val pages: Int,
    val blocks: Int,
    val elapsedMs: Long,
)

class OcrException(message: String) : Exception(message)

/**
 * Offline OCR for images and scanned PDFs.
 *
 * The ML Kit Latin text recogniser (Italian and English are both covered by the
 * Latin model) is bundled in the APK as `com.google.mlkit:text-recognition`, so
 * there is no Play Services download and no network request. Images are decoded
 * with a sampled bitmap, rotated according to EXIF, then passed to ML Kit.
 * Scanned PDFs are rendered page by page with Android's PdfRenderer.
 */
class OcrEngine(private val context: Context) {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeImage(uri: Uri): Result<OcrResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val started = SystemClock.elapsedRealtime()
                val bitmap = decodeRotated(uri)
                    ?: throw OcrException(
                        "Immagine non leggibile. Prova con un file JPG o PNG valido.",
                    )
                try {
                    val text = recognize(bitmap)
                    val content = text.text.trim()
                    if (content.isEmpty()) {
                        throw OcrException(
                            "Nessun testo riconosciuto. Prova con un'immagine più nitida, " +
                                "più vicina o con più contrasto.",
                        )
                    }
                    OcrResult(
                        text = content,
                        pages = 1,
                        blocks = text.textBlocks.size,
                        elapsedMs = SystemClock.elapsedRealtime() - started,
                    )
                } finally {
                    bitmap.recycle()
                }
            }.mapError()
        }

    suspend fun recognizePdf(uri: Uri, maxPages: Int = MAX_PDF_PAGES): Result<OcrResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val started = SystemClock.elapsedRealtime()
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw OcrException("PDF non accessibile: il file potrebbe essere stato spostato.")
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount <= 0) {
                            throw OcrException("Il PDF non contiene pagine.")
                        }
                        val pages = min(renderer.pageCount, maxPages)
                        val builder = StringBuilder()
                        var blocks = 0
                        for (index in 0 until pages) {
                            renderer.openPage(index).use { page ->
                                val width = 1600
                                val height = max(1, (page.height.toFloat() / page.width * width).toInt())
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val text = recognize(bitmap)
                                    blocks += text.textBlocks.size
                                    if (text.text.isNotBlank()) {
                                        builder.append("[Pagina ").append(index + 1).append("]\n")
                                        builder.append(text.text.trim()).append("\n\n")
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                        val content = builder.toString().trim()
                        if (content.isEmpty()) {
                            throw OcrException(
                                "Nessun testo riconosciuto nelle prime $pages pagine del PDF. " +
                                    "Le pagine potrebbero essere immagini a bassa risoluzione.",
                            )
                        }
                        OcrResult(
                            text = content,
                            pages = pages,
                            blocks = blocks,
                            elapsedMs = SystemClock.elapsedRealtime() - started,
                        )
                    }
                }
            }.mapError()
        }

    fun close() {
        runCatching { recognizer.close() }
    }

    private suspend fun recognize(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    if (continuation.isActive) continuation.resume(text)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            OcrException(
                                "Riconoscimento non riuscito: " +
                                    (error.message ?: "errore del motore OCR locale."),
                            ),
                        )
                    }
                }
        }

    /**
     * Decodes a bounded bitmap and applies the EXIF rotation. A 12 MP photo is
     * sampled down to at most 2400 px on the long side: enough for ML Kit and
     * far below the memory a full-resolution bitmap would need.
     */
    private fun decodeRotated(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation == 0) return decoded
        return runCatching {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        }.getOrElse {
            decoded.recycle()
            null
        }
    }

    private fun <T> Result<T>.mapError(): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = { error ->
            Result.failure(
                when (error) {
                    is OcrException -> error
                    is SecurityException -> OcrException(
                        "Il PDF è protetto da password o non è accessibile.",
                    )
                    else -> OcrException(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "OCR non riuscito. Prova con un altro file.",
                    )
                },
            )
        },
    )

    private companion object {
        /** Images larger than this are sampled down before recognition. */
        const val MAX_SIDE = 2400

        /** A scanned PDF is OCR'd page by page; this bounds the wait and memory. */
        const val MAX_PDF_PAGES = 20
    }
}
