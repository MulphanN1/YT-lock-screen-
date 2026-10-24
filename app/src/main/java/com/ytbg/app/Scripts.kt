package com.ytbg.app

object Scripts {

    /**
     * Injected into every page at document start. Makes the page believe it is always
     * visible (so YouTube does not pause itself) and reports play/pause state to Android.
     */
    const val PAGE = """
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

  function report() {
    try {
      var v = document.querySelector('video');
      var playing = !!(v && !v.paused && !v.ended);
      var title = (document.title || '').replace(/\s*-\s*YouTube( Music)?$/, '');
      if (window.YTBG) window.YTBG.onState(playing, title);
    } catch (e) {}
  }
  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadedmetadata'].forEach(function (n) {
    document.addEventListener(n, report, true);
  });
})();
"""

    const val PLAY = "(function(){var v=document.querySelector('video');if(v)v.play();})();"

    const val PAUSE = "(function(){var v=document.querySelector('video');if(v)v.pause();})();"
}
