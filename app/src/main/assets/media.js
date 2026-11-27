/* Trình Duyệt — dò tìm video đang phát để hiện nút tải */
(function () {
  if (window.__qdlRan) return;
  window.__qdlRan = true;
  var B = window.QuangDL;
  if (!B) return;
  var sent = {};
  var abs = function (u) {
    try { return new URL(u, location.href).href; } catch (e) { return u; }
  };
  var rep = function (u, how) {
    try {
      if (!u || typeof u !== 'string') return;
      u = abs(u);
      if (!/^https?:/i.test(u) || sent[u]) return;
      sent[u] = 1;
      var title = '';
      try { title = (window.top && window.top.document && window.top.document.title) || document.title || ''; }
      catch (e) { title = document.title || ''; }
      B.found(u, location.href, title, how || '');
    } catch (e) {}
  };

  /* thẻ <video>/<audio> bắt đầu phát */
  var onMedia = function (e) {
    var v = e.target;
    if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO')) return;
    rep(v.currentSrc || v.src, 'play');
    var s = v.querySelectorAll ? v.querySelectorAll('source') : [];
    for (var i = 0; i < s.length; i++) rep(s[i].src, 'source');
  };
  document.addEventListener('play', onMedia, true);
  document.addEventListener('loadedmetadata', onMedia, true);

  /* danh sách phát HLS tải bằng XHR (kể cả khi đuôi tệp bị giấu) */
  try {
    var XO = XMLHttpRequest.prototype.open;
    var XS = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__qu = u;
      return XO.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      x.addEventListener('load', function () {
        try {
          var url = x.responseURL || x.__qu;
          var ct = (x.getResponseHeader('content-type') || '').toLowerCase();
          if (ct.indexOf('mpegurl') >= 0) { rep(url, 'm3u8'); return; }
          if ((x.responseType === '' || x.responseType === 'text') && typeof x.responseText === 'string'
              && x.responseText.slice(0, 7) === '#EXTM3U') rep(url, 'm3u8');
        } catch (e) {}
      });
      return XS.apply(this, arguments);
    };
  } catch (e) {}

  /* fetch() */
  try {
    var F = window.fetch;
    if (F) {
      window.fetch = function () {
        return F.apply(this, arguments).then(function (r) {
          try {
            var ct = (r.headers.get('content-type') || '').toLowerCase();
            if (ct.indexOf('mpegurl') >= 0) rep(r.url, 'm3u8');
          } catch (e) {}
          return r;
        });
      };
    }
  } catch (e) {}
})();
