package com.youtubemix.tv

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.content.Context

class FilterJsBridge(
    private val context: Context,
    private val repository: FilterConfigRepository,
    private val onTranscriptViolation: (videoTitle: String, matchedPhrase: String) -> Unit,
    private val onExitApp: () -> Unit,
    private val onStartVoiceSearch: () -> Unit,
    private val onStopVoiceSearch: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun getFilterConfig(): String {
        return repository.toJsConfigJson()
    }

    @JavascriptInterface
    fun reportSeenChannel(channelName: String, videoTitle: String) {
        repository.recordSeenChannel(channelName, videoTitle)
    }

    @JavascriptInterface
    fun reportBlockedItem(type: String, videoTitle: String, channelName: String, matchedRule: String) {
        repository.recordBlockedEvent(type, videoTitle, channelName, matchedRule)
    }

    @JavascriptInterface
    fun notifyTranscriptBlocked(videoTitle: String, channelName: String, matchedPhrase: String) {
        repository.recordBlockedEvent("TRANSCRIPT_PHRASE", videoTitle, channelName, matchedPhrase)
        mainHandler.post {
            Toast.makeText(
                context,
                "Skipped video: contains blocked language (\"$matchedPhrase\")",
                Toast.LENGTH_LONG
            ).show()
            onTranscriptViolation(videoTitle, matchedPhrase)
        }
    }

    @JavascriptInterface
    fun closeApp() {
        mainHandler.post {
            onExitApp()
        }
    }

    @JavascriptInterface
    fun startNativeVoiceSearch() {
        mainHandler.post {
            onStartVoiceSearch()
        }
    }

    @JavascriptInterface
    fun stopNativeVoiceSearch() {
        mainHandler.post {
            onStopVoiceSearch()
        }
    }
}
