/*
 * MultiBiz Group Grabber - runs inside web.whatsapp.com on demand.
 *   list(req)                 -> groups this account is in
 *   members(req, ids)         -> participants (number, names, role) per group
 *   chat(req, ids, limit)     -> messages per group
 *   diag(req)                 -> what data sources are available (troubleshooting)
 * Two data sources, tried in order:
 *   1. WhatsApp Web's in-memory collections (window.require('WAWebCollections'))
 *   2. WhatsApp Web's local IndexedDB cache ('model-storage')
 * Results go back to the app via the MultiBizBridge (t:'grab').
 */
(function () {
  'use strict';
  if (window.__mbGrabber) return;

  var B = window.MultiBizBridge;
  function post(o) { o.t = 'grab'; try { if (B) B.postMessage(JSON.stringify(o)); } catch (e) {} }
  function s(x) { try { return x == null ? '' : String(x); } catch (e) { return ''; } }
  function mod(n) { try { return typeof window.require === 'function' ? window.require(n) : null; } catch (e) { return null; } }
  function withTimeout(p, ms) {
    return Promise.race([Promise.resolve(p), new Promise(function (_, rej) { setTimeout(function () { rej(new Error('timeout')); }, ms); })]);
  }
  function models(c) {
    if (!c) return [];
    try {
      if (typeof c.getModelsArray === 'function') return c.getModelsArray();
      if (Array.isArray(c._models)) return c._models;
      if (Array.isArray(c.models)) return c.models;
      if (Array.isArray(c)) return c;
      if (typeof c.toArray === 'function') return c.toArray();
    } catch (e) {}
    return [];
  }
  function ser(w) {
    if (!w) return '';
    if (typeof w === 'string') return w;
    if (w._serialized) return s(w._serialized);
    if (w.user) return w.user + '@' + (w.server || 'c.us');
    return '';
  }
  function userOf(id) { return ser(id).split('@')[0].split(':')[0]; }
  function serverOf(id) { return ser(id).split('@')[1] || ''; }
  function isPhoneServer(sv) { return sv === 'c.us' || sv === 's.whatsapp.net'; }
  function isGroupId(id) { return /@g\.us$/.test(ser(id)); }

  function ownUser() {
    try {
      var v = (localStorage.getItem('last-wid-md') || localStorage.getItem('last-wid') || '').replace(/"/g, '');
      return v ? v.split('@')[0].split(':')[0] : '';
    } catch (e) { return ''; }
  }

  // ---------------------------------------------------------------- source 1: live collections
  var S = null;
  function store() {
    if (S) return S;
    var c = mod('WAWebCollections');
    if (c && c.Chat) S = c;
    return S;
  }
  function groupMetaColl() {
    var st = store();
    return st ? (st.GroupMetadata || st.WAWebGroupMetadataCollection || null) : null;
  }
  function makeWid(id) {
    var f = mod('WAWebWidFactory');
    try { if (f && f.createWid) return f.createWid(id); } catch (e) {}
    return id;
  }

  // ---------------------------------------------------------------- source 2: IndexedDB cache
  function idbAll(name, range) {
    return new Promise(function (res) {
      var r;
      try { r = indexedDB.open('model-storage'); } catch (e) { return res([]); }
      r.onupgradeneeded = function () { try { r.transaction.abort(); } catch (e) {} };
      r.onerror = function () { res([]); };
      r.onblocked = function () { res([]); };
      r.onsuccess = function () {
        var db = r.result;
        try {
          if (!db.objectStoreNames.contains(name)) { db.close(); return res([]); }
          var q = db.transaction(name, 'readonly').objectStore(name).getAll(range || null);
          q.onsuccess = function () { db.close(); res(q.result || []); };
          q.onerror = function () { db.close(); res([]); };
        } catch (e) { try { db.close(); } catch (x) {} res([]); }
      };
    });
  }
  function idbStoreNames() {
    return new Promise(function (res) {
      var r;
      try { r = indexedDB.open('model-storage'); } catch (e) { return res([]); }
      r.onupgradeneeded = function () { try { r.transaction.abort(); } catch (e) {} };
      r.onerror = function () { res([]); };
      r.onsuccess = function () { var n = Array.prototype.slice.call(r.result.objectStoreNames); r.result.close(); res(n); };
    });
  }

  var idbContacts = null, lidPn = {}, lidNames = {}, warmed = false;
  function lidKey(x) { var v = ser(x); return v ? (v.indexOf('@') < 0 ? v + '@lid' : v) : ''; }
  async function loadIdbContacts() {
    if (idbContacts) return idbContacts;
    var m = {};
    (await idbAll('contact')).forEach(function (c) { if (c && c.id) m[ser(c.id)] = c; });
    (await idbAll('lid-pn-mapping')).forEach(function (r) {
      try { var pn = digits(r.phoneNumber || r.pn || ''); if (r.id && pn) lidPn[lidKey(r.id)] = pn; } catch (e) {}
    });
    (await idbAll('lid-display-name-mapping')).forEach(function (r) {
      try { var n = s(r.displayName || r.name || r.pushname || ''); if (r.id && n) lidNames[lidKey(r.id)] = n; } catch (e) {}
    });
    idbContacts = m;
    // Ask WhatsApp to load its hidden-id -> number table (best effort).
    if (!warmed) {
      warmed = true;
      try { var api = mod('WAWebApiContact'); if (api && api.warmUpAllLidPnMappings) await withTimeout(api.warmUpAllLidPnMappings(), 8000); } catch (e) {}
    }
    return m;
  }

  // ---------------------------------------------------------------- helpers
  function contactOf(id) {
    var sid = ser(id), st = store();
    if (st && st.Contact) {
      try { var c = st.Contact.get(makeWid(sid)); if (c) return c; } catch (e) {}
    }
    return (idbContacts && idbContacts[sid]) || null;
  }

  function digits(x) { var u = userOf(x); return /^\d{6,16}$/.test(u) ? u : ''; }

  /** Phone number (digits incl. country code) or '' when WhatsApp hides it. */
  function phoneOf(id, extra) {
    var sid = ser(id);
    if (isPhoneServer(serverOf(sid))) return digits(sid);
    var cand = [];
    if (extra) { try { cand.push(extra.phoneNumber, extra.pn, extra.authorPn); } catch (e) {} }
    var c = contactOf(sid);
    if (c) { try { cand.push(c.phoneNumber, c.pn); } catch (e) {} }
    for (var i = 0; i < cand.length; i++) {
      var p = ser(cand[i]);
      if (p && (serverOf(p) === '' || isPhoneServer(serverOf(p))) && digits(p)) return digits(p);
    }
    if (lidPn[sid]) return lidPn[sid];
    var api = mod('WAWebApiContact'), w = makeWid(sid);
    try { if (api && api.getPhoneNumber) { var pn = api.getPhoneNumber(w); if (pn && digits(pn)) return digits(pn); } } catch (e) {}
    try { if (api && api.getAlternateUserWid) { var alt = api.getAlternateUserWid(w); if (alt && isPhoneServer(serverOf(alt)) && digits(alt)) return digits(alt); } } catch (e) {}
    return '';
  }

  function namesOf(id) {
    var c = contactOf(id) || {};
    var name = '', push = '', saved = false;
    try { name = s(c.name || c.verifiedName || ''); } catch (e) {}
    try { push = s(c.pushname || c.notifyName || '') || lidNames[ser(id)] || ''; } catch (e) {}
    try { saved = !!(c.isMyContact || c.isAddressBookContact || c.name); } catch (e) {}
    return { name: name, push: push, saved: saved };
  }

  // ---------------------------------------------------------------- list groups
  async function listGroups() {
    var out = {}, st = store();
    if (st) {
      models(st.Chat).forEach(function (c) {
        var id = ser(c.id);
        if (!isGroupId(id)) return;
        var gm = null, size = 0, name = '';
        try { gm = c.groupMetadata; } catch (e) {}
        try { size = gm && gm.participants ? models(gm.participants).length : 0; } catch (e) {}
        try { name = s(c.formattedTitle || c.name || (gm && gm.subject) || ''); } catch (e) {}
        out[id] = { id: id, name: name, size: size, t: Number(c.t) || 0 };
      });
    }
    // Fill gaps from the IndexedDB cache.
    var meta = await idbAll('group-metadata'), parts = await idbAll('participant'), chats = await idbAll('chat');
    chats.forEach(function (c) {
      var id = ser(c.id);
      if (!isGroupId(id)) return;
      var g = out[id] || (out[id] = { id: id, name: '', size: 0, t: 0 });
      g.name = g.name || s(c.name || c.formattedTitle || '');
      g.t = g.t || Number(c.t) || 0;
    });
    meta.forEach(function (m) {
      var id = ser(m.id);
      if (!isGroupId(id)) return;
      var g = out[id] || (out[id] = { id: id, name: '', size: 0, t: 0 });
      g.name = g.name || s(m.subject || '');
      if (m.size && !g.size) g.size = Number(m.size) || 0;
    });
    parts.forEach(function (p) {
      var id = ser(p.groupId || p.id);
      if (!out[id]) return;
      var n = (p.participants || []).length;
      if (n > out[id].size) out[id].size = n;
    });
    var list = Object.keys(out).map(function (k) { var g = out[k]; if (!g.name) g.name = 'Group ' + userOf(g.id); return g; });
    list.sort(function (a, b) { return (b.t || 0) - (a.t || 0) || a.name.localeCompare(b.name); });
    return list;
  }

  // ---------------------------------------------------------------- members
  async function membersOf(gid) {
    var raw = [], name = await collectMembers(gid, raw);
    return { name: name, rows: raw.map(function (x) { return { phone: x.phone, name: x.name, push: x.push, saved: x.saved, role: x.role }; }) };
  }
  async function membersRaw(gid) { var raw = []; await collectMembers(gid, raw); return raw; }

  /** Fills [rows] (with sid) and returns the group name. */
  async function collectMembers(gid, rows) {
    await loadIdbContacts();
    var st = store(), name = '', seen = {}, me = ownUser();
    function add(pid, role, extra) {
      var sid = ser(pid);
      if (!sid || seen[sid]) return;
      seen[sid] = 1;
      var n = namesOf(sid), phone = phoneOf(sid, extra);
      if (phone && phone === me) { n.name = 'You'; }
      rows.push({ sid: sid, phone: phone, name: n.name, push: n.push, saved: n.saved, role: role });
    }
    if (st) {
      var w = makeWid(gid), gm = null;
      var GM = groupMetaColl();
      try { if (GM && GM.update) await withTimeout(GM.update(w), 12000); } catch (e) {}
      try {
        var q = mod('WAWebGroupQueryJob');
        if (q && q.queryAndUpdateGroupMetadataById) await withTimeout(q.queryAndUpdateGroupMetadataById({ id: w }), 12000);
      } catch (e) {}
      try { gm = GM && GM.get(w); } catch (e) {}
      try { var ch = st.Chat.get(w); if (ch) { name = s(ch.formattedTitle || ch.name); if (!gm) gm = ch.groupMetadata; } } catch (e) {}
      if (gm) {
        try { name = name || s(gm.subject); } catch (e) {}
        models(gm.participants).forEach(function (p) {
          var role = 'Member';
          try { role = p.isSuperAdmin ? 'Super Admin' : (p.isAdmin ? 'Admin' : 'Member'); } catch (e) {}
          add(p.id, role, p);
        });
      }
    }
    if (!rows.length) {
      var parts = await idbAll('participant'), meta = await idbAll('group-metadata');
      meta.forEach(function (m) { if (ser(m.id) === gid) name = name || s(m.subject); });
      parts.forEach(function (p) {
        if (ser(p.groupId || p.id) !== gid) return;
        var admins = {}, supers = {};
        (p.admins || []).forEach(function (a) { admins[ser(a)] = 1; });
        (p.superAdmins || []).forEach(function (a) { supers[ser(a)] = 1; });
        (p.participants || []).forEach(function (x) {
          var sid = ser(x && x.id ? x.id : x);
          add(sid, supers[sid] ? 'Super Admin' : (admins[sid] ? 'Admin' : 'Member'), typeof x === 'object' ? x : null);
        });
      });
    }
    var order = { 'Super Admin': 0, 'Admin': 1, 'Member': 2 };
    rows.sort(function (a, b) { return order[a.role] - order[b.role]; });
    return name;
  }

  // ---------------------------------------------------------------- chat
  var SKIP = { e2e_notification: 1, notification_template: 1, notification: 1, protocol: 1, ciphertext: 1,
    gp2: 1, call_log: 1, broadcast_notification: 1, debug: 1, unknown: 1, reaction: 1, keep_in_chat: 1, pin_message: 1 };

  function looksBinary(t) { return t.length > 200 && !/\s/.test(t.slice(0, 200)); }
  function msgText(m) {
    var type = s(m.type), cap = '', body = '';
    try { cap = s(m.caption); } catch (e) {}
    try { body = typeof m.body === 'string' ? m.body : ''; } catch (e) {}
    switch (type) {
      case 'chat': return body;
      case 'image': return '[Photo]' + (cap ? ' ' + cap : '');
      case 'video': return '[Video]' + (cap ? ' ' + cap : '');
      case 'gif': return '[GIF]' + (cap ? ' ' + cap : '');
      case 'document': return '[Document] ' + s(m.filename || cap);
      case 'ptt': return '[Voice note]';
      case 'audio': return '[Audio]';
      case 'sticker': return '[Sticker]';
      case 'location': return '[Location] ' + s(m.lat) + ',' + s(m.lng) + (m.loc ? ' ' + s(m.loc) : '');
      case 'vcard': return '[Contact] ' + s(m.vcardFormattedName);
      case 'multi_vcard': return '[Contacts]';
      case 'poll_creation': return '[Poll] ' + s(m.pollName);
      case 'revoked': return '[This message was deleted]';
      case 'event_creation': return '[Event] ' + s(m.eventName);
      default:
        if (cap) return cap;
        if (body && !looksBinary(body)) return body;
        return '[' + (type || 'message') + ']';
    }
  }

  /** sender id -> phone, from the group's participant list (resolves hidden-id senders). */
  async function participantPhones(gid) {
    var map = {};
    try {
      var r = await membersRaw(gid);
      r.forEach(function (x) { if (x.phone) map[x.sid] = x.phone; });
    } catch (e) {}
    return map;
  }

  async function chatOf(gid, limit, req) {
    await loadIdbContacts();
    var st = store(), list = [], name = '', me = ownUser();
    var pmap = await participantPhones(gid);
    if (st) {
      var w = makeWid(gid), chat = null;
      try { chat = st.Chat.get(w); } catch (e) {}
      if (chat) {
        try { name = s(chat.formattedTitle || chat.name); } catch (e) {}
        var loader = mod('WAWebChatLoadMessages'), guard = 0;
        list = models(chat.msgs);
        while (!G.cancel && list.length < limit && loader && loader.loadEarlierMsgs && guard++ < 3000) {
          var before = list.length;
          try { await withTimeout(loader.loadEarlierMsgs(chat), 20000); } catch (e) { break; }
          list = models(chat.msgs);
          post({ ev: 'progress', req: req, n: list.length });
          if (list.length <= before) break;
          try { if (chat.msgs.msgLoadState && chat.msgs.msgLoadState.noEarlierMsgs) break; } catch (e) {}
        }
      }
    }
    if (!list.length) {
      var a = await idbAll('message', IDBKeyRange.bound('false_' + gid + '_', 'false_' + gid + '_￿'));
      var b = await idbAll('message', IDBKeyRange.bound('true_' + gid + '_', 'true_' + gid + '_￿'));
      list = a.concat(b);
    }
    if (!name) {
      (await idbAll('group-metadata')).forEach(function (m) { if (ser(m.id) === gid) name = s(m.subject); });
    }
    list = list.filter(function (m) { var ty = ''; try { ty = s(m.type); } catch (e) {} return !SKIP[ty]; });
    list.sort(function (x, y) { return (Number(x.t) || 0) - (Number(y.t) || 0); });
    if (list.length > limit) list = list.slice(list.length - limit);
    var rows = [];
    list.forEach(function (m) {
      var type = '';
      try { type = s(m.type); } catch (e) {}
      var fromMe = false;
      try { fromMe = !!(m.id && m.id.fromMe) || /^true_/.test(ser(m.id)); } catch (e) {}
      var author = '';
      try { author = ser(m.author || m.from); } catch (e) {}
      var n = namesOf(author), phone = fromMe ? me : (pmap[author] || phoneOf(author, m));
      var who = fromMe ? 'You' : (n.name || n.push || s(m.notifyName));
      rows.push({ t: Number(m.t) || 0, name: who, phone: phone, type: type, text: msgText(m) });
    });
    return { name: name, rows: rows };
  }

  // ---------------------------------------------------------------- API
  var G = window.__mbGrabber = { cancel: false };
  function run(req, fn) {
    G.cancel = false;
    Promise.resolve().then(fn).catch(function (e) { post({ ev: 'error', req: req, msg: s(e && e.message || e) }); });
  }

  G.list = function (req) {
    run(req, async function () { post({ ev: 'groups', req: req, items: await listGroups() }); });
  };

  G.members = function (req, ids) {
    run(req, async function () {
      for (var i = 0; i < ids.length && !G.cancel; i++) {
        post({ ev: 'progress', req: req, i: i, total: ids.length });
        var r;
        try { r = await membersOf(ids[i]); } catch (e) { r = { name: '', rows: [], error: s(e && e.message || e) }; }
        post({ ev: 'members', req: req, id: ids[i], name: r.name, rows: r.rows, error: r.error || '' });
      }
      post({ ev: 'done', req: req, cancelled: !!G.cancel });
    });
  };

  G.chat = function (req, ids, limit) {
    run(req, async function () {
      for (var i = 0; i < ids.length && !G.cancel; i++) {
        post({ ev: 'progress', req: req, i: i, total: ids.length, n: 0 });
        var r;
        try { r = await chatOf(ids[i], limit, req); } catch (e) { r = { name: '', rows: [], error: s(e && e.message || e) }; }
        post({ ev: 'chat', req: req, id: ids[i], name: r.name, rows: r.rows, error: r.error || '' });
      }
      post({ ev: 'done', req: req, cancelled: !!G.cancel });
    });
  };

  G.diag = function (req) {
    run(req, async function () {
      var st = store(), d = {
        ua: navigator.userAgent, hasRequire: typeof window.require === 'function', collections: !!st,
        modules: { widFactory: !!mod('WAWebWidFactory'), apiContact: !!mod('WAWebApiContact'), loadMsgs: !!mod('WAWebChatLoadMessages') },
        groupMetaColl: !!groupMetaColl(),
        chats: st ? models(st.Chat).length : -1, groupsLive: st ? models(st.Chat).filter(function (c) { return isGroupId(c.id); }).length : -1,
        idbStores: await idbStoreNames(), me: ownUser() ? 'yes' : 'no'
      };
      post({ ev: 'diag', req: req, data: d });
    });
  };
})();
