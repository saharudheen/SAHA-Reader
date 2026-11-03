package com.saha.reader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Finds the real folder of an opened file so the user can swipe through its neighbours.
 * Needs "All files access" (Android 11+) or storage permission (Android 8–10).
 */
object FolderFiles {

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Readable files in [dir] that this app can show, sorted by name. */
    fun list(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") && FileRouter.detect(it.name, null) != DocType.UNKNOWN }
            .sortedBy { it.name.lowercase() }

    fun fileFor(ctx: Context, uri: Uri, name: String): File? = try {
        resolve(ctx, uri, name)?.takeIf { it.isFile && it.canRead() }
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun resolve(ctx: Context, uri: Uri, name: String): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        if (uri.scheme != "content") return null
        val auth = uri.authority ?: ""

        if (DocumentsContract.isDocumentUri(ctx, uri)) {
            val id = DocumentsContract.getDocumentId(uri)
            when (auth) {
                "com.android.externalstorage.documents" -> {
                    val volume = id.substringBefore(':')
                    val rel = id.substringAfter(':', "")
                    val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path
                    else "/storage/$volume"
                    return File(base, rel)
                }
                "com.android.providers.downloads.documents" -> {
                    val f = when {
                        id.startsWith("raw:") -> File(id.removePrefix("raw:"))
                        id.startsWith("msf:") -> mediaPath(ctx, MediaStore.Files.getContentUri("external"), id.removePrefix("msf:"))
                        else -> null
                    }
                    if (f != null && f.isFile) return f
                    val guess = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                    return if (guess.isFile) guess else null
                }
                "com.android.providers.media.documents" -> {
                    val type = id.substringBefore(':')
                    val num = id.substringAfter(':', "")
                    val base = when (type) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    return mediaPath(ctx, base, num)
                }
            }
        }
        if (auth == "media") {
            dataColumn(ctx, uri)?.let { return File(it) }
        }
        // File managers often put the real path inside their own content:// links.
        val path = Uri.decode(uri.path ?: "")
        for (marker in listOf("/storage/", "/sdcard/")) {
            val i = path.indexOf(marker)
            if (i >= 0) {
                val f = File(path.substring(i))
                if (f.isFile) return f
            }
        }
        return null
    }

    private fun mediaPath(ctx: Context, base: Uri, id: String): File? {
        if (id.isEmpty()) return null
        return dataColumn(ctx, Uri.withAppendedPath(base, id))?.let { File(it) }
    }

    @Suppress("DEPRECATION")
    private fun dataColumn(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
