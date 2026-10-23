# YouTube Mix — Android TV Family Content & Transcript Filter

A lightweight Android TV application wrapping `https://www.youtube.com/tv` with real-time **InnerTube JSON sanitization**, **spoken transcript/caption phrase blocking**, **channel blacklisting**, **screen-time limits**, and a **Local Wi-Fi Parent Control Web Portal** (`http://<tv-ip>:8080`).

## Features

- **Local Wi-Fi Parent Web Portal (`http://<tv-ip>:8080`)**: Manage all rules from any phone or laptop on the same home Wi-Fi network without touching the TV remote. Protected by a 4-digit Parent PIN (default: `1234`).
- **Title & Shelf Keyword Blocking**: Case-insensitive filtering of both individual video cards and entire recommendation shelves (`shelfRenderer`).
- **Spoken Transcript Phrase & Strong-Language Blocking**: Downloads and scans video closed-caption tracks (`playerCaptionsTracklistRenderer`) when a video is opened on `#/watch`, blocking videos containing custom forbidden phrases (e.g. `"what the heck"`, `"what the hell"`) or YouTube's `[ __ ]` auto-censored profanity tags.
- **Channel Blacklist & 1-Tap Blocking**: Open exploration of YouTube recommendations while hiding blacklisted channels. Includes a live **Recently Seen Channels** feed in the parent portal for 1-tap blocking.
- **Hide All YouTube Shorts**: Automatically removes Shorts shelves and vertical clips.
- **Disable Thumbnail Auto-Previews**: Prevents recommendation/search thumbnails from auto-playing inline previews when focused.
- **Android TV Remote Voice Search**: Bridges YouTube TV's on-screen microphone button directly to Android TV's native `SpeechRecognizer` (`LANGUAGE_MODEL_WEB_SEARCH`) so the Bluetooth remote mic works seamlessly.
- **Screen-Time Timer & On-Screen TV PIN Pad**: Pauses video playback when daily time runs out and displays a D-pad-friendly 4-digit PIN pad on the TV to add `+15m`, `+30m`, or unlock for the day.

## Build & Sideload

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [`RESEARCH_AND_ARCHITECTURE.md`](./RESEARCH_AND_ARCHITECTURE.md) for full architecture details.
