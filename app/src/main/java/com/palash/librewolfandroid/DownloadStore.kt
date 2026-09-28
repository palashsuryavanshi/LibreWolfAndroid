package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * Persistent download queue. Unlike android.app.DownloadManager this gives the
 * app a portable pause/resume API: the HTTP Range offset lives here, so a paused
 * download continues from the exact byte after a restart.
 */
class DownloadStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<DownloadTask> {
        val out = mutableListOf<DownloadTask>()
        val raw = prefs.getString(K_LIST, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { out.add(DownloadTask.fromJson(o)) }
        }
        return out
    }

    @Synchronized
    fun get(id: String): DownloadTask? = all().find { it.id == id }

    @Synchronized
    fun upsert(task: DownloadTask) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == task.id }
        if (index >= 0) list[index] = task else list.add(0, task)
        save(list)
    }

    @Synchronized
    fun update(id: String, block: (DownloadTask) -> Unit) {
        val task = get(id) ?: return
        block(task)
        upsert(task)
    }

    @Synchronized
    fun remove(id: String) = save(all().filter { it.id != id })

    @Synchronized
    fun clearFinished() = save(all().filter { !it.state.isFinished })

    @Synchronized
    fun clearAll() = save(emptyList())

    private fun save(tasks: List<DownloadTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        prefs.edit { putString(K_LIST, arr.toString()) }
    }

    companion object {
        private const val PREFS = "librewolf_downloads"
        private const val K_LIST = "tasks"
    }
}
