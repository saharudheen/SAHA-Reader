package com.saha.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Recent(val uri: String, val name: String, val type: String, val time: Long) {
    val docType: DocType
        get() = try { DocType.valueOf(type) } catch (_: Exception) { DocType.UNKNOWN }
}

object Recents {
    private const val KEY = "recent_v1"
    private const val MAX = 40

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("saha_reader", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Recent> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Recent(o.getString("u"), o.optString("n"), o.optString("t"), o.optLong("ts"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Recent>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("u", it.uri).put("n", it.name).put("t", it.type).put("ts", it.time))
        }
        list.drop(MAX).forEach { release(ctx, it.uri) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, r: Recent) {
        val l = load(ctx).filter { it.uri != r.uri }.toMutableList()
        l.add(0, r)
        save(ctx, l)
    }

    fun remove(ctx: Context, uri: String) {
        release(ctx, uri)
        save(ctx, load(ctx).filter { it.uri != uri })
    }

    fun clear(ctx: Context) {
        load(ctx).forEach { release(ctx, it.uri) }
        prefs(ctx).edit().remove(KEY).apply()
    }

    private fun release(ctx: Context, uri: String) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }
}
