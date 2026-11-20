package com.example.onetaplogin

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Запись в списке: приложение (target = package name) или сайт (target = URL). */
data class Entry(val name: String, val kind: String, val target: String) {
    val isSite: Boolean get() = kind == KIND_SITE

    /** Ключ, под которым лежат логин и пароль в зашифрованном хранилище. */
    val key: String get() = if (isSite) "web:" + hostOf(target) else target

    companion object {
        const val KIND_APP = "app"
        const val KIND_SITE = "site"

        fun hostOf(url: String): String =
            (Uri.parse(url).host ?: url).lowercase().removePrefix("www.")

        fun normalizeUrl(raw: String): String {
            val t = raw.trim()
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }
}

object EntryStore {
    private const val PREFS = "entries"
    private const val KEY = "list"

    private val defaults = listOf(
        Entry("Telegram", Entry.KIND_APP, "org.telegram.messenger"),
        Entry("VK", Entry.KIND_APP, "com.vkontakte.android"),
        Entry("Google", Entry.KIND_APP, "com.google.android.googlequicksearchbox"),
        Entry("Steam", Entry.KIND_APP, "com.valvesoftware.android.steam.community")
    )

    fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getString("name"), o.getString("kind"), o.getString("target"))
            }
        }.getOrDefault(defaults)
    }

    private fun write(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("kind", it.kind).put("target", it.target))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, entry: Entry) {
        val list = load(context)
        if (list.none { it.key == entry.key }) write(context, list + entry)
    }

    /** Заменяет запись на месте. Логин и пароль переносятся, если ключ (домен) изменился. */
    fun update(context: Context, old: Entry, new: Entry): Boolean {
        val list = load(context)
        if (new.key != old.key && list.any { it.key == new.key }) return false
        write(context, list.map { if (it.key == old.key) new else it })
        if (new.key != old.key) {
            CredentialStore.get(context, old.key)?.let {
                CredentialStore.save(context, new.key, it.first, it.second)
            }
            CredentialStore.delete(context, old.key)
        }
        return true
    }

    fun remove(context: Context, entry: Entry) {
        write(context, load(context).filterNot { it.key == entry.key })
        CredentialStore.delete(context, entry.key)
    }
}
