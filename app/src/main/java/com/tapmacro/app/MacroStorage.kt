package com.tapmacro.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MacroStorage {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "macros")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun save(context: Context, name: String, events: List<TapEvent>) {
        val arr = JSONArray()
        for (e in events) {
            val o = JSONObject()
            o.put("x", e.x)
            o.put("y", e.y)
            o.put("delayMs", e.delayMs)
            o.put("durationMs", e.durationMs)
            arr.put(o)
        }
        File(dir(context), "$name.json").writeText(arr.toString())
    }

    fun load(context: Context, name: String): List<TapEvent> {
        val file = File(dir(context), "$name.json")
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        val result = mutableListOf<TapEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(
                TapEvent(
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    delayMs = o.getLong("delayMs"),
                    durationMs = o.getLong("durationMs")
                )
            )
        }
        return result
    }

    fun list(context: Context): List<String> {
        return dir(context).listFiles()?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    }

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.json").delete()
    }
}
