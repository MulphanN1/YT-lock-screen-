package com.ytbg.app

object Scripts {

    fun page(adBlock: Boolean, ambient: Boolean): String =
        PAGE + (if (adBlock) AD_BLOCK else "") + (if (ambient) AMBIENT else "")

    /** Tells the page whether anyone can see it: the ambient glow does no work while hidden. */
    fun visible(visible: Boolean): String = "window.__ytbgVisible=$visible;"

    /** Ambient glow intensity, 0..1. */
    fun ambientStrength(strength: Float): String = "window.__ytbgAmbientStrength=$strength;"

    /**
     * Injected into every page at document start. Makes the page believe it is always
     * visible (so YouTube does not pause itself) and reports play/pause state to Android.
     */
    private const val PAGE = """
(function () {
  if (window.__ytbg) return;
  window.__ytbg = true;

  function define(name, value) {
    try {
      Object.defineProperty(document, name, { get: function () { return value; }, configurable: true });
    } catch (e) {}
  }
  define('hidden', false);
  define('webkitHidden', false);
  define('visibilityState', 'visible');
  define('webkitVisibilityState', 'visible');
  try { document.hasFocus = function () { return true; }; } catch (e) {}

  // Only page-level events: element blur/focus must keep working for the search box.
  var stop = function (e) {
    if (e.target === window || e.target === document) e.stopImmediatePropagation();
  };
  ['visibilitychange', 'webkitvisibilitychange', 'freeze'].forEach(function (n) {
    document.addEventListener(n, stop, true);
    window.addEventListener(n, stop, true);
  });
  ['blur', 'pagehide'].forEach(function (n) { window.addEventListener(n, stop, true); });

  // Prefer the page's own Media Session data: it has the channel name and artwork.
  function state() {
    var v = document.querySelector('video');
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    var id = new URLSearchParams(location.search).get('v');
    var title = (md && md.title) || (document.title || '').replace(/\s*-\s*YouTube( Music)?$/, '');
    var artist = (md && md.artist) || '';
    var art = '';
    if (md && md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src;
    if (!/^https?:/.test(art)) art = id ? 'https://i.ytimg.com/vi/' + id + '/mqdefault.jpg' : '';
    return [
      !!(v && !v.paused && !v.ended),
      title,
      artist,
      art,
      v ? Math.floor(v.currentTime * 1000) : 0,
      v && isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0,
      id || ''
    ];
  }
  window.__ytbgState = state;
  function report() {
    try {
      if (window.YTBG && document.querySelector('video')) window.YTBG.onState(JSON.stringify(state()));
    } catch (e) {}
  }
  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadedmetadata', 'durationchange', 'seeked', 'ratechange']
    .forEach(function (n) { document.addEventListener(n, report, true); });
  // Catches anything the events miss; events cover the rest, so this can be slow.
  setInterval(report, 5000);

  // "Video paused. Continue watching?": YouTube asks after a long time without touches.
  // Keep its idle clock fresh, and confirm the dialog if it still shows up.
  var CONFIRM = /^\s*(да|продолжить|продолжить просмотр|yes|continue|continue watching)\s*[.!]?\s*$/i;
  setInterval(function () {
    window._lact = Date.now();
    var dialogs = document.querySelectorAll(
      'yt-confirm-dialog-renderer,ytm-confirm-dialog-renderer,tp-yt-paper-dialog,ytm-dialog,[role="dialog"]');
    for (var i = 0; i < dialogs.length; i++) {
      var buttons = dialogs[i].querySelectorAll('button,yt-button-renderer,ytm-button-renderer,a');
      for (var j = 0; j < buttons.length; j++) {
        if (CONFIRM.test(buttons[j].textContent || buttons[j].getAttribute('aria-label') || '')) {
          buttons[j].click();
          var v = document.querySelector('video');
          if (v && v.paused && !v.ended) v.play();
          return;
        }
      }
    }
  }, 3000);

  // SponsorBlock: Android fills __ytbgSegments with [start, end] pairs for the video.
  window.__ytbgSegments = { id: '', list: [] };
  document.addEventListener('timeupdate', function (e) {
    var v = e.target, s = window.__ytbgSegments;
    if (!s.list.length || !(v instanceof HTMLVideoElement)) return;
    if (new URLSearchParams(location.search).get('v') !== s.id) return;
    var t = v.currentTime;
    for (var i = 0; i < s.list.length; i++) {
      if (t >= s.list[i][0] && t < s.list[i][1] - 0.5) { v.currentTime = s.list[i][1]; return; }
    }
  }, true);
})();
"""

    /**
     * Strips ads from the player data YouTube downloads, hides ad banners and
     * fast-forwards (muted) through video ads that still get through.
     */
    private const val AD_BLOCK = """
(function () {
  if (window.__ytbgAds) return;
  window.__ytbgAds = true;

  var AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
  function prune(o) {
    if (!o || typeof o !== 'object') return o;
    AD_KEYS.forEach(function (k) { if (k in o) delete o[k]; });
    if (o.playerResponse) prune(o.playerResponse);
    return o;
  }
  var parse = JSON.parse;
  JSON.parse = function () { return prune(parse.apply(this, arguments)); };
  var json = Response.prototype.json;
  Response.prototype.json = function () { return json.apply(this, arguments).then(prune); };
  var initial;
  try {
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return initial; },
      set: function (v) { initial = prune(v); }
    });
  } catch (e) {}

  var css = 'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,' +
    'ytm-ad-slot-renderer,ad-slot-renderer,ytm-display-ad-renderer,ytm-mealbar-promo-renderer,' +
    'ytm-statement-banner-renderer,ytm-brand-video-singleton-renderer,#player-ads,.video-ads,' +
    '.ytp-ad-module,.ytp-ad-overlay-container{display:none!important}';
  function addCss() {
    var style = document.createElement('style');
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }
  if (document.documentElement) addCss(); else document.addEventListener('DOMContentLoaded', addCss);

  var saved = null;
  setInterval(function () {
    var player = document.querySelector('.html5-video-player');
    var v = document.querySelector('video');
    if (!player || !v) return;
    var skip = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');
    if (skip) skip.click();
    if (player.classList.contains('ad-showing')) {
      if (!saved) saved = { muted: v.muted, rate: v.playbackRate };
      v.muted = true;
      if (v.playbackRate !== 16) v.playbackRate = 16;
    } else if (saved) {
      v.muted = saved.muted;
      v.playbackRate = saved.rate;
      saved = null;
    }
  }, 1000);
})();
"""

    /**
     * Ambient lighting: a blurred copy of the video glows above and below the player, like
     * YouTube's own "ambient mode" on desktop. The frame is drawn into a 16x9 canvas, which
     * scaled up and blurred costs next to nothing; a screen-blended overlay brightens the dark
     * page around the player without touching YouTube's own layout. The colours of the top third
     * (left, middle, right) go to Android for the app header. Dark theme only, only while visible.
     */
    private const val AMBIENT = """
(function () {
  if (window.__ytbgAmbient) return;
  window.__ytbgAmbient = true;
  if (window.__ytbgVisible === undefined) window.__ytbgVisible = true;

  var W = 16, H = 9, BELOW = 200;
  var box = null, ctx = null, shown = false, lastTime = -1, lastMask = '';
  var dark = false, darkCheckedAt = 0, sent = null;

  function create() {
    box = document.createElement('div');
    box.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:0;pointer-events:none;' +
      'z-index:2147483000;mix-blend-mode:screen;opacity:0;transition:opacity .6s;overflow:hidden';
    var canvas = document.createElement('canvas');
    canvas.width = W;
    canvas.height = H;
    canvas.style.cssText = 'display:block;width:100%;height:100%;filter:blur(24px) saturate(1.5);transform:scale(1.25)';
    box.appendChild(canvas);
    document.documentElement.appendChild(box);
    ctx = canvas.getContext('2d', { willReadFrequently: true });
  }

  function pageIsDark() {
    var els = [document.querySelector('ytm-app'), document.body, document.documentElement];
    for (var i = 0; i < els.length; i++) {
      if (!els[i]) continue;
      var c = (getComputedStyle(els[i]).backgroundColor.match(/[\d.]+/g) || []).map(Number);
      if (c.length < 3 || (c.length > 3 && c[3] === 0)) continue;
      return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] < 90;
    }
    return matchMedia('(prefers-color-scheme: dark)').matches;
  }

  function strength() {
    var s = window.__ytbgAmbientStrength;
    return typeof s === 'number' ? s : 0.55;
  }

  /** rgb: [r, g, b] for the left, middle and right of the top of the frame, or null. */
  function send(rgb) {
    if (sent === rgb) return;
    if (rgb && sent && rgb.every(function (x, i) { return Math.abs(x - sent[i]) < 8; })) return;
    sent = rgb;
    var hex = function (x) { return (256 | x).toString(16).slice(1); };
    var text = '';
    if (rgb) {
      for (var i = 0; i < rgb.length; i += 3) {
        text += (i ? ',' : '') + '#' + hex(rgb[i]) + hex(rgb[i + 1]) + hex(rgb[i + 2]);
      }
    }
    try { if (window.YTBG) window.YTBG.onAmbient(text); } catch (e) {}
  }

  function hide() {
    if (shown) { box.style.opacity = '0'; shown = false; }
    lastTime = -1;
    send(null);
  }

  setInterval(function () {
    if (!window.__ytbgVisible) return;
    if (location.pathname.indexOf('/watch') !== 0 || document.fullscreenElement) return hide();
    var v = document.querySelector('.html5-video-player video') || document.querySelector('video');
    if (!v || v.readyState < 2 || !v.videoWidth) return hide();
    var now = Date.now();
    if (now - darkCheckedAt > 2000) { dark = pageIsDark(); darkCheckedAt = now; }
    if (!dark) return hide();
    var r = (v.closest('.html5-video-player') || v).getBoundingClientRect();
    if (r.bottom <= 0 || r.height < 50) return hide();
    if (!box) create();

    // Glow everywhere above the player, then fading out below it; never over the video.
    var top = Math.max(0, Math.round(r.top)), bottom = Math.round(r.bottom);
    var mask = 'linear-gradient(to bottom,#000 0,#000 ' + top + 'px,transparent ' + top + 'px,transparent ' +
      bottom + 'px,#000 ' + bottom + 'px,transparent ' + (bottom + BELOW) + 'px)';
    if (mask !== lastMask) {
      lastMask = mask;
      box.style.height = (bottom + BELOW) + 'px';
      box.style.webkitMaskImage = mask;
      box.style.maskImage = mask;
    }

    if (v.currentTime !== lastTime) {
      lastTime = v.currentTime;
      // Blend into the previous frames so the light drifts instead of flickering.
      ctx.globalAlpha = shown ? 0.3 : 1;
      try { ctx.drawImage(v, 0, 0, W, H); } catch (e) { return; }
      try {
        // The top third of the frame, averaged in three columns: what the glow shows up there.
        var rows = 3, d = ctx.getImageData(0, 0, W, rows).data, sum = [0, 0, 0, 0, 0, 0, 0, 0, 0], n = [0, 0, 0];
        for (var y = 0; y < rows; y++) {
          for (var x = 0; x < W; x++) {
            var p = (y * W + x) * 4, col = Math.min(2, Math.floor(x * 3 / W));
            sum[col * 3] += d[p]; sum[col * 3 + 1] += d[p + 1]; sum[col * 3 + 2] += d[p + 2]; n[col]++;
          }
        }
        send(sum.map(function (v, i) { return Math.round(v / n[Math.floor(i / 3)]); }));
      } catch (e) {}
    }
    var opacity = String(strength());
    if (!shown || box.style.opacity !== opacity) { box.style.opacity = opacity; shown = true; }
  }, 150);
})();
"""

    /** Player "next" button, else the next playlist item, else the first recommended video. */
    const val NEXT = """
(function () {
  var button = document.querySelector('.ytp-next-button:not([aria-disabled="true"])');
  if (button) { button.click(); return; }
  var params = new URLSearchParams(location.search);
  var id = params.get('v');
  var list = params.get('list');
  var links = document.querySelectorAll('a[href*="/watch?v="]');
  if (list) {
    var afterCurrent = false;
    for (var i = 0; i < links.length; i++) {
      var p = new URL(links[i].href).searchParams;
      if (p.get('list') !== list) continue;
      if (p.get('v') === id) { afterCurrent = true; continue; }
      if (afterCurrent) { links[i].click(); return; }
    }
  }
  for (var j = 0; j < links.length; j++) {
    var v = new URL(links[j].href).searchParams.get('v');
    if (v && v !== id) { links[j].click(); return; }
  }
})();
"""

    /** Restart the track if it has been playing for a while, otherwise go back. */
    const val PREVIOUS = """
(function () {
  var v = document.querySelector('video');
  if (v && v.currentTime > 5) { v.currentTime = 0; return; }
  history.back();
})();
"""

    const val PLAY = "(function(){var v=document.querySelector('video');if(v)v.play();})();"

    const val PAUSE = "(function(){var v=document.querySelector('video');if(v)v.pause();})();"

    /** Polled from Android as a fallback for the JavaScript interface. */
    const val STATE = "(window.__ytbgState && document.querySelector('video')) ? window.__ytbgState() : null"

    /** Lowest quality while nobody sees the video: less data and far less decoding work. */
    const val LOW_QUALITY = """
(function () {
  var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
  if (!p || !p.setPlaybackQualityRange) return;
  try {
    if (!window.__ytbgQuality) window.__ytbgQuality = (p.getPlaybackQuality && p.getPlaybackQuality()) || 'auto';
    p.setPlaybackQualityRange('tiny', 'tiny');
    if (p.setPlaybackQuality) p.setPlaybackQuality('tiny');
  } catch (e) {}
})();
"""

    const val RESTORE_QUALITY = """
(function () {
  var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
  var q = window.__ytbgQuality;
  window.__ytbgQuality = null;
  // A page reloaded in the background has no saved quality: go back to automatic.
  if (!q || q === 'unknown') q = 'auto';
  if (!p || !p.setPlaybackQualityRange) return;
  try {
    p.setPlaybackQualityRange(q, q);
    if (p.setPlaybackQuality) p.setPlaybackQuality(q);
  } catch (e) {}
})();
"""

    /** [videoId] must already be validated: it is pasted into the script. */
    fun segments(videoId: String, segments: List<Segment>): String {
        val list = segments.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
        return "window.__ytbgSegments={id:'$videoId',list:$list};"
    }

    fun seekTo(positionMs: Long): String =
        "(function(){var v=document.querySelector('video');if(v)v.currentTime=${positionMs / 1000.0};})();"
}
