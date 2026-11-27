/* Trình Duyệt — mã chặn quảng cáo chạy trước khi trang tải */
(function () {
  if (window.__qabRan) return;
  window.__qabRan = true;
  var host = location.hostname || '';
  try {
    if (window.QuangAB && !window.QuangAB.on(host)) return;
  } catch (e) {}

  var isYT = /(^|\.)youtube\.com$/.test(host) || /(^|\.)youtube-nocookie\.com$/.test(host);

  /* ---------- 1. YouTube: xoá dữ liệu quảng cáo khỏi phản hồi của máy chủ ---------- */
  if (isYT) {
    var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adBreakParams'];
    var prune = function (o) {
      if (!o || typeof o !== 'object') return o;
      try {
        for (var i = 0; i < KEYS.length; i++) {
          if (Object.prototype.hasOwnProperty.call(o, KEYS[i])) delete o[KEYS[i]];
        }
        if (o.playerResponse && typeof o.playerResponse === 'object') prune(o.playerResponse);
        if (Array.isArray(o)) {
          for (var j = 0; j < o.length && j < 10; j++) {
            if (o[j] && o[j].playerResponse) prune(o[j].playerResponse);
          }
        }
      } catch (e) {}
      return o;
    };
    var origParse = JSON.parse;
    JSON.parse = function () {
      return prune(origParse.apply(this, arguments));
    };
    try {
      var origJson = Response.prototype.json;
      Response.prototype.json = function () {
        return origJson.apply(this, arguments).then(prune);
      };
    } catch (e) {}
    ['ytInitialPlayerResponse', 'playerResponse'].forEach(function (name) {
      try {
        var store;
        Object.defineProperty(window, name, {
          configurable: true,
          get: function () { return store; },
          set: function (v) { store = prune(v); }
        });
      } catch (e) {}
    });
  }

  /* ---------- 2. Ẩn khung quảng cáo trên trang ---------- */
  var css = [
    'ins.adsbygoogle', '.adsbygoogle', '[id^="google_ads_"]', '[id^="div-gpt-ad"]',
    'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication"]', 'iframe[id^="google_ads_iframe"]',
    'iframe[src*="adnxs"]', '[data-ad-slot]', '[data-ad-client]', 'amp-ad', 'amp-sticky-ad',
    '.ad-banner', '.ads-banner', '.banner-ads', '.banner-ad', '.adsbox', '.ad-container', '.ads-container',
    '.advertisement', '#advertisement', '.qc-banner', '.box-qc', '.quangcao', '.quang-cao',
    '[id*="catfish" i]', '[class*="catfish" i]', '[id*="balloon-ads" i]', '[class*="popup-ads" i]',
    '[id*="sticky-ad" i]', '[class*="sticky-ad" i]', '[class*="floating-ad" i]', '[id*="floating-ad" i]',
    '[id^="ads-"]', '[id^="ads_"]', '[class^="ads-"]', '[class^="ads_"]',
    /* YouTube */
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer',
    'ytm-companion-slot', 'ad-slot-renderer', 'ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer',
    'ytd-banner-promo-renderer', 'ytd-promoted-sparkles-web-renderer', 'ytd-display-ad-renderer',
    'ytd-player-legacy-desktop-watch-ads-renderer', '#player-ads', '#masthead-ad',
    'ytm-mealbar-promo-renderer', 'ytd-mealbar-promo-renderer', 'ytm-paid-content-overlay-renderer',
    '.ytp-ad-overlay-container', '.ytp-ad-image-overlay', '.ytp-ad-text-overlay', '.ytm-promoted-sparkles-text-search-root',
    'ytm-rich-item-renderer:has(ad-slot-renderer)', 'ytd-rich-item-renderer:has(ytd-ad-slot-renderer)',
    'ytm-item-section-renderer:has(ytm-promoted-sparkles-web-renderer)'
  ].map(function (x) {
    return x + '{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important;}';
  }).join('\n');

  var addStyle = function () {
    var root = document.head || document.documentElement;
    if (!root) return false;
    var s = document.createElement('style');
    s.id = '__qab_css';
    s.textContent = css;
    root.appendChild(s);
    return true;
  };
  if (!addStyle()) document.addEventListener('DOMContentLoaded', addStyle);

  /* ---------- 3. Bỏ qua quảng cáo trong video ---------- */
  var mutedByUs = false;
  var rateByUs = false;
  var clicked = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
  var SKIP_TEXT = /^\s*(bỏ qua( quảng cáo| qc)?|skip( ad| ads)?|skip ad[s]?|bo qua quang cao|close ad|đóng quảng cáo)\s*[»>›▶▸▶]*\s*$/i;

  var tryClick = function (el) {
    if (!el || (clicked && clicked.has(el))) return;
    var r = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
    if (r && (r.width === 0 || r.height === 0)) return;
    if (clicked) clicked.add(el);
    try { el.click(); } catch (e) {}
  };

  var ytTick = function () {
    var player = document.querySelector('.ad-showing, .ad-interrupting');
    var video = document.querySelector('#movie_player video, .html5-video-player video, video');
    if (player && video) {
      if (!video.muted) { video.muted = true; mutedByUs = true; }
      try {
        if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.2) {
          video.currentTime = video.duration;
        }
        if (video.playbackRate < 16) { video.playbackRate = 16; rateByUs = true; }
      } catch (e) {}
    } else if (video) {
      if (mutedByUs) { video.muted = false; mutedByUs = false; }
      if (rateByUs) { video.playbackRate = 1; rateByUs = false; }
    }
    var btns = document.querySelectorAll(
      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button,' +
      ' .ytp-ad-overlay-close-button, .ytm-skip-ad-button, button[class*="skip-ad"], .ytp-ad-skip-button-container');
    for (var i = 0; i < btns.length; i++) { try { btns[i].click(); } catch (e) {} }
  };

  var genericTick = function () {
    /* JW Player, Video.js, các trình phát phim phổ biến */
    var sel = document.querySelectorAll(
      '.jw-flag-ads .jw-skip, .jw-skip.jw-skippable, .vjs-ad-playing .vjs-skip-ad, .videoAdUiSkipButton,' +
      ' [class*="skip-ad" i], [id*="skip-ad" i], [class*="skipAd"], [id*="skipAd"], [class*="ad-skip" i], [class*="btn-skip" i]');
    for (var i = 0; i < sel.length; i++) tryClick(sel[i]);
    var cands = document.querySelectorAll('button, a, div[role="button"], span[role="button"], .btn, [onclick]');
    for (var j = 0; j < cands.length && j < 400; j++) {
      var t = cands[j].textContent;
      if (t && t.length < 40 && SKIP_TEXT.test(t)) tryClick(cands[j]);
    }
    /* Video quảng cáo của JW Player: tua đến cuối */
    var jw = document.querySelectorAll('.jw-flag-ads video');
    for (var k = 0; k < jw.length; k++) {
      try {
        jw[k].muted = true;
        if (isFinite(jw[k].duration) && jw[k].duration > 0) jw[k].currentTime = jw[k].duration;
      } catch (e) {}
    }
  };

  var tick = isYT ? ytTick : genericTick;
  var timer = null;
  var schedule = function () {
    if (timer) return;
    timer = setTimeout(function () { timer = null; try { tick(); } catch (e) {} }, 80);
  };
  var startObserver = function () {
    try {
      new MutationObserver(schedule).observe(document.documentElement, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['class']
      });
    } catch (e) {}
  };
  if (document.documentElement) startObserver();
  else document.addEventListener('DOMContentLoaded', startObserver);
  setInterval(function () { try { tick(); } catch (e) {} }, isYT ? 300 : 1000);
})();
