package com.youtubemix.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var timerLockOverlay: FrameLayout
    private lateinit var portalInfoBanner: TextView
    private lateinit var pinDotsView: TextView
    private lateinit var pinStatusView: TextView
    private lateinit var pinEntryContainer: LinearLayout
    private lateinit var timeExtendChoicesContainer: LinearLayout

    private lateinit var repository: FilterConfigRepository
    private var portalServer: LocalPortalServer? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var enteredPin = StringBuilder()
    private var filterEngineScript: String = ""

    private val configListener: () -> Unit = {
        mainHandler.post {
            pushConfigToWebView()
            evaluateTimerOverlayState()
        }
    }

    private val timerTickRunnable = object : Runnable {
        override fun run() {
            if (repository.timerEnabled && !repository.unlockedForToday) {
                val rem = repository.remainingSeconds
                if (rem > 0) {
                    repository.remainingSeconds = rem - 1
                }
            }
            evaluateTimerOverlayState()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        ensureMicrophonePermission()

        repository = FilterConfigRepository(applicationContext)
        repository.addListener(configListener)

        filterEngineScript = assets.open("filter_engine.js").bufferedReader().use { it.readText() }

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        setupWebView()
        setupPortalInfoBanner()
        setupTimerLockOverlay()

        setContentView(rootLayout)

        portalServer = LocalPortalServer(applicationContext, repository, PORTAL_PORT).also {
            it.start()
        }

        showPortalBannerBriefly()
        mainHandler.postDelayed(timerTickRunnable, 1000L)
    }

    private fun ensureMicrophonePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingWebPermissionRequest?.let { req ->
                if (granted) {
                    req.grant(req.resources)
                } else {
                    req.deny()
                }
                pendingWebPermissionRequest = null
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        @Suppress("DEPRECATION")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            // Essential for 1080p TV WebView scaling
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Certified Android TV Cobalt User-Agent so YouTube enables the in-app Voice Search Mic icon
            userAgentString = SMART_TV_USER_AGENT
        }

        val bridge = FilterJsBridge(
            context = this,
            repository = repository,
            onTranscriptViolation = { _, _ ->
                // Trigger a Back key event to ensure YouTube TV exits the blocked video player
                webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            },
            onExitApp = {
                finish()
            },
            onStartVoiceSearch = {
                launchNativeVoiceRecognizer()
            },
            onStopVoiceSearch = {
                stopNativeVoiceRecognizer()
            }
        )
        webView.addJavascriptInterface(bridge, "YouTubeMixBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                filterEngineScript,
                setOf("https://*.youtube.com", "https://youtube.com")
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(filterEngineScript, null)
                pushConfigToWebView()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(filterEngineScript, null)
                pushConfigToWebView()
                CookieManager.getInstance().flush()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    val resources = request.resources ?: emptyArray()
                    if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (ensureMicrophonePermission()) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingWebPermissionRequest = request
                        }
                    } else {
                        request.grant(resources)
                    }
                }
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                finish()
            }
        }

        rootLayout.addView(webView)
        webView.loadUrl("https://www.youtube.com/tv")
        webView.requestFocus()
    }

    private fun setupPortalInfoBanner() {
        portalInfoBanner = TextView(this).apply {
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121826"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#00E5A3"))
            }
            background = bg
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(36, 20, 36, 20)
            text = "Parent Filter Portal: http://${getLocalIpAddress()}:$PORTAL_PORT  (PIN: ${repository.parentPin})"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 32
                rightMargin = 32
            }
        }
        rootLayout.addView(portalInfoBanner)
    }

    private fun showPortalBannerBriefly() {
        portalInfoBanner.text =
            "Parent Filter Portal: http://${getLocalIpAddress()}:$PORTAL_PORT"
        portalInfoBanner.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideBannerRunnable)
        mainHandler.postDelayed(hideBannerRunnable, 10000L)
    }

    private val hideBannerRunnable = Runnable {
        portalInfoBanner.visibility = View.GONE
    }

    private fun setupTimerLockOverlay() {
        timerLockOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F20B0F19"))
            visibility = View.GONE
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 48, 64, 48)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B2436"))
                cornerRadius = 32f
                setStroke(3, Color.parseColor("#00E5A3"))
            }
            layoutParams = FrameLayout.LayoutParams(
                900,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val title = TextView(this).apply {
            text = "TV Time is Finished for Now!"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Ask a parent to enter the 4-digit Parent PIN with the TV remote to add more time."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }

        // Container 1: 4-Digit On-Screen PIN Pad
        pinEntryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        pinDotsView = TextView(this).apply {
            text = "○  ○  ○  ○"
            setTextColor(Color.parseColor("#00E5A3"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 12)
        }

        pinStatusView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FF4B4B"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        val keypadGrid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "⌫")
        var firstKeyButton: Button? = null

        for (label in keys) {
            val btn = createTvButton(label, widthPx = 170, heightPx = 82) {
                onPinPadButtonClicked(label)
            }
            if (firstKeyButton == null) firstKeyButton = btn
            keypadGrid.addView(btn)
        }

        pinEntryContainer.addView(pinDotsView)
        pinEntryContainer.addView(pinStatusView)
        pinEntryContainer.addView(keypadGrid)

        // Container 2: Time Extension Options (shown after valid 4-digit PIN)
        timeExtendChoicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        val unlockedLabel = TextView(this).apply {
            text = "Parent PIN Verified — Choose Screen Time:"
            setTextColor(Color.parseColor("#00E5A3"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 20)
        }

        val choicesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btn15 = createTvButton("+15 Mins", widthPx = 220, heightPx = 90) {
            repository.grantBonusMinutes(15)
            evaluateTimerOverlayState()
        }
        val btn30 = createTvButton("+30 Mins", widthPx = 220, heightPx = 90) {
            repository.grantBonusMinutes(30)
            evaluateTimerOverlayState()
        }
        val btnUnlockToday = createTvButton("Unlock for Today", widthPx = 260, heightPx = 90) {
            repository.unlockedForToday = true
            evaluateTimerOverlayState()
        }

        choicesRow.addView(btn15)
        choicesRow.addView(btn30)
        choicesRow.addView(btnUnlockToday)

        timeExtendChoicesContainer.addView(unlockedLabel)
        timeExtendChoicesContainer.addView(choicesRow)

        val portalFooter = TextView(this).apply {
            text = "Or manage from phone: http://${getLocalIpAddress()}:$PORTAL_PORT"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        card.addView(title)
        card.addView(subtitle)
        card.addView(pinEntryContainer)
        card.addView(timeExtendChoicesContainer)
        card.addView(portalFooter)

        timerLockOverlay.addView(card)
        rootLayout.addView(timerLockOverlay)
    }

    private fun createTvButton(
        textLabel: String,
        widthPx: Int,
        heightPx: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = textLabel
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = true
            setTextColor(Color.WHITE)

            val normalBg = GradientDrawable().apply {
                setColor(Color.parseColor("#121826"))
                cornerRadius = 18f
                setStroke(2, Color.parseColor("#2A364F"))
            }
            val focusedBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5A3"))
                cornerRadius = 18f
                setStroke(4, Color.WHITE)
            }
            background = normalBg

            setOnFocusChangeListener { _, hasFocus ->
                background = if (hasFocus) focusedBg else normalBg
                setTextColor(if (hasFocus) Color.parseColor("#051912") else Color.WHITE)
            }

            setOnClickListener { onClick() }

            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx).apply {
                setMargins(10, 10, 10, 10)
            }
        }
    }

    private fun onPinPadButtonClicked(label: String) {
        when (label) {
            "Clear" -> {
                enteredPin.clear()
                pinStatusView.text = ""
            }
            "⌫" -> {
                if (enteredPin.isNotEmpty()) {
                    enteredPin.deleteCharAt(enteredPin.length - 1)
                }
                pinStatusView.text = ""
            }
            else -> {
                if (enteredPin.length < 4) {
                    enteredPin.append(label)
                }
            }
        }
        updatePinDots()

        if (enteredPin.length == 4) {
            val candidate = enteredPin.toString()
            if (candidate == repository.parentPin) {
                pinStatusView.text = ""
                pinEntryContainer.visibility = View.GONE
                timeExtendChoicesContainer.visibility = View.VISIBLE
                timeExtendChoicesContainer.getChildAt(1)?.let { row ->
                    if (row is ViewGroup && row.childCount > 0) {
                        row.getChildAt(0).requestFocus()
                    }
                }
            } else {
                pinStatusView.text = "Incorrect PIN. Try again."
                enteredPin.clear()
                updatePinDots()
            }
        }
    }

    private fun updatePinDots() {
        val filled = enteredPin.length
        val dots = (1..4).joinToString("  ") { idx -> if (idx <= filled) "●" else "○" }
        pinDotsView.text = dots
    }

    private fun evaluateTimerOverlayState() {
        val shouldLock =
            repository.timerEnabled && !repository.unlockedForToday && repository.remainingSeconds <= 0L

        if (shouldLock && timerLockOverlay.visibility != View.VISIBLE) {
            // Pause video inside WebView
            webView.evaluateJavascript(
                "document.querySelectorAll('video').forEach(v => v.pause());",
                null
            )
            enteredPin.clear()
            updatePinDots()
            pinStatusView.text = ""
            pinEntryContainer.visibility = View.VISIBLE
            timeExtendChoicesContainer.visibility = View.GONE
            timerLockOverlay.visibility = View.VISIBLE
            timerLockOverlay.requestFocus()
        } else if (!shouldLock && timerLockOverlay.visibility == View.VISIBLE) {
            timerLockOverlay.visibility = View.GONE
            webView.requestFocus()
        }
    }

    private fun pushConfigToWebView() {
        val cfgJson = repository.toJsConfigJson()
        val js = "if(window.__updateYouTubeMixConfig){window.__updateYouTubeMixConfig($cfgJson);}"
        webView.evaluateJavascript(js, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // If Screen-Time PIN Overlay is visible, support direct number keys on TV remote too
        if (timerLockOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 &&
                    pinEntryContainer.visibility == View.VISIBLE
                ) {
                    val digit = (event.keyCode - KeyEvent.KEYCODE_0).toString()
                    onPinPadButtonClicked(digit)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DEL && pinEntryContainer.visibility == View.VISIBLE) {
                    onPinPadButtonClicked("⌫")
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // Long-press Back or Menu key on TV remote shows the Phone Web Portal IP banner
        if (event.keyCode == KeyEvent.KEYCODE_MENU ||
            (event.keyCode == KeyEvent.KEYCODE_BACK && event.isLongPress)
        ) {
            showPortalBannerBriefly()
            Toast.makeText(
                this,
                "Parent Portal: http://${getLocalIpAddress()}:$PORTAL_PORT",
                Toast.LENGTH_LONG
            ).show()
            return true
        }

        // Map Android TV Back button to Escape key inside YouTube TV SPA
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun launchNativeVoiceRecognizer() {
        if (!ensureMicrophonePermission()) return

        try {
            stopNativeVoiceRecognizer()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    // Do NOT play a ToneGenerator beep over the TV speakers here,
                    // because the mic is already recording and the beep pollutes the first spoken word!
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    webView.evaluateJavascript(
                        "if(window.__onNativeVoiceEnded){window.__onNativeVoiceEnded();}",
                        null
                    )
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim() ?: ""
                    if (spokenText.isNotEmpty()) {
                        typeRecognizedQueryIntoWebView(spokenText)
                    } else {
                        webView.evaluateJavascript(
                            "if(window.__onNativeVoiceEnded){window.__onNativeVoiceEnded();}",
                            null
                        )
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                // Use Google's Cloud WEB_SEARCH language model (optimized for YouTube queries, names, and accents)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer.startListening(intent)
        } catch (_: Exception) {
            webView.evaluateJavascript(
                "if(window.__onNativeVoiceEnded){window.__onNativeVoiceEnded();}",
                null
            )
        }
    }

    private fun stopNativeVoiceRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
    }

    private fun typeRecognizedQueryIntoWebView(spokenText: String) {
        Toast.makeText(this, "Heard: \"$spokenText\"", Toast.LENGTH_SHORT).show()

        // 1. Stop the active JS recorder cleanly
        webView.evaluateJavascript(
            "if(window.__onNativeVoiceEnded){window.__onNativeVoiceEnded();}",
            null
        )

        // 2. Exit the red mic listening overlay (Escape) and wait for the Search keyboard to regain focus
        mainHandler.postDelayed({
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))

            // 3. Clear any previous partial letters by sending Backspace a few times, then type each character cleanly with a 25ms gap so YouTube TV's SPA never drops a letter or space
            mainHandler.postDelayed({
                val normalized = spokenText.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
                val keyCharacterMap = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)

                var delayOffset = 0L
                for (ch in normalized.toCharArray()) {
                    mainHandler.postDelayed({
                        if (ch == ' ') {
                            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
                            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
                        } else {
                            val events = keyCharacterMap.getEvents(charArrayOf(ch))
                            if (events != null) {
                                for (ev in events) {
                                    webView.dispatchKeyEvent(ev)
                                }
                            }
                        }
                    }, delayOffset)
                    delayOffset += 30L
                }
            }, 300L)
        }, 100L)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    override fun onPause() {
        repository.flushRemainingSeconds()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopNativeVoiceRecognizer()
        repository.flushRemainingSeconds()
        repository.removeListener(configListener)
        portalServer?.stop()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PORTAL_PORT = 8080
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_VOICE_SPEECH = 1002
        // Sony Bravia / Android TV Cobalt User-Agent so YouTube TV enables 1080p/4K + on-screen Microphone Voice Search
        private const val SMART_TV_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; BRAVIA 4K GB) Cobalt/23.lts.2.309559-gold (unlike Gecko) v8/8.8.278.8-jit gles Starboard/14, Sony_TV_BRAVIA-4K-GB_2023/9 (Sony, BRAVIA 4K GB)"
    }
}
