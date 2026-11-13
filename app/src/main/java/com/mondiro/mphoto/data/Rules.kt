package com.mondiro.mphoto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Chuyến đi: album gom ảnh chụp từ [start] tới [end] (end = 0 là đang diễn ra). */
data class Trip(val name: String, val start: Long, val end: Long = 0) {
    val key: String get() = "Chuyến đi/$name"
    val active: Boolean get() = end == 0L
}

/** Rule người dùng thêm: include = true nghĩa là "Không loại trừ" (ưu tiên hơn mọi rule loại trừ). */
data class UserRule(val pattern: String, val include: Boolean)

/** Rule loại trừ mặc định của hệ thống, bật/tắt từng cái được. */
data class SysRule(val id: String, val title: String, val patterns: List<String>)

object Rules {
    val SYSTEM = listOf(
        SysRule("android_data", "Android/data", listOf("Android/data")),
        SysRule("thumbs", "Ảnh thu nhỏ (.thumbnails)", listOf(".thumbnails", "*thumbnail*", "thumbs", ".thumbs")),
        SysRule("cache", "Cache, file tạm", listOf("cache", ".cache", "*_cache", "temp", ".temp", "tmp", ".tmp", ".trash")),
        SysRule("stickers", "Sticker của app chat", listOf("*sticker*")),
        SysRule("emoji", "Emoji, biểu cảm", listOf("*emoji*", "*emoticon*", "*gif_panel*")),
        SysRule("icons", "Icon, avatar, ảnh khởi động app", listOf("icon", "icons", ".icons", "*avatar*", "*splash*")),
    )

    private val regexCache = HashMap<String, Regex>()

    private fun glob(p: String): Regex = regexCache.getOrPut(p) {
        val sb = StringBuilder("^")
        for (ch in p.lowercase()) when (ch) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(ch.toString()))
        }
        sb.append('$')
        Regex(sb.toString())
    }

    /**
     * Cú pháp mẫu:
     *  - có dấu "/"  -> khớp đường dẫn từ gốc (thư mục đó và mọi thư mục con). Vd: Android/media/com.zing.zalo
     *  - "* /tên" hoặc không có "/" -> khớp mọi thư mục có tên đó ở bất kỳ cấp nào. Vd: Stickers, *sticker*
     *  - dấu * thay cho chuỗi bất kỳ.
     */
    fun matches(pattern: String, path: String): Boolean {
        var p = pattern.trim().trim('/')
        if (p.isEmpty()) return false
        if (p.startsWith("*/")) p = p.substring(2)
        val low = path.lowercase()
        return if (p.contains('/')) {
            val segs = p.lowercase().split('/')
            val parts = low.split('/')
            if (parts.size < segs.size) false
            else segs.indices.all { glob(segs[it]).matches(parts[it]) }
        } else {
            val g = glob(p)
            low.split('/').any { g.matches(it) }
        }
    }

    fun splitPatterns(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun isExcluded(path: String, user: List<UserRule>, sysOff: Set<String>, auto: Boolean): Boolean {
        for (r in user) if (r.include && splitPatterns(r.pattern).any { matches(it, path) }) return false
        for (r in user) if (!r.include && splitPatterns(r.pattern).any { matches(it, path) }) return true
        if (auto) for (s in SYSTEM) if (s.id !in sysOff && s.patterns.any { matches(it, path) }) return true
        return false
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mphoto", Context.MODE_PRIVATE)

    private fun set(key: String) = sp.getStringSet(key, emptySet())!!.toSet()
    private fun putSet(key: String, v: Set<String>) = sp.edit().putStringSet(key, v).apply()

    var pinned: Set<String>
        get() = set("pinned")
        set(v) = putSet("pinned", v)
    var hidden: Set<String>
        get() = set("hidden")
        set(v) = putSet("hidden", v)
    var gsync: Set<String>
        get() = set("gsync")
        set(v) = putSet("gsync", v)
    var sysOff: Set<String>
        get() = set("sysOff")
        set(v) = putSet("sysOff", v)
    var autoExclude: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()
    var defaultTab: String
        get() = sp.getString("defaultTab", "album")!!
        set(v) = sp.edit().putString("defaultTab", v).apply()

    var panSpeed: Float
        get() = sp.getFloat("panSpeed", 1.5f)
        set(v) = sp.edit().putFloat("panSpeed", v).apply()
    var scrollSpeed: Float
        get() = sp.getFloat("scrollSpeed", 2f)
        set(v) = sp.edit().putFloat("scrollSpeed", v).apply()
    /** Cỡ ô Album: 0 nhỏ, 1 vừa, 2 to. */
    var albumSize: Int
        get() = sp.getInt("albumSize", 2)
        set(v) = sp.edit().putInt("albumSize", v).apply()

    var trips: List<Trip>
        get() = try {
            val a = JSONArray(sp.getString("trips", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Trip(o.getString("n"), o.getLong("s"), o.optLong("e", 0)) } }
        } catch (e: Exception) { emptyList() }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("n", it.name).put("s", it.start).put("e", it.end)) }
            sp.edit().putString("trips", a.toString()).apply()
        }

    var rules: List<UserRule>
        get() = try {
            val a = JSONArray(sp.getString("rules", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> UserRule(o.getString("p"), o.getBoolean("i")) } }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("p", it.pattern).put("i", it.include)) }
            sp.edit().putString("rules", a.toString()).apply()
        }
}
