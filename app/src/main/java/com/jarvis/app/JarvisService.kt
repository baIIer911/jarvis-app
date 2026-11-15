package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class JarvisService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isTorchOn = false
    private var awaitingCommand = false
    private var lastCommandTime = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()

    companion object {
        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIF_ID = 101
        var isRunning = false
        var statusText = "Остановлен"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Загрузка голосового ядра..."))
        tts = TextToSpeech(this, this)

        val sysPrompt = "Ты — Джарвис, персональный ИИ-ассистент на телефоне пользователя. Отвечай кратко, по делу и называй пользователя «сэр». " +
            "Если пользователь просит выполнить действие на телефоне, ответь строго в формате JSON (без лишнего текста вокруг): " +
            "1. Позвонить: {\"action\": \"call\", \"contact\": \"имя или номер\", \"reply\": \"Набираю контакт, сэр.\"} " +
            "2. Написать сообщение в MAX: {\"action\": \"message\", \"contact\": \"имя\", \"text\": \"текст сообщения\", \"reply\": \"Отправляю сообщение через MAX, сэр.\"} " +
            "3. Написать сообщение в Telegram: {\"action\": \"telegram\", \"contact\": \"имя\", \"text\": \"текст сообщения\", \"reply\": \"Отправляю сообщение в Telegram, сэр.\"} " +
            "4. Включить или выключить VPN: {\"action\": \"vpn\", \"reply\": \"Переключаю VPN, сэр.\"} " +
            "5. Включить или выключить фонарик: {\"action\": \"flashlight\", \"reply\": \"Переключаю фонарик, сэр.\"} " +
            "6. Найти в интернете: {\"action\": \"history\", \"query\": \"поисковый запрос\", \"reply\": \"Ищу информацию, сэр.\"} " +
            "7. Открыть сайт, Яндекс, Гугл или YouTube: {\"action\": \"url\", \"url\": \"https://...\", \"reply\": \"Открываю, сэр.\"} " +
            "Если это обычный разговор или вопрос — отвечай коротким текстом для озвучки вслух."
        history.add(JSONObject().put("role", "system").put("content", sysPrompt))

        scope.launch { initVoskModel() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale("ru", "RU"))
            ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setPitch(0.95f)
            tts?.setSpeechRate(1.05f)
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    private suspend fun initVoskModel() {
        try {
            val modelDir = File(filesDir, "vosk-model-small-ru-0.22")
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                updateNotif("Скачивание русской речи (45 МБ)...")
                val zipFile = File(cacheDir, "vosk-ru.zip")
                URL("https://alphacephei.com").openStream().use { input ->
                    FileOutputStream(zipFile).use { output -> input.copyTo(output) }
                }
                updateNotif("Распаковка голосовой модели...")
                ZipInputStream(zipFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(filesDir, entry.name)
                        if (entry.isDirectory) outFile.mkdirs() else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                zipFile.delete()
            }
            model = Model(modelDir.absolutePath)
            withContext(Dispatchers.Main) {
                val rec = Recognizer(model, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this@JarvisService)
                updateNotif("Слушаю в фоне • Скажите «Джарвис»")
                speak("Все системы Джарвиса активны, сэр.")
            }
        } catch (e: Exception) {
            updateNotif("Ошибка запуска речи: ${e.message}")
        }
    }
    override fun onPartialResult(hypothesis: String?) {
        val text = parseVoskText(hypothesis, "partial")
        if (!awaitingCommand && containsWakeWord(text)) {
            awaitingCommand = true
            lastCommandTime = System.currentTimeMillis()
            updateNotif("Слушаю команду, сэр...")
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = parseVoskText(hypothesis, "text")
        if (text.isBlank()) return
        if (containsWakeWord(text)) {
            val cleaned = removeWakeWord(text)
            if (cleaned.length >= 2) {
                awaitingCommand = false
                updateNotif("Обработка: $cleaned")
                scope.launch { processCommand(cleaned) }
            } else {
                awaitingCommand = true
                lastCommandTime = System.currentTimeMillis()
                speak("Да, сэр?")
                updateNotif("Жду команду, сэр...")
            }
        } else if (awaitingCommand) {
            awaitingCommand = false
            if (System.currentTimeMillis() - lastCommandTime < 15000) {
                updateNotif("Обработка: $text")
                scope.launch { processCommand(text) }
            } else updateNotif("Слушаю в фоне • Скажите «Джарвис»")
        }
    }

    override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }
    override fun onError(exception: Exception?) { updateNotif("Ошибка микрофона: ${exception?.message}") }
    override fun onTimeout() {}

    private fun parseVoskText(jsonStr: String?, key: String): String =
        if (jsonStr.isNullOrBlank()) "" else try { JSONObject(jsonStr).optString(key, "").trim().lowercase() } catch (_: Exception) { "" }

    private fun containsWakeWord(text: String): Boolean =
        text.contains("джарвис") || text.contains("джарвиз") || text.contains("jarvis")

    private fun removeWakeWord(text: String): String =
        text.replace("джарвис", "").replace("джарвиз", "").replace("jarvis", "").trim()

    private suspend fun processCommand(userText: String) {
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "")?.trim() ?: ""
        val primaryModel = prefs.getString("model_name", "openrouter/free")?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            speak("Сэр, сначала введите ключ OpenRouter в приложении.")
            updateNotif("Нужен ключ OpenRouter")
            return
        }
        history.add(JSONObject().put("role", "user").put("content", userText))
        val models = listOf(primaryModel.ifEmpty { "openrouter/free" }, "openrouter/free", "meta-llama/llama-3.3-70b-instruct:free", "google/gemma-3-27b-it:free").distinct()
        var rawReply = ""
        for (m in models) {
            try {
                val messagesArr = JSONArray()
                history.takeLast(10).forEach { messagesArr.put(it) }
                val bodyJson = JSONObject().put("model", m).put("messages", messagesArr)
                val req = Request.Builder()
                    .url("https://openrouter.ai")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://github.com")
                    .addHeader("X-Title", "Jarvis Native Android")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && bodyStr.isNotBlank()) {
                        val choices = JSONObject(bodyStr).optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val content = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim()
                            if (content.isNotEmpty()) rawReply = content
                        }
                    }
                }
                if (rawReply.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        if (rawReply.isEmpty()) {
            speak("Сбой связи с сервером, сэр.")
            updateNotif("Слушаю в фоне • Скажите «Джарвис»")
            return
        }
        history.add(JSONObject().put("role", "assistant").put("content", rawReply))
        val fence = "\u0060\u0060\u0060"
        val cleanJson = rawReply.replace(fence + "json", "").replace(fence, "").trim()
        if (cleanJson.startsWith("{") && cleanJson.endsWith("}")) {
            try {
                val cmd = JSONObject(cleanJson)
                val action = cmd.optString("action")
                speak(cmd.optString("reply", "Выполняю, сэр."))
                when (action) {
                    "flashlight" -> toggleFlashlight()
                    "call" -> callMacrodroid("call", mapOf("contact" to cmd.optString("contact")))
                    "message" -> callMacrodroid("message", mapOf("contact" to cmd.optString("contact"), "text" to cmd.optString("text")))
                    "telegram" -> callMacrodroid("telegram", mapOf("contact" to cmd.optString("contact"), "text" to cmd.optString("text")))
                    "vpn" -> callMacrodroid("vpn", emptyMap())
                    "history" -> callMacrodroid("history", mapOf("query" to cmd.optString("query")))
                    "url" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cmd.optString("url", "https://yandex.ru"))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            } catch (_: Exception) { speak(rawReply) }
        } else speak(rawReply)
        updateNotif("Слушаю в фоне • Скажите «Джарвис»")
    }

    private fun toggleFlashlight() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cm.setTorchMode(cameraId, isTorchOn)
        } catch (_: Exception) {}
    }

    private fun callMacrodroid(endpoint: String, params: Map<String, String>) {
        try {
            val query = params.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
            val fullUrl = if (query.isEmpty()) "http://127.0.0" else "http://127.0.0?$query"
            client.newCall(Request.Builder().url(fullUrl).get().build()).execute().close()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Фоновая служба Джарвиса", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        statusText = text
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        isRunning = false
        statusText = "Остановлен"
        speechService?.stop(); speechService?.shutdown()
        tts?.stop(); tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
