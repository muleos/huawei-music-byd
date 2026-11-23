package com.sonance.player.data.huawei

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Two-level catalog cache: small in-memory LRU plus one JSON file per key under cacheDir. */
object HuaweiCache {

    private const val MAX_MEMORY = 60
    private const val MAX_FILES = 240

    private data class Entry(val value: String, val expiresAt: Long)

    private val memory = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_MEMORY
    }

    @Volatile
    private var directory: File? = null

    fun init(context: Context) {
        if (directory != null) return
        directory = File(context.applicationContext.cacheDir, "huawei-catalog").apply { mkdirs() }
    }

    fun get(key: String): String? {
        synchronized(memory) {
            val hit = memory[key]
            if (hit != null && hit.expiresAt > System.currentTimeMillis()) return hit.value
            if (hit != null) memory.remove(key)
        }
        val file = fileFor(key) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            if (o.optLong("expiresAt") <= System.currentTimeMillis()) {
                file.delete()
                null
            } else {
                o.optString("value").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun put(key: String, value: String, ttlMs: Long) {
        val expiresAt = System.currentTimeMillis() + ttlMs
        synchronized(memory) { memory[key] = Entry(value, expiresAt) }
        val dir = directory ?: return
        runCatching {
            fileFor(key)?.writeText(JSONObject().put("expiresAt", expiresAt).put("value", value).toString())
            trimFiles(dir)
        }
    }

    private fun trimFiles(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_FILES) return
        files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun fileFor(key: String): File? {
        val dir = directory ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }
}
