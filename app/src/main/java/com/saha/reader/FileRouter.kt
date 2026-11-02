package com.saha.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

enum class DocType(val badge: String, val label: String, val color: Int, val mime: String) {
    PDF("PDF", "PDF", 0xFFE53935.toInt(), "application/pdf"),
    WORD("DOC", "Word", 0xFF1E5BB8.toInt(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("XLS", "Excel", 0xFF1D7A45.toInt(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("CSV", "CSV", 0xFF00897B.toInt(), "text/csv"),
    TEXT("TXT", "Text", 0xFF546E7A.toInt(), "text/plain"),
    IMAGE("IMG", "Image", 0xFF8E24AA.toInt(), "image/*"),
    OLD_WORD("DOC", "Word 97-2003", 0xFF1E5BB8.toInt(), "application/msword"),
    OLD_EXCEL("XLS", "Excel 97-2003", 0xFF1D7A45.toInt(), "application/vnd.ms-excel"),
    UNKNOWN("?", "File", 0xFF757575.toInt(), "*/*")
}

object MimeSets {
    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    val PDF = arrayOf("application/pdf")
    val WORD = arrayOf(DOCX, "application/msword")
    val EXCEL = arrayOf(XLSX, "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "text/tab-separated-values")
    val TEXT = arrayOf("text/*")
    val IMAGE = arrayOf("image/*")
    val ALL = arrayOf(
        "application/pdf", DOCX, "application/msword", XLSX, "application/vnd.ms-excel",
        "text/*", "image/*", "application/octet-stream"
    )
}

object FileRouter {
    private val textExt = setOf(
        "txt", "log", "md", "json", "xml", "html", "htm", "java", "kt", "py", "js", "css", "ini",
        "cfg", "conf", "yaml", "yml", "sql", "srt", "properties", "bat", "sh", "c", "cpp", "h"
    )
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun detect(name: String, mime: String?): DocType {
        when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> return DocType.PDF
            "docx", "docm", "dotx" -> return DocType.WORD
            "xlsx", "xlsm", "xltx" -> return DocType.EXCEL
            "csv", "tsv" -> return DocType.CSV
            "doc" -> return DocType.OLD_WORD
            "xls" -> return DocType.OLD_EXCEL
            in textExt -> return DocType.TEXT
            in imageExt -> return DocType.IMAGE
        }
        val m = mime?.lowercase() ?: return DocType.UNKNOWN
        return when {
            m == "application/pdf" -> DocType.PDF
            m.contains("wordprocessingml") -> DocType.WORD
            m.contains("spreadsheetml") -> DocType.EXCEL
            m == "application/msword" -> DocType.OLD_WORD
            m == "application/vnd.ms-excel" -> DocType.OLD_EXCEL
            m.contains("csv") || m.contains("comma-separated") || m.contains("tab-separated") -> DocType.CSV
            m.startsWith("text/") -> DocType.TEXT
            m.startsWith("image/") -> DocType.IMAGE
            else -> DocType.UNKNOWN
        }
    }

    fun open(ctx: Context, uri: Uri, knownName: String? = null) {
        val name = knownName ?: displayName(ctx, uri)
        val mime = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
        val type = detect(name, mime)
        val cls = when (type) {
            DocType.PDF -> PdfActivity::class.java
            DocType.IMAGE -> ImageActivity::class.java
            else -> WebDocActivity::class.java
        }
        val i = Intent(ctx, cls)
            .setData(uri)
            .putExtra(BaseViewerActivity.EXTRA_NAME, name)
            .putExtra(BaseViewerActivity.EXTRA_TYPE, type.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
