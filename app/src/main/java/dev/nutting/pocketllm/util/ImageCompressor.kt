package dev.nutting.pocketllm.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

object ImageCompressor {

    private const val TAG = "ImageCompressor"

    fun compressAndEncode(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1024,
        jpegQuality: Int = 85,
    ): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val scaled = scaleToFit(original, maxDimension)
            if (scaled !== original) original.recycle()

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
            scaled.recycle()

            val bytes = outputStream.toByteArray()
            "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image: $uri", e)
            null
        }
    }

    private fun scaleToFit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = maxDimension.toFloat() / maxOf(width, height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
