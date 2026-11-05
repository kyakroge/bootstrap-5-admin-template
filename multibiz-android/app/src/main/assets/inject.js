/*
 * MultiBiz page bridge - injected into https://web.whatsapp.com at document start.
 * 1. Replaces the browser Notification API so every web notification becomes a
 *    native Android notification labelled with the account name.
 * 2. Reports unread count + login state (QR / ready) to the app.
 * 3. Makes blob: downloads (media, documents) work inside Android WebView.
 */
(function () {
  'use strict';
  if (window.__mbInjected) return;
  window.__mbInjected = true;
  if (window.top !== window) return;

  var B = window.MultiBizBridge;
  function post(o) { try { if (B) B.postMessage(JSON.stringify(o)); } catch (e) {} }
  function s(x) { try { return x == null ? '' : String(x); } catch (e) { return ''; } }

  // ---- look like desktop Chrome (WhatsApp Web refuses mobile browsers)
  try { Object.defineProperty(Navigator.prototype, 'userAgentData', { get: function () { return undefined; }, configurable: true }); } catch (e) {}
  try { Object.defineProperty(Navigator.prototype, 'platform', { get: function () { return 'Linux x86_64'; }, configurable: true }); } catch (e) {}

  // ---- Notification API shim
  var map = {}, order = [], seq = 0;
  function MBNotification(title, options) {
    if (!(this instanceof MBNotification)) throw new TypeError("Failed to construct 'Notification'");
    options = options || {};
    var self = this;
    this.title = s(title);
    this.body = s(options.body);
    this.tag = s(options.tag);
    this.icon = s(options.icon);
    this.data = options.data === undefined ? null : options.data;
    this.silent = !!options.silent;
    this.onclick = null; this.onshow = null; this.onclose = null; this.onerror = null;
    this._l = {};
    this._id = 'n' + (++seq) + '_' + Date.now();
    map[this._id] = this;
    order.push(this._id);
    if (order.length > 300) delete map[order.shift()];
    post({ t: 'notify', id: this._id, title: this.title, body: this.body, tag: this.tag, silent: this.silent });
    setTimeout(function () { self._fire('show'); }, 0);
  }
  MBNotification.prototype._fire = function (type) {
    var ev = { type: type, target: this, currentTarget: this, preventDefault: function () {}, stopPropagation: function () {} };
    var h = this['on' + type];
    if (typeof h === 'function') { try { h.call(this, ev); } catch (e) {} }
    var ls = this._l[type] || [];
    for (var i = 0; i < ls.length; i++) { try { ls[i].call(this, ev); } catch (e) {} }
  };
  MBNotification.prototype.addEventListener = function (t, fn) { (this._l[t] = this._l[t] || []).push(fn); };
  MBNotification.prototype.removeEventListener = function (t, fn) {
    var ls = this._l[t]; if (!ls) return; var i = ls.indexOf(fn); if (i >= 0) ls.splice(i, 1);
  };
  MBNotification.prototype.dispatchEvent = function (ev) { if (ev && ev.type) this._fire(ev.type); return true; };
  MBNotification.prototype.close = function () {
    post({ t: 'cancel', tag: this.tag, id: this._id });
    this._fire('close');
  };
  Object.defineProperty(MBNotification, 'permission', { get: function () { return 'granted'; } });
  MBNotification.requestPermission = function (cb) {
    if (typeof cb === 'function') { try { cb('granted'); } catch (e) {} }
    return Promise.resolve('granted');
  };
  MBNotification.maxActions = 0;
  try {
    Object.defineProperty(window, 'Notification', { value: MBNotification, writable: true, configurable: true });
  } catch (e) { window.Notification = MBNotification; }

  // Native notification tapped -> run the page's own click handler (opens that chat).
  window.__mbNotifClick = function (id) {
    try { window.focus(); } catch (e) {}
    var n = map[id];
    if (n) n._fire('click');
  };

  try {
    if (navigator.permissions && navigator.permissions.query) {
      var oq = navigator.permissions.query.bind(navigator.permissions);
      navigator.permissions.query = function (d) {
        if (d && d.name === 'notifications') {
          return Promise.resolve({ state: 'granted', status: 'granted', onchange: null,
            addEventListener: function () {}, removeEventListener: function () {} });
        }
        return oq(d);
      };
    }
  } catch (e) {}

  try {
    if (window.ServiceWorkerRegistration && ServiceWorkerRegistration.prototype) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, opts) {
        opts = opts || {};
        post({ t: 'notify', id: 'sw_' + Date.now(), title: s(title), body: s(opts.body), tag: s(opts.tag), silent: !!opts.silent });
        return Promise.resolve();
      };
      ServiceWorkerRegistration.prototype.getNotifications = function () { return Promise.resolve([]); };
    }
  } catch (e) {}

  // ---- blob: downloads
  var origRevoke = URL.revokeObjectURL;
  URL.revokeObjectURL = function (u) { var a = arguments; setTimeout(function () { origRevoke.apply(URL, a); }, 60000); };

  window.__mbDownloadBlob = function (url, name, mime) {
    fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onloadend = function () { post({ t: 'blob', data: s(fr.result), name: s(name), mime: s(mime || b.type) }); };
      fr.readAsDataURL(b);
    }).catch(function (e) { post({ t: 'log', m: 'blob download failed: ' + e }); });
  };
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.href && this.href.indexOf('blob:') === 0 && this.hasAttribute('download')) {
      window.__mbDownloadBlob(this.href, this.getAttribute('download') || '', '');
      return;
    }
    return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download][href^="blob:"]') : null;
    if (a) { ev.preventDefault(); ev.stopPropagation(); window.__mbDownloadBlob(a.href, a.getAttribute('download') || '', ''); }
  }, true);

  // ---- layout width (set from the app menu)
  window.__mbViewport = function (w) {
    var apply = function () {
      var m = document.querySelector('meta[name=viewport]');
      if (!m) { m = document.createElement('meta'); m.name = 'viewport'; (document.head || document.documentElement).appendChild(m); }
      m.content = w > 0 ? ('width=' + w + ', user-scalable=yes') : 'width=device-width, initial-scale=1, user-scalable=yes';
    };
    if (document.head) apply(); else document.addEventListener('DOMContentLoaded', apply);
  };

  // ---- state + unread reporting
  var lastUnread = -1, lastState = '', ticks = 0;
  function detectState() {
    try {
      if (document.querySelector('#pane-side, #side, [data-testid="chat-list"], div[aria-label="Chat list"], div[role="grid"][aria-label]')) return 'ready';
      if (document.querySelector('canvas[aria-label*="QR" i], canvas[aria-label*="scan" i], div[data-ref] canvas, [data-testid="qrcode"], div[data-ref]')) return 'qr';
      var t = (document.body && document.body.innerText) || '';
      if (/scan to log in|log in with phone number|link with phone number|enter code on phone|log into whatsapp web/i.test(t)) return 'qr';
    } catch (e) {}
    return 'loading';
  }
  // ---- own profile photo (DP) -> shown on the MultiBiz home screen
  var lastAvatar = '';
  function ownUser() {
    try {
      var v = localStorage.getItem('last-wid-md') || localStorage.getItem('last-wid') || '';
      v = v.replace(/"/g, '');
      if (!v) return '';
      return v.split('@')[0].split(':')[0];
    } catch (e) { return ''; }
  }
  function avatarFromIdb(cb) {
    var user = ownUser();
    if (!user || !window.indexedDB) return cb('');
    var done = false;
    function finish(u) { if (!done) { done = true; cb(u || ''); } }
    var req;
    try { req = indexedDB.open('model-storage'); } catch (e) { return finish(''); }
    req.onupgradeneeded = function () { try { req.transaction.abort(); } catch (e) {} }; // never create the DB
    req.onerror = function () { finish(''); };
    req.onblocked = function () { finish(''); };
    req.onsuccess = function () {
      var db = req.result;
      try {
        if (!db.objectStoreNames.contains('profile-pic-thumb')) { db.close(); return finish(''); }
        var st = db.transaction('profile-pic-thumb', 'readonly').objectStore('profile-pic-thumb');
        var keys = [user + '@c.us', user + '@s.whatsapp.net'], i = 0;
        (function next() {
          if (i >= keys.length) { db.close(); return finish(''); }
          var g = st.get(keys[i++]);
          g.onsuccess = function () {
            var r = g.result, u = r && (r.eurl || r.previewEurl);
            if (u) { db.close(); finish(String(u)); } else next();
          };
          g.onerror = function () { next(); };
        })();
      } catch (e) { try { db.close(); } catch (x) {} finish(''); }
    };
  }
  function avatarFromDom() {
    try {
      var imgs = document.querySelectorAll(
        'header img, [role="navigation"] img, [aria-label="Profile" i] img, [title="Profile" i] img');
      for (var i = 0; i < imgs.length; i++) {
        var im = imgs[i];
        if (im.closest('#pane-side, #main, [role="dialog"], [role="listitem"], [role="row"]')) continue;
        if (/^https:\/\/[^/]*whatsapp\.net\//.test(im.src || '')) return im.src;
      }
    } catch (e) {}
    return '';
  }
  function checkAvatar() {
    if (lastState !== 'ready') return;
    avatarFromIdb(function (u) {
      u = u || avatarFromDom();
      if (u && u !== lastAvatar) { lastAvatar = u; post({ t: 'avatar', url: u }); }
    });
  }

  function tick() {
    var m = /^\((\d+)\)/.exec(document.title || '');
    var c = m ? parseInt(m[1], 10) : 0;
    if (c !== lastUnread) { lastUnread = c; post({ t: 'unread', n: c }); }
    var st = detectState();
    if (st !== lastState) {
      lastState = st; post({ t: 'state', s: st });
      if (st === 'ready') { setTimeout(checkAvatar, 4000); setTimeout(checkAvatar, 20000); }
    }
    if (ticks % 150 === 0) checkAvatar();
    if (++ticks % 30 === 0) post({ t: 'hb' });
  }
  setInterval(tick, 2000);
  if (document.readyState !== 'loading') tick(); else document.addEventListener('DOMContentLoaded', tick);
})();
