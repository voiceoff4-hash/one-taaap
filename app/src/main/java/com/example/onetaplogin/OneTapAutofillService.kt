package com.example.onetaplogin

import android.app.assist.AssistStructure
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.*
import android.text.InputType
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

class OneTapAutofillService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null); return
        }

        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var lastTextFieldId: AutofillId? = null // кандидат в логин, если нет подсказок
        var webHost: String? = null

        walk(structure) { node ->
            node.webDomain?.takeIf { it.isNotBlank() }?.let {
                if (webHost == null) webHost = it.lowercase().removePrefix("www.")
            }
            val id = node.autofillId ?: return@walk
            val hints = node.autofillHints?.toList() ?: emptyList()
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val isTextClass = (node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
            val isPassword = hints.contains("password") ||
                (isTextClass && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                node.htmlInfo?.attributes?.any { it.first == "type" && it.second == "password" } == true

            when {
                isPassword -> if (passwordId == null) passwordId = id
                hints.any { it == "username" || it == "emailAddress" } -> usernameId = id
                isTextClass && node.isFocusable && passwordId == null -> lastTextFieldId = id
            }
        }
        if (usernameId == null && passwordId != null) usernameId = lastTextFieldId

        val credentials = findCredentials(structure, webHost) ?: run {
            callback.onSuccess(null); return
        }

        if (usernameId == null && passwordId == null) {
            callback.onSuccess(null); return
        }

        val presentation = RemoteViews(this@OneTapAutofillService.packageName, R.layout.autofill_dataset).apply {
            setTextViewText(R.id.autofill_title, "OneTap Login")
        }
        val dataset = Dataset.Builder(presentation).apply {
            usernameId?.let { setValue(it, AutofillValue.forText(credentials.first)) }
            passwordId?.let { setValue(it, AutofillValue.forText(credentials.second)) }
        }.build()
        callback.onSuccess(FillResponse.Builder().addDataset(dataset).build())
    }

    /** Сначала ищем по домену сайта (если запрос из браузера), затем по package name приложения. */
    private fun findCredentials(structure: AssistStructure, host: String?): Pair<String, String>? {
        if (host != null) {
            EntryStore.load(this).filter { it.isSite }.forEach { entry ->
                val entryHost = Entry.hostOf(entry.target)
                if (host == entryHost || host.endsWith(".$entryHost")) {
                    CredentialStore.get(this, entry.key)?.let { return it }
                }
            }
            return null
        }
        val pkg = structure.activityComponent?.packageName ?: return null
        return CredentialStore.get(this, pkg)
    }

    private fun walk(structure: AssistStructure, action: (AssistStructure.ViewNode) -> Unit) {
        fun visit(node: AssistStructure.ViewNode) {
            action(node)
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(i).rootViewNode)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }
}
