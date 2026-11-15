package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E17"))
            setPadding(56, 110, 56, 56)
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        }

        statusView = TextView(this).apply {
            text = "Статус: ${JarvisService.statusText}"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 48)
        }

        val keyLabel = TextView(this).apply {
            text = "Ключ OpenRouter (sk-or-...):"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
        }

        val keyInput = EditText(this).apply {
            setText(prefs.getString("api_key", ""))
            hint = "sk-or-v1-..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val modelLabel = TextView(this).apply {
            text = "Модель OpenRouter:"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }

        val modelInput = EditText(this).apply {
            setText(prefs.getString("model_name", "openrouter/free"))
            setTextColor(Color.WHITE)
        }

        val saveBtn = Button(this).apply {
            text = "Сохранить настройки"
            setBackgroundColor(Color.parseColor("#1E3A8A"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                prefs.edit()
                    .putString("api_key", keyInput.text.toString().trim())
                    .putString("model_name", modelInput.text.toString().trim().ifEmpty { "openrouter/free" })
                    .apply()
                Toast.makeText(this@MainActivity, "Настройки сохранены, сэр", Toast.LENGTH_SHORT).show()
            }
        }

        val startBtn = Button(this).apply {
            text = "▶ Запустить фонового Джарвиса"
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (checkAndRequestPermissions()) {
                    val intent = Intent(this@MainActivity, JarvisService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    statusView.text = "Статус: Запускается (проверьте шторку уведомлений)..."
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "⏹ Остановить службу"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, JarvisService::class.java))
                statusView.text = "Статус: Остановлен"
            }
        }

        val hintText = TextView(this).apply {
            text = "\nКак пользоваться поверх игр:\n1. Нажмите «Запустить фонового Джарвиса».\n2. При первом запуске он за 10 сек скачает русскую модель речи (45 МБ) и скажет «Все системы Джарвиса активны, сэр».\n3. Можно сворачивать приложение и заходить в игру: просто скажите «Джарвис, включи фонарик» или «Джарвис, напиши [имя] в Макс привет»."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(keyLabel)
        root.addView(keyInput)
        root.addView(modelLabel)
        root.addView(modelInput)
        root.addView(saveBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(hintText)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        statusView.text = "Статус: ${JarvisService.statusText}"
    }

    private fun checkAndRequestPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) {
            true
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            Toast.makeText(this, "Разрешите микрофон и нажмите «Запустить» ещё раз", Toast.LENGTH_LONG).show()
            false
        }
    }
}
