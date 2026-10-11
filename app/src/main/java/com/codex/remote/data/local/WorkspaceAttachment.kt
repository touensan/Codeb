package com.codex.remote.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.codex.remote.data.api.readLimited
import com.codex.remote.domain.ComposerImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

internal suspend fun readWorkspaceAttachment(context: Context, uri: Uri, imageOnly: Boolean): ComposerImageAttachment = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
    require(!imageOnly || mime.startsWith("image/")) { "Select an image" }
    var name = if (mime.startsWith("image/")) "image" else "attachment"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: name
    }
    // Provider-supplied names are untrusted path components on both Windows and POSIX hosts.
    name = name.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().take(80).trimEnd('.', ' ')
    if (name.lastOrNull()?.isHighSurrogate() == true) name = name.dropLast(1)
    if (name.isBlank() || name.all { it == '.' }) name = "attachment"
    if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(name)) name = "_$name"
    val bytes = resolver.openInputStream(uri)?.use { it.readLimited(18 * 1024 * 1024) } ?: error("Unable to read attachment")
    require(bytes.size <= 18 * 1024 * 1024) { "Attachment must be 18 MB or smaller" }
    if (mime.startsWith("image/")) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or invalid image" }
    }
    ComposerImageAttachment(displayName = name, mimeType = mime,
        dataUrl = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}")
}
