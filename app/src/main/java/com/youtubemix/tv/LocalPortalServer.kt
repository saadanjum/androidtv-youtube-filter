package com.youtubemix.tv

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

class LocalPortalServer(
    private val context: Context,
    private val repository: FilterConfigRepository,
    private val port: Int = 8080
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        executor.execute {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                while (running) {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                }
            } catch (_: Exception) {
                // Socket closed or port in use
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val out = sock.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.ROOT)
            val path = parts[1].substringBefore("?")

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            val bodyChars = CharArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                if (read == -1) break
                totalRead += read
            }
            val bodyString = String(bodyChars, 0, totalRead)

            // Serve portal.html on GET /
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                val html = context.assets.open("portal.html").bufferedReader().use { it.readText() }
                sendResponse(out, 200, "text/html; charset=utf-8", html)
                return
            }

            // All /api/* endpoints require valid X-Parent-Pin header
            if (path.startsWith("/api/")) {
                val providedPin = headers["x-parent-pin"] ?: ""
                if (providedPin != repository.parentPin) {
                    val err = JSONObject().put("error", "Invalid PIN").toString()
                    sendResponse(out, 401, "application/json; charset=utf-8", err)
                    return
                }

                when {
                    method == "GET" && path == "/api/status" -> {
                        val json = repository.toPortalStatusJson().toString()
                        sendResponse(out, 200, "application/json; charset=utf-8", json)
                    }

                    method == "POST" && path == "/api/list" -> {
                        val payload = JSONObject(bodyString)
                        val action = payload.optString("action")
                        val listName = payload.optString("list")
                        val value = payload.optString("value")

                        if (action == "add") {
                            when (listName) {
                                "titleKeywords" -> repository.addTitleKeyword(value)
                                "transcriptPhrases" -> repository.addTranscriptPhrase(value)
                                "blockedChannels" -> repository.addBlockedChannel(value)
                            }
                        } else if (action == "remove") {
                            when (listName) {
                                "titleKeywords" -> repository.removeTitleKeyword(value)
                                "transcriptPhrases" -> repository.removeTranscriptPhrase(value)
                                "blockedChannels" -> repository.removeBlockedChannel(value)
                            }
                        }
                        val ok = JSONObject().put("ok", true).toString()
                        sendResponse(out, 200, "application/json; charset=utf-8", ok)
                    }

                    method == "POST" && path == "/api/settings" -> {
                        val payload = JSONObject(bodyString)
                        if (payload.has("hideShorts")) {
                            repository.hideShorts = payload.getBoolean("hideShorts")
                        }
                        if (payload.has("blockCensoredCaptions")) {
                            repository.blockCensoredCaptions = payload.getBoolean("blockCensoredCaptions")
                        }
                        if (payload.has("timerEnabled")) {
                            repository.timerEnabled = payload.getBoolean("timerEnabled")
                        }
                        if (payload.has("newPin")) {
                            repository.parentPin = payload.getString("newPin")
                        }
                        val ok = JSONObject().put("ok", true).toString()
                        sendResponse(out, 200, "application/json; charset=utf-8", ok)
                    }

                    method == "POST" && path == "/api/timer" -> {
                        val payload = JSONObject(bodyString)
                        when (payload.optString("action")) {
                            "set_limit" -> {
                                val mins = payload.optInt("minutes", 60)
                                repository.timerLimitMinutes = mins
                            }
                            "grant" -> {
                                val mins = payload.optInt("minutes", 15)
                                repository.grantBonusMinutes(mins)
                            }
                            "unlock_today" -> {
                                repository.unlockedForToday = true
                            }
                            "lock_now" -> {
                                repository.unlockedForToday = false
                                repository.remainingSeconds = 0L
                                repository.timerEnabled = true
                            }
                        }
                        val ok = JSONObject().put("ok", true).toString()
                        sendResponse(out, 200, "application/json; charset=utf-8", ok)
                    }

                    else -> {
                        sendResponse(out, 404, "application/json", "{\"error\":\"Not found\"}")
                    }
                }
                return
            }

            sendResponse(out, 404, "text/plain", "Not Found")
        }
    }

    private fun sendResponse(out: OutputStream, statusCode: Int, contentType: String, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
