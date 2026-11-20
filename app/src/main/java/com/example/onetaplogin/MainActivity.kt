package com.example.onetaplogin

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val csvPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCsv(uri)
    }

    private var unlocked = false
    private var promptShowing = false
    private var statusView: TextView? = null

    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            // На Android 9-10 комбинация STRONG + DEVICE_CREDENTIAL не поддерживается
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLockScreen()
    }

    override fun onStart() {
        super.onStart()
        if (!unlocked) authenticate()
    }

    override fun onStop() {
        super.onStop()
        // Блокируем снова, когда пользователь уходит из приложения
        if (!isChangingConfigurations) {
            unlocked = false
            showLockScreen()
        }
    }

    private fun showLockScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF101522.toInt())
        }
        root.addView(TextView(this).apply {
            text = "OneTap Login"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        })
        val status = TextView(this).apply {
            text = "Приложение заблокировано"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFADB8CC.toInt())
            setPadding(0, 12, 0, 32)
        }
        statusView = status
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Войти по биометрии"
            setOnClickListener { authenticate() }
        })
        root.addView(Button(this).apply {
            text = "Настройки безопасности Android"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
        })
        setContentView(root)
    }

    private fun authenticate() {
        if (promptShowing || unlocked) return

        when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                statusView?.text = "Добавь отпечаток пальца или установи PIN/пароль экрана блокировки в настройках Android"
                return
            }
            else -> {
                statusView?.text = "Биометрия или блокировка экрана недоступны на этом устройстве"
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    unlocked = true
                    render()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                    statusView?.text = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> "Доступ не подтверждён. Нажми «Войти по биометрии»"
                        else -> errString.toString()
                    }
                }
            })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Разблокировка OneTap Login")
            .setSubtitle("Приложи палец или введи PIN-код телефона")
            .setAllowedAuthenticators(authenticators)
            .build()

        promptShowing = true
        prompt.authenticate(info)
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 24)
            setBackgroundColor(0xFF101522.toInt())
        }
        root.addView(TextView(this).apply {
            text = "OneTap Login"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Твой центр входа в приложения"
            textSize = 14f
            setTextColor(0xFFADB8CC.toInt())
            setPadding(0, 4, 0, 24)
        })

        EntryStore.load(this).forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 14, 18, 14)
                setBackgroundColor(0xFF202A3B.toInt())
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = entry.name
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
            })
            labels.addView(TextView(this).apply {
                text = if (entry.isSite) "Сайт · " + Entry.hostOf(entry.target) else "Приложение"
                textSize = 12f
                setTextColor(0xFFADB8CC.toInt())
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "Открыть"
                setOnClickListener { open(entry) }
            })
            val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
            root.addView(row, params)
            row.setOnLongClickListener {
                showEntryMenu(entry)
                true
            }
        }

        root.addView(Button(this).apply {
            text = "+ Добавить приложение или сайт"
            setOnClickListener { chooseKindToAdd() }
        })
        root.addView(Button(this).apply {
            text = "Импорт паролей из Google (CSV)"
            setOnClickListener { confirmImport() }
        })
        root.addView(Button(this).apply {
            text = "Настроить автозаполнение Android"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                }.onFailure {
                    Toast.makeText(this@MainActivity,
                        "Открой настройки Android → Пароли и автозаполнение",
                        Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "Удерживай карточку, чтобы сохранить логин и пароль или удалить запись. " +
                "Для сайтов автозаполнение работает в браузерах, которые его поддерживают."
            textSize = 12f
            setTextColor(0xFFADB8CC.toInt())
            setPadding(0, 16, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun open(entry: Entry) {
        if (entry.isSite) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(entry.target)))
            }.onFailure {
                Toast.makeText(this, "Не удалось открыть сайт", Toast.LENGTH_LONG).show()
            }
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(entry.target)
        if (intent == null) {
            Toast.makeText(this, "${entry.name} не найдено. Проверь, что приложение установлено.", Toast.LENGTH_LONG).show()
        } else startActivity(intent)
    }

    private fun showEntryMenu(entry: Entry) {
        android.app.AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf("Логин и пароль", "Изменить название и адрес", "Удалить")) { _, which ->
                when (which) {
                    0 -> editCredentials(entry)
                    1 -> editEntry(entry)
                    else -> confirmRemove(entry)
                }
            }
            .show()
    }

    private fun editEntry(entry: Entry) {
        val name = EditText(this).apply { hint = "Название"; setText(entry.name) }
        val url = EditText(this).apply {
            hint = "Адрес сайта"
            setText(entry.target)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 4)
            addView(name)
            if (entry.isSite) addView(url) // у приложения меняется только название
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Изменить ${entry.name}")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                val target = if (entry.isSite) Entry.normalizeUrl(url.text.toString()) else entry.target
                if (entry.isSite && url.text.isBlank()) return@setPositiveButton
                val title = name.text.toString().trim().ifBlank {
                    if (entry.isSite) Entry.hostOf(target) else entry.name
                }
                val updated = Entry(title, entry.kind, target)
                if (EntryStore.update(this, entry, updated)) render()
                else Toast.makeText(this, "Такой сайт уже есть в списке", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmRemove(entry: Entry) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Удалить ${entry.name}?")
            .setMessage("Сохранённые логин и пароль тоже будут удалены.")
            .setPositiveButton("Удалить") { _, _ ->
                EntryStore.remove(this, entry)
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseKindToAdd() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Что добавить?")
            .setItems(arrayOf("Приложение из установленных", "Сайт")) { _, which ->
                if (which == 0) pickInstalledApp() else addSite()
            }
            .show()
    }

    private fun pickInstalledApp() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { Entry(it.loadLabel(packageManager).toString(), Entry.KIND_APP, it.activityInfo.packageName) }
            .filter { it.target != packageName }
            .distinctBy { it.target }
            .sortedBy { it.name.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Не удалось получить список приложений", Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Выбери приложение")
            .setItems(apps.map { it.name }.toTypedArray()) { _, which ->
                EntryStore.add(this, apps[which])
                render()
            }
            .show()
    }

    private fun addSite() {
        val name = EditText(this).apply { hint = "Название (например, GitHub)" }
        val url = EditText(this).apply {
            hint = "Адрес (например, github.com)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 4)
            addView(name); addView(url)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Добавить сайт")
            .setView(layout)
            .setPositiveButton("Добавить") { _, _ ->
                val address = url.text.toString().trim()
                if (address.isBlank()) return@setPositiveButton
                val full = Entry.normalizeUrl(address)
                val title = name.text.toString().trim().ifBlank { Entry.hostOf(full) }
                EntryStore.add(this, Entry(title, Entry.KIND_SITE, full))
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun editCredentials(entry: Entry) {
        val existing = CredentialStore.get(this, entry.key)
        val user = EditText(this).apply {
            hint = "Логин / email"
            setText(existing?.first.orEmpty())
        }
        val pass = EditText(this).apply {
            hint = "Пароль"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 4)
            addView(user); addView(pass)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Сохранить ${entry.name}")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                if (user.text.isNotBlank() && pass.text.isNotBlank()) {
                    CredentialStore.save(this, entry.key, user.text.toString(), pass.text.toString())
                    Toast.makeText(this, "Данные сохранены в зашифрованном хранилище", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmImport() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Импорт из Google")
            .setMessage(
                "1. Откройте passwords.google.com → Настройки → Экспорт паролей.\n" +
                "2. Сохраните CSV-файл на телефон.\n" +
                "3. Выберите его здесь.\n\n" +
                "Файл содержит пароли в открытом виде: после импорта приложение попробует удалить его, " +
                "но проверьте это сами."
            )
            .setPositiveButton("Выбрать файл") { _, _ -> csvPicker.launch(arrayOf("*/*")) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun importCsv(uri: android.net.Uri) {
        Thread {
            val result = runCatching {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                GoogleImport.run(this, text)
            }
            val deleted = result.isSuccess && runCatching {
                android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
            }.getOrDefault(false)
            runOnUiThread {
                result.onSuccess {
                    val msg = "Добавлено записей: ${it.entries}\nСохранено паролей: ${it.credentials}\n" +
                        "Пропущено: ${it.skipped}\n" +
                        if (deleted) "CSV-файл удалён." else "Удалите CSV-файл вручную: в нём открытые пароли."
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Импорт завершён")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                    if (unlocked) render()
                }.onFailure {
                    Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
