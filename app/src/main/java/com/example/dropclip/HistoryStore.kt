package com.example.dropclip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条粘贴历史。图片只记本地缓存文件路径（字节已经在 cache/clip/ 里）。 */
data class ClipEntry(
    val kind: Kind,
    val text: String,
    val filePath: String?,
    val time: Long
) {
    enum class Kind { TEXT, IMAGE }

    fun toJson(): JSONObject = JSONObject()
        .put("k", kind.name)
        .put("t", text)
        .put("p", filePath ?: "")
        .put("ts", time)

    companion object {
        fun fromJson(o: JSONObject): ClipEntry = ClipEntry(
            kind = runCatching { Kind.valueOf(o.optString("k")) }.getOrDefault(Kind.TEXT),
            text = o.optString("t"),
            filePath = o.optString("p").ifEmpty { null },
            time = o.optLong("ts")
        )
    }
}

/**
 * 历史存在 filesDir/clip_history.json，最多 [MAX] 条，最新的在最前面。
 * 用 JSON 数组而不是数据库：条目少、结构简单，省一个 Room 依赖。
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val items = ArrayList<ClipEntry>()

    val size: Int get() = items.size

    init {
        load()
    }

    fun all(): List<ClipEntry> = items.toList()

    /**
     * 同一条内容只留一份：已存在就先删掉再插到最前面，效果是「用过就升到顶部」。
     */
    fun add(entry: ClipEntry) {
        items.removeAll {
            it.kind == entry.kind && it.text == entry.text && it.filePath == entry.filePath
        }
        items.add(0, entry)
        while (items.size > MAX) {
            items.removeAt(items.size - 1)
        }
        save()
    }

    fun clear() {
        items.clear()
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                items.add(ClipEntry.fromJson(array.getJSONObject(i)))
            }
        }
    }

    private fun save() {
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        }
    }

    companion object {
        private const val FILE_NAME = "clip_history.json"
        const val MAX = 50
    }
}
