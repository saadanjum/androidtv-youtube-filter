package com.youtubemix.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class SeenChannel(
    val name: String,
    val lastVideoTitle: String,
    val timestamp: String
)

data class BlockedEvent(
    val type: String, // "TITLE_KEYWORD", "TRANSCRIPT_PHRASE", "CHANNEL", "SHORTS"
    val videoTitle: String,
    val channelName: String,
    val matchedRule: String,
    val timestamp: String
)

class FilterConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("youtube_mix_prefs", Context.MODE_PRIVATE)

    private val recentChannels = CopyOnWriteArrayList<SeenChannel>()
    private val blockedEvents = CopyOnWriteArrayList<BlockedEvent>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        if (!prefs.contains(KEY_INITIALIZED)) {
            prefs.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putString(KEY_PARENT_PIN, "1234")
                .putBoolean(KEY_HIDE_SHORTS, true)
                .putBoolean(KEY_BLOCK_CENSORED_CAPTIONS, true)
                .putInt(KEY_TIMER_LIMIT_MINUTES, 60) // Default 60 min screen time
                .putLong(KEY_TIMER_REMAINING_SECONDS, 60 * 60L)
                .putBoolean(KEY_TIMER_ENABLED, true)
                .putBoolean(KEY_TIMER_UNLOCKED_TODAY, false)
                .putStringSet(
                    KEY_TITLE_KEYWORDS,
                    setOf(
                        "skibidi",
                        "huggy wuggy",
                        "poppy playtime",
                        "siren head",
                        "mommy long legs",
                        "prank",
                        "18+",
                        "horror"
                    )
                )
                .putStringSet(
                    KEY_TRANSCRIPT_PHRASES,
                    setOf(
                        "what the heck",
                        "what the hell",
                        "shut up",
                        "stupid",
                        "idiot",
                        "dumbass",
                        "crap",
                        "damn",
                        "wtf",
                        "omfg"
                    )
                )
                .putStringSet(KEY_BLOCKED_CHANNELS, emptySet())
                .apply()
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (l in listeners) {
            l.invoke()
        }
    }

    var parentPin: String
        get() = prefs.getString(KEY_PARENT_PIN, "1234") ?: "1234"
        set(value) {
            if (value.matches(Regex("^\\d{4}$"))) {
                prefs.edit().putString(KEY_PARENT_PIN, value).apply()
                notifyListeners()
            }
        }

    var hideShorts: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SHORTS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_SHORTS, value).apply()
            notifyListeners()
        }

    var blockCensoredCaptions: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_CENSORED_CAPTIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_CENSORED_CAPTIONS, value).apply()
            notifyListeners()
        }

    var timerEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIMER_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TIMER_ENABLED, value).apply()
            notifyListeners()
        }

    var timerLimitMinutes: Int
        get() = prefs.getInt(KEY_TIMER_LIMIT_MINUTES, 60)
        set(value) {
            val clamped = value.coerceIn(5, 480)
            prefs.edit()
                .putInt(KEY_TIMER_LIMIT_MINUTES, clamped)
                .putLong(KEY_TIMER_REMAINING_SECONDS, clamped * 60L)
                .putBoolean(KEY_TIMER_UNLOCKED_TODAY, false)
                .apply()
            notifyListeners()
        }

    var remainingSeconds: Long
        get() = prefs.getLong(KEY_TIMER_REMAINING_SECONDS, 3600L)
        set(value) {
            prefs.edit().putLong(KEY_TIMER_REMAINING_SECONDS, value.coerceAtLeast(0L)).apply()
        }

    var unlockedForToday: Boolean
        get() = prefs.getBoolean(KEY_TIMER_UNLOCKED_TODAY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TIMER_UNLOCKED_TODAY, value).apply()
            notifyListeners()
        }

    fun grantBonusMinutes(minutes: Int) {
        val added = (remainingSeconds + minutes * 60L).coerceAtLeast(minutes * 60L)
        prefs.edit()
            .putLong(KEY_TIMER_REMAINING_SECONDS, added)
            .putBoolean(KEY_TIMER_UNLOCKED_TODAY, false)
            .apply()
        notifyListeners()
    }

    fun resetDailyTimer() {
        prefs.edit()
            .putLong(KEY_TIMER_REMAINING_SECONDS, timerLimitMinutes * 60L)
            .putBoolean(KEY_TIMER_UNLOCKED_TODAY, false)
            .apply()
        notifyListeners()
    }

    fun getTitleKeywords(): List<String> =
        (prefs.getStringSet(KEY_TITLE_KEYWORDS, emptySet()) ?: emptySet())
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .sorted()

    fun setTitleKeywords(items: Collection<String>) {
        val cleaned = items.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        prefs.edit().putStringSet(KEY_TITLE_KEYWORDS, cleaned).apply()
        notifyListeners()
    }

    fun addTitleKeyword(word: String) {
        val current = getTitleKeywords().toMutableSet()
        val clean = word.trim().lowercase(Locale.ROOT)
        if (clean.isNotEmpty()) {
            current.add(clean)
            setTitleKeywords(current)
        }
    }

    fun removeTitleKeyword(word: String) {
        val current = getTitleKeywords().toMutableSet()
        current.remove(word.trim().lowercase(Locale.ROOT))
        setTitleKeywords(current)
    }

    fun getTranscriptPhrases(): List<String> =
        (prefs.getStringSet(KEY_TRANSCRIPT_PHRASES, emptySet()) ?: emptySet())
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .sorted()

    fun setTranscriptPhrases(items: Collection<String>) {
        val cleaned = items.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        prefs.edit().putStringSet(KEY_TRANSCRIPT_PHRASES, cleaned).apply()
        notifyListeners()
    }

    fun addTranscriptPhrase(phrase: String) {
        val current = getTranscriptPhrases().toMutableSet()
        val clean = phrase.trim().lowercase(Locale.ROOT)
        if (clean.isNotEmpty()) {
            current.add(clean)
            setTranscriptPhrases(current)
        }
    }

    fun removeTranscriptPhrase(phrase: String) {
        val current = getTranscriptPhrases().toMutableSet()
        current.remove(phrase.trim().lowercase(Locale.ROOT))
        setTranscriptPhrases(current)
    }

    fun getBlockedChannels(): List<String> =
        (prefs.getStringSet(KEY_BLOCKED_CHANNELS, emptySet()) ?: emptySet())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedBy { it.lowercase(Locale.ROOT) }

    fun setBlockedChannels(items: Collection<String>) {
        val cleaned = items.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.edit().putStringSet(KEY_BLOCKED_CHANNELS, cleaned).apply()
        notifyListeners()
    }

    fun addBlockedChannel(channel: String) {
        val current = getBlockedChannels().toMutableSet()
        val clean = channel.trim()
        if (clean.isNotEmpty()) {
            current.add(clean)
            setBlockedChannels(current)
        }
    }

    fun removeBlockedChannel(channel: String) {
        val target = channel.trim().lowercase(Locale.ROOT)
        val updated = getBlockedChannels().filterNot { it.lowercase(Locale.ROOT) == target }
        setBlockedChannels(updated)
    }

    fun recordSeenChannel(channelName: String, videoTitle: String) {
        val cleanChannel = channelName.trim()
        if (cleanChannel.isEmpty() || cleanChannel.length < 2) return
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        recentChannels.removeAll { it.name.equals(cleanChannel, ignoreCase = true) }
        recentChannels.add(0, SeenChannel(cleanChannel, videoTitle.take(80), now))
        while (recentChannels.size > 40) {
            recentChannels.removeAt(recentChannels.size - 1)
        }
    }

    fun recordBlockedEvent(type: String, videoTitle: String, channelName: String, matchedRule: String) {
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        blockedEvents.add(
            0,
            BlockedEvent(
                type = type,
                videoTitle = videoTitle.take(90),
                channelName = channelName.take(50),
                matchedRule = matchedRule,
                timestamp = now
            )
        )
        while (blockedEvents.size > 50) {
            blockedEvents.removeAt(blockedEvents.size - 1)
        }
    }

    fun toJsConfigJson(): String {
        val obj = JSONObject()
        obj.put("hideShorts", hideShorts)
        obj.put("blockCensoredCaptions", blockCensoredCaptions)
        obj.put("titleKeywords", JSONArray(getTitleKeywords()))
        obj.put("transcriptPhrases", JSONArray(getTranscriptPhrases()))
        obj.put("blockedChannels", JSONArray(getBlockedChannels().map { it.lowercase(Locale.ROOT) }))
        return obj.toString()
    }

    fun toPortalStatusJson(): JSONObject {
        val obj = JSONObject()
        obj.put("hideShorts", hideShorts)
        obj.put("blockCensoredCaptions", blockCensoredCaptions)
        obj.put("timerEnabled", timerEnabled)
        obj.put("timerLimitMinutes", timerLimitMinutes)
        obj.put("remainingSeconds", remainingSeconds)
        obj.put("unlockedForToday", unlockedForToday)
        obj.put("titleKeywords", JSONArray(getTitleKeywords()))
        obj.put("transcriptPhrases", JSONArray(getTranscriptPhrases()))
        obj.put("blockedChannels", JSONArray(getBlockedChannels()))

        val seenArr = JSONArray()
        for (item in recentChannels) {
            val c = JSONObject()
            c.put("name", item.name)
            c.put("lastVideoTitle", item.lastVideoTitle)
            c.put("timestamp", item.timestamp)
            seenArr.put(c)
        }
        obj.put("recentChannels", seenArr)

        val blockedArr = JSONArray()
        for (ev in blockedEvents) {
            val b = JSONObject()
            b.put("type", ev.type)
            b.put("videoTitle", ev.videoTitle)
            b.put("channelName", ev.channelName)
            b.put("matchedRule", ev.matchedRule)
            b.put("timestamp", ev.timestamp)
            blockedArr.put(b)
        }
        obj.put("blockedEvents", blockedArr)
        return obj
    }

    companion object {
        private const val KEY_INITIALIZED = "initialized_v1"
        private const val KEY_PARENT_PIN = "parent_pin"
        private const val KEY_HIDE_SHORTS = "hide_shorts"
        private const val KEY_BLOCK_CENSORED_CAPTIONS = "block_censored_captions"
        private const val KEY_TIMER_ENABLED = "timer_enabled"
        private const val KEY_TIMER_LIMIT_MINUTES = "timer_limit_minutes"
        private const val KEY_TIMER_REMAINING_SECONDS = "timer_remaining_seconds"
        private const val KEY_TIMER_UNLOCKED_TODAY = "timer_unlocked_today"
        private const val KEY_TITLE_KEYWORDS = "title_keywords"
        private const val KEY_TRANSCRIPT_PHRASES = "transcript_phrases"
        private const val KEY_BLOCKED_CHANNELS = "blocked_channels"
    }
}
