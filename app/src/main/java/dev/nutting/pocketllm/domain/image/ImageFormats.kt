package dev.nutting.pocketllm.domain.image

/** A recognised image encoding and the dimensions read from its header. */
data class ImageInfo(val extension: String, val mimeType: String, val width: Int, val height: Int)

/**
 * Identifies PNG, JPEG and WebP data from magic bytes and reads its dimensions from the header, without
 * decoding the (multi-megabyte) image. Servers don't always say which format they returned, so this is
 * the source of truth for the file extension and the stored size. Returns null for anything else.
 */
fun sniffImage(bytes: ByteArray): ImageInfo? = when {
    isPng(bytes) -> pngInfo(bytes)
    isJpeg(bytes) -> jpegInfo(bytes)
    isWebp(bytes) -> webpInfo(bytes)
    else -> null
}

private fun isPng(b: ByteArray) = b.size >= 8 &&
    b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

private fun isJpeg(b: ByteArray) = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

private fun isWebp(b: ByteArray) = b.size >= 16 &&
    String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"

private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
private fun be32(b: ByteArray, i: Int) = (be16(b, i) shl 16) or be16(b, i + 2)
private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + 2) shl 16)

/** IHDR is always the first chunk: width and height are big-endian at offsets 16 and 20. */
private fun pngInfo(b: ByteArray): ImageInfo? {
    if (b.size < 24 || String(b, 12, 4, Charsets.US_ASCII) != "IHDR") return null
    return ImageInfo("png", "image/png", be32(b, 16), be32(b, 20))
}

/** Walks the JPEG segments to the first start-of-frame marker, which holds the height and width. */
private fun jpegInfo(b: ByteArray): ImageInfo? {
    var i = 2
    while (i + 4 <= b.size) {
        if (u8(b, i) != 0xFF) return null
        val marker = u8(b, i + 1)
        if (marker == 0xFF) { i++; continue } // padding
        if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue } // segments without a length
        val length = be16(b, i + 2)
        val isStartOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isStartOfFrame) {
            if (i + 9 > b.size) return null
            return ImageInfo("jpg", "image/jpeg", width = be16(b, i + 7), height = be16(b, i + 5))
        }
        if (length < 2) return null
        i += 2 + length
    }
    return null
}

/** WebP stores its size in the first chunk, whose layout depends on the variant (lossy, lossless, extended). */
private fun webpInfo(b: ByteArray): ImageInfo? {
    if (b.size < 30) return null
    val (w, h) = when (String(b, 12, 4, Charsets.US_ASCII)) {
        "VP8 " -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
        "VP8L" -> {
            val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
            ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
        }
        "VP8X" -> (le24(b, 24) + 1) to (le24(b, 27) + 1)
        else -> return null
    }
    return ImageInfo("webp", "image/webp", w, h)
}
