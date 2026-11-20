package com.example.onetaplogin

import android.content.Context

/** Импорт CSV-файла, выгруженного из Google Password Manager / Chrome (name,url,username,password,note). */
object GoogleImport {
    data class Result(val entries: Int, val credentials: Int, val skipped: Int)

    private val androidUrl = Regex("^android://[^@]*@([^/]+)/?$")

    fun run(context: Context, text: String): Result {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return Result(0, 0, 0)
        val header = rows[0].map { it.trim().lowercase() }
        val iName = header.indexOf("name")
        val iUrl = header.indexOf("url")
        val iUser = header.indexOf("username").takeIf { it >= 0 } ?: header.indexOf("login")
        val iPass = header.indexOf("password")
        if (iUrl < 0 || iUser < 0 || iPass < 0) return Result(0, 0, rows.size - 1)

        val existing = EntryStore.load(context).map { it.key }.toMutableSet()
        var entries = 0
        var credentials = 0
        var skipped = 0
        for (row in rows.drop(1)) {
            fun col(i: Int) = if (i in row.indices) row[i].trim() else ""
            val url = col(iUrl)
            val user = col(iUser)
            val pass = col(iPass)
            if (url.isBlank() || user.isBlank() || pass.isBlank()) { skipped++; continue }

            val pkg = androidUrl.find(url)?.groupValues?.get(1)
            val entry = if (pkg != null) {
                Entry(col(iName).ifBlank { pkg }, Entry.KIND_APP, pkg)
            } else {
                val full = Entry.normalizeUrl(url)
                Entry(col(iName).ifBlank { Entry.hostOf(full) }, Entry.KIND_SITE, full)
            }
            if (existing.add(entry.key)) {
                EntryStore.add(context, entry)
                entries++
            }
            // уже сохранённые вручную данные не перезаписываем
            if (CredentialStore.get(context, entry.key) == null) {
                CredentialStore.save(context, entry.key, user, pass)
                credentials++
            } else skipped++
        }
        return Result(entries, credentials, skipped)
    }

    fun parseCsv(raw: String): List<List<String>> {
        val text = raw.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = false
                } else sb.append(c)
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(sb.toString()); sb.setLength(0) }
                    '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = mutableListOf() }
                    '\r' -> Unit
                    else -> sb.append(c)
                }
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows.filter { it.size > 1 || (it.size == 1 && it[0].isNotBlank()) }
    }
}
