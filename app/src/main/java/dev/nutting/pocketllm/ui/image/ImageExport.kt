package dev.nutting.pocketllm.ui.image

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Saving and sharing generated images. */
object ImageExport {
    private const val TAG = "ImageExport"
    private const val ALBUM = "Pocket LLM"

    /** Android 9 needs the storage permission to write to Pictures; later versions use MediaStore without one. */
    val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Copies [file] into the gallery under Pictures/Pocket LLM. Throws [IOException] on failure. */
    suspend fun saveToGallery(context: Context, file: File, mimeType: String) = withContext(Dispatchers.IO) {
        val name = "pocket-llm-${System.currentTimeMillis()}.${file.extension}"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("The gallery refused the new image")
                try {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Couldn't open the gallery file")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: IOException) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create $dir")
                val target = File(dir, name)
                file.copyTo(target)
                MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeType), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Saving image to the gallery failed", e)
            throw e as? IOException ?: IOException(e.message, e)
        }
    }

    fun shareIntent(context: Context, file: File, mimeType: String, prompt: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share image")
    }
}
