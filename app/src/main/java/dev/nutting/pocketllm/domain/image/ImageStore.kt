package dev.nutting.pocketllm.domain.image

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Generated images live as files under `filesDir/images/<conversationId>/` — never in the database, whose
 * cursor window can't hold multi-megabyte rows. Paths handed out and stored are relative to [filesDir].
 */
class ImageStore(
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = File(filesDir, ROOT)

    /**
     * Writes [bytes] as image [index] of [messageId] and returns its relative path. The file only appears once
     * fully written (temp file + rename), so readers never see a partial image and a crash leaves at most a
     * temp file for [sweepOrphans].
     */
    fun write(conversationId: String, messageId: String, index: Int, bytes: ByteArray, extension: String): String {
        requireSafe(conversationId)
        requireSafe(messageId)
        requireSafe(extension)
        val dir = File(root, conversationId)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create $dir")
        val name = "${messageId}_$index.$extension"
        val tmp = File(dir, ".$name.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        return "$ROOT/$conversationId/$name"
    }

    fun file(relativePath: String): File = File(filesDir, relativePath)

    /** Deletes one stored image (e.g. one written by a save that then failed or was cancelled). */
    fun delete(relativePath: String) {
        val f = file(relativePath)
        require(f.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Not an image path: $relativePath" }
        if (f.exists() && !f.delete()) Log.w(TAG, "Couldn't delete $relativePath; the orphan sweep will retry")
    }

    /** Removes every image of a conversation (called when the conversation is deleted). */
    fun deleteConversation(conversationId: String) {
        requireSafe(conversationId)
        val dir = File(root, conversationId)
        if (dir.exists() && !dir.deleteRecursively()) Log.w(TAG, "Couldn't fully delete $dir; the orphan sweep will retry")
    }

    /**
     * Deletes files that no database row references (e.g. images of deleted messages, temp files left by a
     * crash) and then empty conversation directories. Files younger than [graceMs] are kept so a job that is
     * still saving isn't raced. Returns the number of files deleted.
     */
    fun sweepOrphans(referencedPaths: Set<String>, graceMs: Long = DEFAULT_GRACE_MS): Int {
        val cutoff = clock() - graceMs
        var deleted = 0
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val relative = "$ROOT/${dir.name}/${f.name}"
                if (f.isFile && relative !in referencedPaths && f.lastModified() < cutoff) {
                    if (f.delete()) deleted++ else Log.w(TAG, "Couldn't delete orphan $relative")
                }
            }
            if (dir.listFiles()?.isEmpty() == true && dir.lastModified() < cutoff) dir.delete()
        }
        if (deleted > 0) Log.i(TAG, "Removed $deleted orphaned image file(s)")
        return deleted
    }

    private fun requireSafe(component: String) =
        require(SAFE.matches(component)) { "Unsafe path component: $component" }

    companion object {
        private const val TAG = "ImageStore"
        const val ROOT = "images"
        const val DEFAULT_GRACE_MS = 60 * 60 * 1000L
        private val SAFE = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
    }
}

/**
 * The latest live-preview frame of each running job, under `cacheDir/image-previews/`. Frames are disposable,
 * so they live in the cache. Each step gets a new file name: an image loader keyed by file would otherwise keep
 * showing the first frame. Paths handed out and stored are relative to [cacheDir].
 */
class ImagePreviewStore(private val cacheDir: File) {
    private val root = File(cacheDir, ROOT)

    /** Writes [bytes] as [jobId]'s frame [frame], removes its older frames, and returns the relative path. */
    fun write(jobId: String, frame: String, bytes: ByteArray, extension: String): String {
        require(SAFE.matches(jobId) && SAFE.matches(frame) && SAFE.matches(extension)) { "Unsafe preview name" }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Can't create $root")
        val name = "${jobId}_$frame.$extension"
        val tmp = File(root, ".$name.tmp")
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), File(root, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        framesOf(jobId).filter { it.name != name }.forEach { it.delete() }
        return "$ROOT/$name"
    }

    fun file(relativePath: String): File = File(cacheDir, relativePath)

    /** Removes every frame of [jobId]; called when the job stops running. */
    fun deleteJob(jobId: String) {
        framesOf(jobId).forEach { it.delete() }
    }

    private fun framesOf(jobId: String): List<File> =
        root.listFiles { f -> f.name.startsWith("${jobId}_") || f.name.startsWith(".${jobId}_") }.orEmpty().toList()

    companion object {
        const val ROOT = "image-previews"
        private val SAFE = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
    }
}
