package com.ytbg.app

object Scripts {

    fun page(adBlock: Boolean): String = if (adBlock) PAGE + AD_BLOCK else PAGE

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
      v && isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0
    ];
  }
  function report() {
    try {
      if (window.YTBG && document.querySelector('video')) window.YTBG.onState(JSON.stringify(state()));
    } catch (e) {}
  }
  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadedmetadata', 'durationchange', 'seeked', 'ratechange']
    .forEach(function (n) { document.addEventListener(n, report, true); });
  // Catches title/artwork that arrive late and anything the events miss.
  setInterval(report, 2000);
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
  }, 500);
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

    fun seekTo(positionMs: Long): String =
        "(function(){var v=document.querySelector('video');if(v)v.currentTime=${positionMs / 1000.0};})();"
}
