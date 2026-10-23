/**
 * YouTube Mix — Real-Time InnerTube JSON Sanitizer & Transcript Scanner
 * Injected into https://www.youtube.com/tv
 */
(function () {
  if (window.__YOUTUBE_MIX_INJECTED__) return;
  window.__YOUTUBE_MIX_INJECTED__ = true;

  // Hook window.close() so when YouTube TV's "Exit" dialog or root Back button is triggered, the native Android TV app exits cleanly
  const originalWindowClose = window.close.bind(window);
  window.close = function () {
    try {
      if (window.YouTubeMixBridge && window.YouTubeMixBridge.closeApp) {
        window.YouTubeMixBridge.closeApp();
        return;
      }
    } catch (_) {}
    originalWindowClose();
  };

  // 1. Advertise a virtual microphone in enumerateDevices() so YouTube TV shows the Mic button.
  // 2. Return a silent WebAudio MediaStream in getUserMedia() so WebView NEVER locks the hardware mic!
  // 3. When MediaRecorder.start() is called (Mic turns RED), trigger Android TV's background SpeechRecognizer
  //    (which plays the standard Android TV "Beep" and listens to the Bluetooth remote mic with ZERO extra popups).
  try {
    if (navigator.mediaDevices) {
      if (navigator.mediaDevices.enumerateDevices) {
        const origEnumerate = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
        navigator.mediaDevices.enumerateDevices = async function () {
          let devices = [];
          try {
            devices = await origEnumerate();
          } catch (_) {}
          const hasAudioInput = devices.some((d) => d && d.kind === 'audioinput');
          if (!hasAudioInput) {
            devices.push({
              deviceId: 'default',
              kind: 'audioinput',
              label: 'Android TV Voice Remote Microphone',
              groupId: 'default',
              toJSON() {
                return this;
              }
            });
          }
          return devices;
        };
      }

      const origGetUserMedia = navigator.mediaDevices.getUserMedia
        ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
        : null;

      navigator.mediaDevices.getUserMedia = async function (constraints) {
        if (constraints && constraints.audio) {
          // Return a silent WebAudio MediaStream so WebView does NOT grab exclusive hardware mic lock!
          const AudioCtx = window.AudioContext || window.webkitAudioContext;
          const ctx = new AudioCtx();
          const dest = ctx.createMediaStreamDestination();
          return dest.stream;
        }
        if (origGetUserMedia) {
          return origGetUserMedia(constraints);
        }
        throw new DOMException('NotSupported', 'NotSupportedError');
      };
    }

    class NativeBridgeMediaRecorder {
      constructor(stream, options = {}) {
        this.stream = stream;
        this.mimeType = (options && options.mimeType) || 'audio/webm;codecs=opus';
        this.state = 'inactive';
        this.ondataavailable = null;
        this.onstart = null;
        this.onstop = null;
        this.onerror = null;
        this._listeners = { dataavailable: [], start: [], stop: [], error: [] };
      }

      addEventListener(type, cb) {
        if (this._listeners[type]) this._listeners[type].push(cb);
      }

      removeEventListener(type, cb) {
        if (this._listeners[type]) {
          this._listeners[type] = this._listeners[type].filter((fn) => fn !== cb);
        }
      }

      _emit(type, eventObj) {
        const handler = this[`on${type}`];
        if (typeof handler === 'function') {
          try {
            handler(eventObj);
          } catch (_) {}
        }
        if (this._listeners[type]) {
          this._listeners[type].forEach((fn) => {
            try {
              fn(eventObj);
            } catch (_) {}
          });
        }
      }

      start(timeslice) {
        if (this.state !== 'inactive') return;
        this.state = 'recording';
        window.__activeYouTubeMixRecorder = this;
        this._emit('start', new Event('start'));

        if (window.YouTubeMixBridge && window.YouTubeMixBridge.startNativeVoiceSearch) {
          try {
            window.YouTubeMixBridge.startNativeVoiceSearch();
          } catch (_) {}
        }
      }

      stop() {
        if (this.state === 'inactive') return;
        this.state = 'inactive';
        if (window.__activeYouTubeMixRecorder === this) {
          window.__activeYouTubeMixRecorder = null;
        }
        if (window.YouTubeMixBridge && window.YouTubeMixBridge.stopNativeVoiceSearch) {
          try {
            window.YouTubeMixBridge.stopNativeVoiceSearch();
          } catch (_) {}
        }
        this._emit('stop', new Event('stop'));
      }

      pause() {
        this.state = 'paused';
      }

      resume() {
        this.state = 'recording';
      }
    }

    window.MediaRecorder = function (stream, options) {
      return new NativeBridgeMediaRecorder(stream, options);
    };

    window.MediaRecorder.isTypeSupported = function () {
      return true;
    };
  } catch (e) {
    console.warn('[YouTubeMix] Audio bridge error:', e);
  }

  // Called by MainActivity when background SpeechRecognizer finishes recognizing speech from the TV remote
  window.__onNativeVoiceEnded = function () {
    if (window.__activeYouTubeMixRecorder) {
      try {
        window.__activeYouTubeMixRecorder.stop();
      } catch (_) {}
    }
  };

  const blockedVideoIds = new Set();

  function loadConfig() {
    try {
      if (window.YouTubeMixBridge && window.YouTubeMixBridge.getFilterConfig) {
        const raw = window.YouTubeMixBridge.getFilterConfig();
        return JSON.parse(raw);
      }
    } catch (e) {
      console.warn('[YouTubeMix] Failed to load config from bridge:', e);
    }
    return {
      hideShorts: true,
      blockCensoredCaptions: true,
      titleKeywords: [],
      transcriptPhrases: ['what the heck', 'what the hell'],
      blockedChannels: []
    };
  }

  function isWatchScreen() {
    const hash = window.location.hash || '';
    const href = window.location.href || '';
    return hash.includes('/watch') || href.includes('/watch?');
  }

  // Force hardware-decoded 1080p SDR codecs (H.264 / 8-bit VP9) in MediaSource Extensions
  // Rejects AV1 (av01), 10-bit HDR VP9 (vp09.02), and >1080p (1440p/4K) which cause frame drops in Android TV WebView
  try {
    if (window.MediaSource && typeof window.MediaSource.isTypeSupported === 'function') {
      const origIsTypeSupported = window.MediaSource.isTypeSupported.bind(window.MediaSource);
      window.MediaSource.isTypeSupported = function (mimeType) {
        if (typeof mimeType === 'string') {
          const lower = mimeType.toLowerCase();
          // Block AV1 and 10-bit HDR VP9 Profile 2 so YouTube selects hardware-accelerated H.264 (avc1) or 8-bit VP9
          if (lower.includes('av01') || lower.includes('vp09.02') || lower.includes('vp9.2')) {
            return false;
          }
          // Block >1080p (4K / 1440p) streams on 1080p WebView
          const heightMatch = lower.match(/height=(\d+)/);
          if (heightMatch && parseInt(heightMatch[1], 10) > 1080) {
            return false;
          }
          const widthMatch = lower.match(/width=(\d+)/);
          if (widthMatch && parseInt(widthMatch[1], 10) > 1920) {
            return false;
          }
        }
        return origIsTypeSupported(mimeType);
      };
    }
  } catch (e) {
    console.warn('[YouTubeMix] MediaSource codec filter error:', e);
  }

  let currentConfig = loadConfig();
  window.__updateYouTubeMixConfig = function (newConfig) {
    if (typeof newConfig === 'string') {
      try {
        currentConfig = JSON.parse(newConfig);
      } catch (_) {}
    } else if (newConfig && typeof newConfig === 'object') {
      currentConfig = newConfig;
    } else {
      currentConfig = loadConfig();
    }
    if (!isWatchScreen()) {
      sweepLiveDomShelvesAndTiles();
    }
  };

  // Normalize 1080p Android TV WebView viewport & prevent post-login scroll/offset cut-offs
  function enforceTvViewportFit() {
    if (isWatchScreen()) return;
    try {
      // 1. Ensure viewport meta matches device-width 1.0 without user-scalable shifts
      let vp = document.querySelector('meta[name="viewport"]');
      if (!vp && document.head) {
        vp = document.createElement('meta');
        vp.name = 'viewport';
        document.head.appendChild(vp);
      }
      if (vp && vp.content !== 'width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no') {
        vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no';
      }

      // 2. Inject strict fullscreen positioning CSS so youtube.com/tv never shifts negative top/left after OAuth redirect
      if (document.head && !document.getElementById('ytmix-viewport-fix')) {
        const style = document.createElement('style');
        style.id = 'ytmix-viewport-fix';
        style.textContent = `
          html, body {
            margin: 0 !important;
            padding: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            overflow: hidden !important;
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            background: #000 !important;
          }
          ytlr-app, #app, #container {
            top: 0 !important;
            left: 0 !important;
            margin: 0 !important;
          }
        `;
        document.head.appendChild(style);
      }

      // 3. Reset any accidental scroll offset caused by login input focus
      if (window.scrollX !== 0 || window.scrollY !== 0) {
        window.scrollTo(0, 0);
      }
    } catch (_) {}
  }

  document.addEventListener('DOMContentLoaded', enforceTvViewportFit);
  window.addEventListener('load', enforceTvViewportFit);
  window.addEventListener('resize', enforceTvViewportFit);
  window.addEventListener('hashchange', enforceTvViewportFit);
  window.addEventListener('scroll', () => {
    if (window.scrollX !== 0 || window.scrollY !== 0) {
      window.scrollTo(0, 0);
    }
  });

  // Helper to extract human-readable text from YouTube InnerTube Text objects
  function extractText(node) {
    if (!node) return '';
    if (typeof node === 'string') return node;
    if (typeof node.simpleText === 'string') return node.simpleText;
    if (Array.isArray(node.runs)) {
      return node.runs.map((r) => r.text || '').join('');
    }
    if (node.accessibility && node.accessibility.accessibilityData && node.accessibility.accessibilityData.label) {
      return node.accessibility.accessibilityData.label;
    }
    return '';
  }

  // Extract metadata from any video renderer node (tileRenderer, videoRenderer, compactVideoRenderer, etc.)
  function inspectVideoNode(item) {
    if (!item || typeof item !== 'object') return null;

    const renderer =
      item.tileRenderer ||
      item.videoRenderer ||
      item.compactVideoRenderer ||
      item.gridVideoRenderer ||
      item.reelItemRenderer ||
      item.shortsLockupViewModel ||
      (item.content && item.content.tileRenderer) ||
      null;

    if (!renderer) return null;

    // Immediate Shorts lockup check
    if (item.reelItemRenderer || item.shortsLockupViewModel) {
      return {
        isShorts: true,
        videoId: '',
        title: extractText(renderer.headline || renderer.title) || 'YouTube Short',
        channel: ''
      };
    }

    const videoId =
      renderer.videoId ||
      renderer.contentId ||
      (renderer.onSelectCommand &&
        renderer.onSelectCommand.watchEndpoint &&
        renderer.onSelectCommand.watchEndpoint.videoId) ||
      (renderer.onSelectCommand &&
        renderer.onSelectCommand.reelWatchEndpoint &&
        renderer.onSelectCommand.reelWatchEndpoint.videoId) ||
      (renderer.navigationEndpoint &&
        renderer.navigationEndpoint.watchEndpoint &&
        renderer.navigationEndpoint.watchEndpoint.videoId) ||
      '';

    const meta = (renderer.metadata && renderer.metadata.tileMetadataRenderer) || renderer;

    const title =
      extractText(meta.title) ||
      extractText(renderer.title) ||
      extractText(renderer.headline) ||
      '';

    const channel =
      extractText(meta.lines && meta.lines[0] && meta.lines[0].lineRenderer && meta.lines[0].lineRenderer.items && meta.lines[0].lineRenderer.items[0] && meta.lines[0].lineRenderer.items[0].lineItemRenderer && meta.lines[0].lineRenderer.items[0].lineItemRenderer.text) ||
      extractText(renderer.shortBylineText) ||
      extractText(renderer.longBylineText) ||
      extractText(renderer.ownerText) ||
      '';

    const a11yLabel =
      (renderer.accessibility &&
        renderer.accessibility.accessibilityData &&
        renderer.accessibility.accessibilityData.label) ||
      (meta.title &&
        meta.title.accessibility &&
        meta.title.accessibility.accessibilityData &&
        meta.title.accessibility.accessibilityData.label) ||
      '';

    // Detect Shorts on TV (tileStyle, reelWatchEndpoint, #shorts in title/a11y)
    const isShorts = Boolean(
      renderer.style === 'TILE_STYLE_YTLR_SHORTS' ||
        renderer.tileStyle === 'TILE_STYLE_YTLR_SHORTS' ||
        (renderer.onSelectCommand && renderer.onSelectCommand.reelWatchEndpoint) ||
        (renderer.navigationEndpoint && renderer.navigationEndpoint.reelWatchEndpoint) ||
        /\b#shorts\b/i.test(title) ||
        /\bshorts\b/i.test(a11yLabel)
    );

    if (!title && !a11yLabel && !videoId && !isShorts) {
      return null;
    }

    return {
      videoId,
      title: title || a11yLabel,
      channel,
      a11yLabel,
      isShorts
    };
  }

  // Try to parse channel name from accessibility label if lineRenderer didn't have it explicitly
  // e.g. "Minecraft Build Tutorial by Wattles 120,000 views 15 minutes"
  function inferChannelFromA11y(info) {
    if (info.channel && info.channel.trim().length > 0) return info.channel.trim();
    if (!info.a11yLabel) return '';
    const byMatch = info.a11yLabel.match(/\bby\s+([^•,]+?)\s+(?:\d|views|streamed|ago|minutes|hours|seconds)/i);
    if (byMatch && byMatch[1]) {
      return byMatch[1].trim();
    }
    return '';
  }

  function shouldBlockVideo(info) {
    const cfg = currentConfig || loadConfig();

    if (info.videoId && blockedVideoIds.has(info.videoId)) {
      return { blocked: true, type: 'TRANSCRIPT_PHRASE', rule: 'Previously flagged transcript' };
    }

    // 1. Check Shorts
    if (cfg.hideShorts && info.isShorts) {
      return { blocked: true, type: 'SHORTS', rule: 'YouTube Shorts Hidden' };
    }

    const resolvedChannel = inferChannelFromA11y(info);
    const channelLower = resolvedChannel.toLowerCase();
    const combinedText = `${info.title || ''} ${info.a11yLabel || ''}`.toLowerCase();

    // 2. Check Channel Blacklist
    if (Array.isArray(cfg.blockedChannels) && cfg.blockedChannels.length > 0) {
      for (const blockedChan of cfg.blockedChannels) {
        if (!blockedChan) continue;
        const target = blockedChan.toLowerCase().trim();
        if (!target) continue;
        if (
          (channelLower && (channelLower === target || channelLower.includes(target))) ||
          combinedText.includes(`by ${target} `)
        ) {
          return { blocked: true, type: 'CHANNEL', rule: blockedChan };
        }
      }
    }

    // 3. Check Title Keywords
    if (Array.isArray(cfg.titleKeywords) && cfg.titleKeywords.length > 0) {
      for (const kw of cfg.titleKeywords) {
        if (!kw) continue;
        const target = kw.toLowerCase().trim();
        if (!target) continue;
        if (combinedText.includes(target)) {
          return { blocked: true, type: 'TITLE_KEYWORD', rule: kw };
        }
      }
    }

    // Record allowed seen channel so parent can 1-tap block it from phone portal
    if (resolvedChannel && window.YouTubeMixBridge && window.YouTubeMixBridge.reportSeenChannel) {
      try {
        window.YouTubeMixBridge.reportSeenChannel(resolvedChannel, info.title || '');
      } catch (_) {}
    }

    return { blocked: false };
  }

  // Extract header title from any YouTube TV shelf / row renderer
  function extractShelfTitle(shelfNode) {
    if (!shelfNode || typeof shelfNode !== 'object') return '';
    const shelf =
      shelfNode.shelfRenderer ||
      shelfNode.richShelfRenderer ||
      shelfNode.reelShelfRenderer ||
      shelfNode.gridRenderer ||
      shelfNode;

    return (
      extractText(shelf.title) ||
      extractText(
        shelf.headerRenderer &&
          shelf.headerRenderer.shelfHeaderRenderer &&
          shelf.headerRenderer.shelfHeaderRenderer.title
      ) ||
      extractText(
        shelf.headerRenderer &&
          shelf.headerRenderer.shelfHeaderRenderer &&
          shelf.headerRenderer.shelfHeaderRenderer.avatarLockup &&
          shelf.headerRenderer.shelfHeaderRenderer.avatarLockup.avatarLockupRenderer &&
          shelf.headerRenderer.shelfHeaderRenderer.avatarLockup.avatarLockupRenderer.title
      ) ||
      extractText(shelf.header && shelf.header.gridHeaderRenderer && shelf.header.gridHeaderRenderer.title) ||
      ''
    ).trim();
  }

  // Check if a shelf (row) should be removed entirely before or after sanitizing its children
  function shouldRemoveShelf(elem, afterSanitizingChildren = false) {
    if (!elem || typeof elem !== 'object') return false;
    const cfg = currentConfig || loadConfig();

    const isShelfObject = Boolean(
      elem.shelfRenderer ||
        elem.richShelfRenderer ||
        elem.reelShelfRenderer ||
        elem.itemSectionRenderer ||
        elem.gridRenderer
    );
    if (!isShelfObject) return false;

    const headerTitle = extractShelfTitle(elem);
    const headerLower = headerTitle.toLowerCase();

    // 1. Shorts shelf
    if (cfg.hideShorts && (elem.reelShelfRenderer || /^shorts$/i.test(headerTitle))) {
      return true;
    }

    // 2. Does the shelf heading itself match a blocked keyword or blocked channel? (e.g., "Super Mario" topic/channel shelf)
    if (headerLower) {
      if (Array.isArray(cfg.blockedChannels)) {
        for (const ch of cfg.blockedChannels) {
          const target = (ch || '').toLowerCase().trim();
          if (target && headerLower.includes(target)) {
            return true;
          }
        }
      }
      if (Array.isArray(cfg.titleKeywords)) {
        for (const kw of cfg.titleKeywords) {
          const target = (kw || '').toLowerCase().trim();
          if (target && headerLower.includes(target)) {
            return true;
          }
        }
      }
    }

    // 3. After sanitizing children: if the shelf's horizontal list/grid now has 0 items left, remove the whole shelf so no empty black placeholders remain!
    if (afterSanitizingChildren) {
      const shelf = elem.shelfRenderer || elem.richShelfRenderer || elem.gridRenderer || elem;
      const horizontalItems =
        (shelf.content &&
          shelf.content.horizontalListRenderer &&
          shelf.content.horizontalListRenderer.items) ||
        (shelf.content &&
          shelf.content.gridRenderer &&
          shelf.content.gridRenderer.items) ||
        (shelf.content &&
          shelf.content.expandedShelfContentsRenderer &&
          shelf.content.expandedShelfContentsRenderer.items) ||
        shelf.items ||
        elem.contents;

      if (Array.isArray(horizontalItems) && horizontalItems.length === 0) {
        return true;
      }
    }

    return false;
  }

  // Recursively walk and sanitize any InnerTube JSON payload
  function sanitizeInnerTubeTree(node, depth = 0) {
    if (!node || typeof node !== 'object' || depth > 35) return node;

    if (Array.isArray(node)) {
      for (let i = node.length - 1; i >= 0; i--) {
        const elem = node[i];
        if (!elem || typeof elem !== 'object') continue;

        // Check if entire shelf should be stripped immediately (Shorts shelf or shelf heading matches blocked keyword/channel)
        if (shouldRemoveShelf(elem, false)) {
          node.splice(i, 1);
          continue;
        }

        const videoInfo = inspectVideoNode(elem);
        if (videoInfo) {
          const decision = shouldBlockVideo(videoInfo);
          if (decision.blocked) {
            if (window.YouTubeMixBridge && window.YouTubeMixBridge.reportBlockedItem) {
              try {
                window.YouTubeMixBridge.reportBlockedItem(
                  decision.type,
                  videoInfo.title || 'Untitled',
                  inferChannelFromA11y(videoInfo),
                  decision.rule
                );
              } catch (_) {}
            }
            node.splice(i, 1);
            continue;
          }
        }

        // Recursively sanitize child arrays first
        sanitizeInnerTubeTree(elem, depth + 1);

        // If this was a shelf and all of its videos were filtered out, remove the empty shelf itself!
        if (shouldRemoveShelf(elem, true)) {
          node.splice(i, 1);
          continue;
        }

        // Sync visibleItemCount / totalItemCount if this is a horizontalListRenderer so YouTube TV doesn't render blank black placeholder slots!
        const horiz =
          elem.horizontalListRenderer ||
          (elem.content && elem.content.horizontalListRenderer) ||
          (elem.shelfRenderer && elem.shelfRenderer.content && elem.shelfRenderer.content.horizontalListRenderer);
        if (horiz && Array.isArray(horiz.items)) {
          if (typeof horiz.visibleItemCount === 'number' && horiz.visibleItemCount > horiz.items.length) {
            horiz.visibleItemCount = horiz.items.length;
          }
        }
      }
      return node;
    }

    for (const key of Object.keys(node)) {
      // Strip inline thumbnail preview autoplay triggers so resting the cursor on a video card never auto-plays it!
      if (
        key === 'inlinePlaybackEndpoint' ||
        key === 'inlinePlaybackRenderer' ||
        key === 'movingThumbnailDetails' ||
        key === 'richThumbnail'
      ) {
        delete node[key];
        continue;
      }
      // If onFocusCommand triggers inline playback, delete it as well
      if (
        key === 'onFocusCommand' &&
        node[key] &&
        (node[key].inlinePlaybackEndpoint ||
          JSON.stringify(node[key]).includes('inlinePlayback'))
      ) {
        delete node[key];
        continue;
      }

      const val = node[key];
      if (val && typeof val === 'object') {
        sanitizeInnerTubeTree(val, depth + 1);
      }
    }

    // Also clamp horizontalListRenderer counts on direct objects
    if (node.horizontalListRenderer && Array.isArray(node.horizontalListRenderer.items)) {
      const h = node.horizontalListRenderer;
      if (typeof h.visibleItemCount === 'number' && h.visibleItemCount > h.items.length) {
        h.visibleItemCount = h.items.length;
      }
    }

    return node;
  }

  // Stop current video playback and navigate back ONLY if the user actually opened the fullscreen player (#/watch)
  function abortForbiddenVideoPlayback(videoId, videoTitle, channelName, matchedPhrase) {
    if (videoId) {
      blockedVideoIds.add(videoId);
    }

    // Always silence/stop the <video> element (whether it's an inline hover preview or fullscreen player)
    try {
      const videos = document.querySelectorAll('video');
      videos.forEach((v) => {
        v.pause();
        v.muted = true;
        v.removeAttribute('src');
        v.load();
      });
    } catch (_) {}

    // Also hide the offending thumbnail tile on the browse/search grid so they don't even click it!
    try {
      const tiles = document.querySelectorAll('ytlr-tile-renderer');
      tiles.forEach((tile) => {
        const text = ((tile.textContent || '') + ' ' + (tile.getAttribute('aria-label') || '')).toLowerCase();
        if (videoTitle && text.includes(videoTitle.toLowerCase().slice(0, 25))) {
          tile.style.display = 'none';
        }
      });
    } catch (_) {}

    // Check if the user is actually inside the fullscreen video player (#/watch)
    // vs just hovering over a thumbnail preview on the Home / Search screen!
    const hash = window.location.hash || '';
    const href = window.location.href || '';
    const isFullScreenWatch = hash.includes('/watch') || href.includes('/watch?');

    if (!isFullScreenWatch) {
      // It was just an inline hover auto-preview on the Home/Search screen!
      // Record it silently in the parent portal log, DO NOT press Back/Escape!
      if (window.YouTubeMixBridge && window.YouTubeMixBridge.reportBlockedItem) {
        try {
          window.YouTubeMixBridge.reportBlockedItem(
            'TRANSCRIPT_PHRASE',
            videoTitle || 'Preview Video',
            channelName || '',
            matchedPhrase
          );
        } catch (_) {}
      }
      return;
    }

    // User actually clicked into the fullscreen video player (#/watch) — show toast and exit back to browse screen
    if (window.YouTubeMixBridge && window.YouTubeMixBridge.notifyTranscriptBlocked) {
      try {
        window.YouTubeMixBridge.notifyTranscriptBlocked(
          videoTitle || 'Video',
          channelName || '',
          matchedPhrase
        );
      } catch (_) {}
    }

    setTimeout(() => {
      try {
        if ((window.location.hash || '').includes('/watch')) {
          const escEvent = new KeyboardEvent('keydown', {
            key: 'Escape',
            code: 'Escape',
            keyCode: 27,
            which: 27,
            bubbles: true,
            cancelable: true
          });
          document.dispatchEvent(escEvent);
        }
      } catch (_) {}
    }, 100);
  }

  // Decode XML entities in YouTube timedtext transcripts
  function decodeEntities(str) {
    return str
      .replace(/&amp;/g, '&')
      .replace(/&#39;/g, "'")
      .replace(/&quot;/g, '"')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/\s+/g, ' ');
  }

  // Layer 1: Spoken Transcript / Caption Scanner
  async function inspectPlayerPayloadAndTranscript(playerJson) {
    if (!playerJson || typeof playerJson !== 'object') return;
    const cfg = currentConfig || loadConfig();

    const details = playerJson.videoDetails || {};
    const videoId = details.videoId || '';
    const title = details.title || '';
    const author = details.author || '';
    const shortDescription = details.shortDescription || '';

    if (author && window.YouTubeMixBridge && window.YouTubeMixBridge.reportSeenChannel) {
      try {
        window.YouTubeMixBridge.reportSeenChannel(author, title);
      } catch (_) {}
    }

    // Check title, author, and description against rules first
    const metaCheck = shouldBlockVideo({
      videoId,
      title: `${title} ${shortDescription}`,
      channel: author,
      a11yLabel: '',
      isShorts: Boolean(details.isShort || details.isShortsEligible)
    });

    if (metaCheck.blocked) {
      abortForbiddenVideoPlayback(videoId, title, author, metaCheck.rule);
      return;
    }

    // Extract caption track URL from player response
    const tracklist =
      playerJson.captions &&
      playerJson.captions.playerCaptionsTracklistRenderer &&
      playerJson.captions.playerCaptionsTracklistRenderer.captionTracks;

    if (!Array.isArray(tracklist) || tracklist.length === 0) return;

    // Prefer English track or fallback to first available track
    const preferredTrack =
      tracklist.find((t) => t.languageCode && t.languageCode.startsWith('en')) ||
      tracklist[0];

    if (!preferredTrack || !preferredTrack.baseUrl) return;

    try {
      const resp = await originalFetch(preferredTrack.baseUrl);
      const rawCaptionText = await resp.text();
      if (!rawCaptionText) return;

      // Strip XML tags if XML format, or extract text segments
      const plainTranscript = decodeEntities(
        rawCaptionText.replace(/<[^>]+>/g, ' ')
      ).toLowerCase();

      // 1. Check YouTube's "[ __ ]" auto-censored profanity tags
      if (cfg.blockCensoredCaptions && /\[\s*__\s*\]/.test(plainTranscript)) {
        abortForbiddenVideoPlayback(videoId, title, author, '[ __ ] (Strong Language)');
        return;
      }

      // 2. Check Parent's Custom Spoken Transcript Phrases (e.g., "what the heck", "what the hell")
      if (Array.isArray(cfg.transcriptPhrases)) {
        for (const phrase of cfg.transcriptPhrases) {
          if (!phrase) continue;
          const cleanPhrase = phrase.toLowerCase().trim();
          if (!cleanPhrase) continue;
          if (plainTranscript.includes(cleanPhrase)) {
            abortForbiddenVideoPlayback(videoId, title, author, phrase);
            return;
          }
        }
      }
    } catch (err) {
      console.warn('[YouTubeMix] Caption scan error:', err);
    }
  }

  // Filter streamingData.adaptiveFormats to prefer hardware-accelerated <=1080p SDR codecs (H.264 / 8-bit VP9)
  function optimizePlayerStreamingFormats(playerJson) {
    try {
      if (!playerJson || !playerJson.streamingData) return;
      const adaptive = playerJson.streamingData.adaptiveFormats;
      if (!Array.isArray(adaptive) || adaptive.length === 0) return;

      const filtered = adaptive.filter((fmt) => {
        if (!fmt || typeof fmt.mimeType !== 'string') return true;
        const mime = fmt.mimeType.toLowerCase();
        // Keep all audio tracks untouched
        if (mime.startsWith('audio/')) return true;

        // Drop AV1 (av01) and 10-bit HDR VP9 (vp09.02) which cause WebView stutter
        if (mime.includes('av01') || mime.includes('vp09.02') || mime.includes('vp9.2')) {
          return false;
        }
        // Cap video resolution at 1080p (1920x1080) to match 1080p WebView viewport
        if (typeof fmt.height === 'number' && fmt.height > 1080) {
          return false;
        }
        if (typeof fmt.width === 'number' && fmt.width > 1920) {
          return false;
        }
        return true;
      });

      // Only apply if at least one video stream remains after filtering
      const hasVideoLeft = filtered.some(
        (f) => f && typeof f.mimeType === 'string' && f.mimeType.toLowerCase().startsWith('video/')
      );
      if (hasVideoLeft) {
        playerJson.streamingData.adaptiveFormats = filtered;
      }
    } catch (e) {
      console.warn('[YouTubeMix] Format optimization error:', e);
    }
  }

  let lastPlayerPayload = null;
  let lastInspectedVideoId = null;

  // Block inline thumbnail video previews from playing when not on the fullscreen #/watch screen
  const origVideoPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function (...args) {
    if (!isWatchScreen()) {
      try {
        this.pause();
      } catch (_) {}
      return Promise.resolve();
    }
    return origVideoPlay.apply(this, args);
  };

  // When navigating into #/watch, inspect the player payload if it was prefetched just before navigation
  window.addEventListener('hashchange', () => {
    if (isWatchScreen() && lastPlayerPayload) {
      const vid = lastPlayerPayload.videoDetails && lastPlayerPayload.videoDetails.videoId;
      if (vid && vid !== lastInspectedVideoId) {
        lastInspectedVideoId = vid;
        inspectPlayerPayloadAndTranscript(lastPlayerPayload);
      }
      // Resume video playback if it was held back before hash changed to #/watch
      try {
        const videos = document.querySelectorAll('video');
        videos.forEach((v) => {
          if (v.paused && v.src) {
            origVideoPlay.call(v).catch(() => {});
          }
        });
      } catch (_) {}
    }
  });

  // Hook JSON.parse to catch all InnerTube responses regardless of transport (fetch or XHR)
  const originalJsonParse = JSON.parse;
  JSON.parse = function (text, reviver) {
    const parsed = originalJsonParse.call(this, text, reviver);
    try {
      if (parsed && typeof parsed === 'object' && parsed.responseContext) {
        // If it's a /youtubei/v1/player response
        if (parsed.videoDetails || parsed.streamingData || parsed.captions) {
          optimizePlayerStreamingFormats(parsed);
          lastPlayerPayload = parsed;
          if (isWatchScreen()) {
            const vid = parsed.videoDetails && parsed.videoDetails.videoId;
            lastInspectedVideoId = vid || null;
            inspectPlayerPayloadAndTranscript(parsed);
          }
        }
        // If it's a browse, search, or next response
        if (
          parsed.contents ||
          parsed.onResponseReceivedActions ||
          parsed.onResponseReceivedEndpoints ||
          parsed.continuationContents
        ) {
          sanitizeInnerTubeTree(parsed);
        }
      }
    } catch (e) {
      console.warn('[YouTubeMix] Sanitizer error:', e);
    }
    return parsed;
  };

  // Also keep reference to original fetch for caption downloading
  const originalFetch = window.fetch.bind(window);
  window.fetch = async function (...args) {
    const response = await originalFetch(...args);
    const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';

    if (
      url.includes('/youtubei/v1/browse') ||
      url.includes('/youtubei/v1/search') ||
      url.includes('/youtubei/v1/next') ||
      url.includes('/youtubei/v1/player')
    ) {
      try {
        const cloned = response.clone();
        const data = await cloned.json();
        // JSON.parse hook above already sanitizes `data` when `.json()` runs,
        // so we return a fresh Response with the sanitized JSON body!
        return new Response(JSON.stringify(data), {
          status: response.status,
          statusText: response.statusText,
          headers: response.headers
        });
      } catch (_) {
        return response;
      }
    }
    return response;
  };

  // Also sanitize initial page data or live-rendered DOM shelves/tiles when rules change dynamically
  // Never run during fullscreen video playback (#/watch) so the main thread stays 100% idle for smooth 60fps video!
  function sweepLiveDomShelvesAndTiles() {
    if (isWatchScreen()) return;
    try {
      const cfg = currentConfig || loadConfig();
      const keywords = (cfg.titleKeywords || []).map((k) => (k || '').toLowerCase().trim()).filter(Boolean);
      const channels = (cfg.blockedChannels || []).map((c) => (c || '').toLowerCase().trim()).filter(Boolean);

      // 1. Check rendered shelves (ytlr-shelf-renderer, ytlr-item-section-renderer)
      const shelves = document.querySelectorAll('ytlr-shelf-renderer, ytlr-rich-shelf-renderer');
      shelves.forEach((shelfEl) => {
        const headerEl = shelfEl.querySelector('ytlr-shelf-header-renderer, .ytlr-shelf-header-renderer');
        const headerText = (headerEl ? headerEl.textContent || '' : '').toLowerCase().trim();

        let hideWholeShelf = false;
        if (cfg.hideShorts && headerText === 'shorts') {
          hideWholeShelf = true;
        }
        if (!hideWholeShelf && headerText) {
          for (const kw of keywords) {
            if (headerText.includes(kw)) {
              hideWholeShelf = true;
              break;
            }
          }
          for (const ch of channels) {
            if (headerText.includes(ch)) {
              hideWholeShelf = true;
              break;
            }
          }
        }

        // Also check if all visible tiles inside the shelf are hidden or empty placeholders
        const tiles = shelfEl.querySelectorAll('ytlr-tile-renderer');
        let visibleTiles = 0;
        tiles.forEach((tile) => {
          const tileText = ((tile.textContent || '') + ' ' + (tile.getAttribute('aria-label') || '')).toLowerCase();
          let blockTile = false;
          for (const kw of keywords) {
            if (tileText.includes(kw)) {
              blockTile = true;
              break;
            }
          }
          if (!blockTile) {
            for (const ch of channels) {
              if (tileText.includes(ch)) {
                blockTile = true;
                break;
              }
            }
          }
          if (blockTile) {
            tile.style.display = 'none';
          } else if (tile.style.display !== 'none' && tileText.trim().length > 0) {
            visibleTiles++;
          }
        });

        if (hideWholeShelf || (tiles.length > 0 && visibleTiles === 0)) {
          shelfEl.style.display = 'none';
        }
      });
    } catch (_) {}
  }

  setInterval(sweepLiveDomShelvesAndTiles, 2500);

  console.log('[YouTubeMix] Content Filter & Transcript Scanner active.');
})();
