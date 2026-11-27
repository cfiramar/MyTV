/*
 * Modular ES5 cloud, discovery, smart-home, Supabase profiles/Realtime, and gaming services
 * for LG webOS (3+) and Samsung Tizen (2.3+).
 *
 * Strict ES5 compliance: XMLHttpRequest and WebSocket only; zero ES6 syntax or unsupported built-ins.
 */
var MyTvHttp = (function () {
  'use strict';

  /** Sends an XMLHttpRequest JSON call; done(err, parsedJson, status, rawText). */
  function request(method, url, headers, body, timeoutMs, done) {
    var xhr = new XMLHttpRequest();
    var finished = false;
    xhr.open(method, url, true);
    xhr.timeout = timeoutMs || 10000;
    var h = headers || {}, k;
    for (k in h) {
      if (Object.prototype.hasOwnProperty.call(h, k) && h[k] !== undefined && h[k] !== null && h[k] !== '') {
        try { xhr.setRequestHeader(k, String(h[k])); } catch (e) {}
      }
    }
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4 || finished) return;
      finished = true;
      var status = xhr.status || 0;
      var raw = xhr.responseText || '';
      var parsed = null;
      if (raw) {
        try { parsed = JSON.parse(raw); } catch (e) {}
      }
      if (status >= 200 && status < 300) {
        if (done) done(null, parsed, status, raw);
      } else {
        var err = new Error('HTTP ' + status);
        err.status = status;
        err.body = parsed || raw;
        if (done) done(err, parsed, status, raw);
      }
    };
    xhr.ontimeout = xhr.onerror = function () {
      if (finished) return;
      finished = true;
      var err = new Error('no connection');
      err.status = 0;
      if (done) done(err, null, 0, '');
    };
    if (body !== null && body !== undefined) {
      var payload = typeof body === 'string' ? body : JSON.stringify(body);
      if (!h['Content-Type'] && !h['content-type']) {
        try { xhr.setRequestHeader('Content-Type', 'application/json; charset=UTF-8'); } catch (e2) {}
      }
      xhr.send(payload);
    } else {
      xhr.send();
    }
    return xhr;
  }

  return {request: request};
})();

/* FeatureTabs parity with Android TV FeatureTabs.java (tri-state auto/true/false). */
var FeatureTabs = (function () {
  'use strict';

  function normalizeMode(raw) {
    if (!raw && raw !== 0 && raw !== false) return 'auto';
    var s = String(raw).toLowerCase().replace(/^\s+|\s+$/g, '');
    if (s === 'true' || s === 'on' || s === 'yes' || s === '1' || s === 'shown') return 'true';
    if (s === 'false' || s === 'off' || s === 'no' || s === '0' || s === 'hidden') return 'false';
    return 'auto';
  }

  function resolveFeatureEnabled(runtimeMode, buildMode, hasHardwareOrKeys) {
    var rt = normalizeMode(runtimeMode);
    if (rt === 'true') return true;
    if (rt === 'false') return false;
    var cfg = normalizeMode(buildMode);
    if (cfg === 'true') return true;
    if (cfg === 'false') return false;
    return !!hasHardwareOrKeys;
  }

  function buildScreenList(gamingEnabled, smartHomeEnabled) {
    var list = ['search', 'home', 'apps'];
    if (gamingEnabled) list.push('gaming');
    if (smartHomeEnabled) list.push('smarthome');
    list.push('settings');
    return list;
  }

  function sanitizeScreen(requested, visibleList) {
    if (!visibleList || !visibleList.length) return 'home';
    if (visibleList.indexOf(requested) >= 0) return requested;
    return 'home';
  }

  return {
    normalizeMode: normalizeMode,
    resolveFeatureEnabled: resolveFeatureEnabled,
    buildScreenList: buildScreenList,
    sanitizeScreen: sanitizeScreen
  };
})();

/* Trakt OAuth Device-Code Flow (PKCE + urn:ietf:wg:oauth:2.0:oob refresh) & Sync Client. */
var TraktClient = (function () {
  'use strict';

  var API = 'https://api.trakt.tv';
  var REDIRECT_OOB = 'urn:ietf:wg:oauth:2.0:oob';
  var KEY_AUTH = 'mytv.trakt';
  var KEY_LISTS = 'mytv.trakt.lists';
  var pollTimer = null;
  var authSessionId = 0;
  var activeCode = null;

  function loadJson(key, fallback) {
    try {
      var raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (e) { return fallback; }
  }
  function saveJson(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }
  function removeKey(key) {
    try { localStorage.removeItem(key); } catch (e) {}
  }

  function clientId() {
    if (typeof Prefs !== 'undefined' && Prefs.traktClientId) return Prefs.traktClientId();
    return String((window.CONFIG && window.CONFIG.traktClientId) || '').replace(/^\s+|\s+$/g, '');
  }

  function authState() {
    return loadJson(KEY_AUTH, null);
  }

  function isAvailable() {
    return !!clientId();
  }

  function isSignedIn() {
    var a = authState();
    return !!(isAvailable() && a && (a.refresh || a.access));
  }

  function username() {
    var a = authState();
    return (a && a.user) ? a.user : '';
  }

  function baseHeaders(token) {
    var h = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'trakt-api-version': '2',
      'trakt-api-key': clientId()
    };
    if (token) h.Authorization = 'Bearer ' + token;
    return h;
  }

  function saveTokenBody(body, prevUser) {
    var created = body.created_at || Math.floor(Date.now() / 1000);
    var expiresIn = body.expires_in || 86400;
    var next = {
      access: body.access_token || '',
      refresh: body.refresh_token || '',
      expires: (created + expiresIn) * 1000,
      user: prevUser || ''
    };
    saveJson(KEY_AUTH, next);
    return next;
  }

  function cancelDeviceAuth() {
    authSessionId++;
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    activeCode = null;
  }

  /**
   * Starts Trakt device-code OAuth with PKCE S256 (urn:ietf:wg:oauth:2.0:oob compatible).
   * Calls onCode({userCode, verificationUrl, expiresIn}) once code arrives, and onDone(err, user).
   */
  function startDeviceAuth(onCode, onDone) {
    cancelDeviceAuth();
    var cid = clientId();
    if (!cid) {
      if (onDone) onDone(new Error('Trakt Client ID is not configured'));
      return;
    }
    var mySession = ++authSessionId;
    var verifier = MyTvCrypto.base64Url(MyTvCrypto.randomBytes(48));
    var challenge = MyTvCrypto.base64Url(MyTvCrypto.sha256Bytes(verifier));

    MyTvHttp.request('POST', API + '/oauth/device/code', baseHeaders(null), {
      client_id: cid,
      code_challenge: challenge,
      code_challenge_method: 'S256'
    }, 10000, function (err, start) {
      if (mySession !== authSessionId) return;
      if (err || !start || !start.device_code || !start.user_code) {
        if (onDone) onDone(err || new Error('Could not obtain Trakt device code'));
        return;
      }
      var deviceCode = start.device_code;
      var userCode = start.user_code;
      var verifyUrl = start.verification_url || 'https://trakt.tv/activate';
      var intervalMs = Math.max(5, start.interval || 5) * 1000;
      var deadline = Date.now() + (start.expires_in || 600) * 1000;
      activeCode = {userCode: userCode, verificationUrl: verifyUrl, deadline: deadline};
      if (onCode) onCode(activeCode);

      function poll() {
        if (mySession !== authSessionId) return;
        if (Date.now() >= deadline) {
          activeCode = null;
          if (onDone) onDone(new Error('The code expired.'));
          return;
        }
        MyTvHttp.request('POST', API + '/oauth/device/token', baseHeaders(null), {
          code: deviceCode,
          client_id: cid,
          code_verifier: verifier,
          redirect_uri: REDIRECT_OOB
        }, 10000, function (pollErr, tokenBody, status) {
          if (mySession !== authSessionId) return;
          if (!pollErr && status === 200 && tokenBody && tokenBody.access_token) {
            activeCode = null;
            var saved = saveTokenBody(tokenBody, '');
            MyTvHttp.request('GET', API + '/users/settings', baseHeaders(saved.access), null, 8000, function (uErr, uData) {
              if (mySession !== authSessionId) return;
              var uname = (!uErr && uData && uData.user && uData.user.username) ? uData.user.username : '';
              saved.user = uname;
              saveJson(KEY_AUTH, saved);
              if (onDone) onDone(null, uname);
            });
            return;
          }
          if (status === 400) {
            pollTimer = setTimeout(poll, intervalMs);
            return;
          }
          if (status === 429) {
            intervalMs += 1000;
            pollTimer = setTimeout(poll, intervalMs);
            return;
          }
          activeCode = null;
          if (status === 418) {
            if (onDone) onDone(new Error('Sign-in declined on Trakt.'));
          } else if (status === 410) {
            if (onDone) onDone(new Error('The code expired.'));
          } else {
            if (onDone) onDone(pollErr || new Error('HTTP ' + status));
          }
        });
      }
      pollTimer = setTimeout(poll, intervalMs);
    });
  }

  /** Ensures a fresh access token, refreshing via urn:ietf:wg:oauth:2.0:oob when near expiry. */
  function ensureAccessToken(done) {
    var a = authState();
    if (!a || (!a.access && !a.refresh)) { done(new Error('not signed in')); return; }
    if (a.access && a.expires && Date.now() < a.expires - 3600000) {
      done(null, a.access);
      return;
    }
    if (!a.refresh) {
      done(null, a.access);
      return;
    }
    MyTvHttp.request('POST', API + '/oauth/token', baseHeaders(null), {
      refresh_token: a.refresh,
      client_id: clientId(),
      redirect_uri: REDIRECT_OOB,
      grant_type: 'refresh_token'
    }, 10000, function (err, body, status) {
      if (!err && status === 200 && body && body.access_token) {
        var saved = saveTokenBody(body, a.user);
        done(null, saved.access);
        return;
      }
      if (status === 400 || status === 401) {
        removeKey(KEY_AUTH);
        done(new Error('Trakt session expired'));
        return;
      }
      done(err || new Error('HTTP ' + status));
    });
  }

  function signOut(done) {
    cancelDeviceAuth();
    var a = authState();
    removeKey(KEY_AUTH);
    removeKey(KEY_LISTS);
    if (a && a.access && clientId()) {
      MyTvHttp.request('POST', API + '/oauth/revoke', baseHeaders(null), {
        token: a.access,
        client_id: clientId()
      }, 6000, function () { if (done) done(); });
    } else if (done) {
      done();
    }
  }

  function extractKey(item, type) {
    if (!item) return null;
    var obj = item[type] || item;
    var ids = obj && obj.ids;
    if (!ids) return null;
    var prefix = type === 'show' ? 'tv' : 'movie';
    if (ids.tmdb > 0) return {key: prefix + ':' + ids.tmdb, kind: prefix, tmdb: ids.tmdb, imdb: ids.imdb || '', title: obj.title || ''};
    if (ids.imdb && String(ids.imdb).indexOf('tt') === 0) return {key: prefix + ':' + ids.imdb, kind: prefix, tmdb: 0, imdb: ids.imdb, title: obj.title || ''};
    return null;
  }

  function parseIsoMs(iso) {
    if (!iso) return 0;
    var t = Date.parse(iso);
    return isNaN(t) ? 0 : t;
  }

  /** Syncs Trakt watchlist, watched history, playback progress, and personal recommendations. */
  function syncLists(done) {
    if (!isSignedIn()) { if (done) done(null, getCachedLists()); return; }
    ensureAccessToken(function (err, token) {
      if (err) { if (done) done(err, getCachedLists()); return; }
      var h = baseHeaders(token);
      var out = {
        watchlistShows: [],
        watchlistMovies: [],
        watched: {},
        recommendedShows: [],
        recommendedMovies: [],
        progress: [],
        syncedAt: Date.now()
      };
      var tasks = [
        {path: '/sync/watchlist/shows', apply: function (arr) {
          for (var i = 0; arr && i < arr.length && out.watchlistShows.length < 120; i++) {
            var k = extractKey(arr[i], 'show');
            if (k) out.watchlistShows.push(k);
          }
        }},
        {path: '/sync/watchlist/movies', apply: function (arr) {
          for (var i = 0; arr && i < arr.length && out.watchlistMovies.length < 120; i++) {
            var k = extractKey(arr[i], 'movie');
            if (k) out.watchlistMovies.push(k);
          }
        }},
        {path: '/sync/watched/shows', apply: function (arr) {
          for (var i = 0; arr && i < arr.length; i++) {
            var k = extractKey(arr[i], 'show');
            if (k) out.watched[k.key] = true;
          }
        }},
        {path: '/sync/watched/movies', apply: function (arr) {
          for (var i = 0; arr && i < arr.length; i++) {
            var k = extractKey(arr[i], 'movie');
            if (k) out.watched[k.key] = true;
          }
        }},
        {path: '/sync/playback?limit=20', apply: function (arr) {
          for (var i = 0; arr && i < arr.length; i++) {
            var it = arr[i], isMovie = it.type === 'movie';
            var k = extractKey(it, isMovie ? 'movie' : 'show');
            if (!k) continue;
            var ep = isMovie ? null : it.episode;
            var pct = typeof it.progress === 'number' ? it.progress : 50;
            var s = ep ? (ep.season || 0) : 0;
            var e = ep ? (ep.number || 0) : 0;
            out.progress.push({
              key: k.key, kind: k.kind, tmdb: k.tmdb, imdb: k.imdb, title: k.title,
              season: s, episode: pct >= 90 && !isMovie && e > 0 ? e + 1 : e,
              progressPct: pct >= 90 && !isMovie ? 0 : Math.round(pct),
              engaged: parseIsoMs(it.paused_at) || Date.now(),
              source: 'trakt'
            });
          }
        }},
        {path: '/recommendations/shows?ignore_collected=true&limit=30', apply: function (arr) {
          for (var i = 0; arr && i < arr.length; i++) {
            var k = extractKey(arr[i], 'show');
            if (k) out.recommendedShows.push(k);
          }
        }},
        {path: '/recommendations/movies?ignore_collected=true&limit=30', apply: function (arr) {
          for (var i = 0; arr && i < arr.length; i++) {
            var k = extractKey(arr[i], 'movie');
            if (k) out.recommendedMovies.push(k);
          }
        }}
      ];
      var idx = 0;
      function next() {
        if (idx >= tasks.length) {
          out.progress.sort(function (a, b) { return b.engaged - a.engaged; });
          saveJson(KEY_LISTS, out);
          if (done) done(null, out);
          return;
        }
        var t = tasks[idx++];
        MyTvHttp.request('GET', API + t.path, h, null, 10000, function (reqErr, data) {
          if (!reqErr && Array.isArray(data)) t.apply(data);
          next();
        });
      }
      next();
    });
  }

  function getCachedLists() {
    return loadJson(KEY_LISTS, {
      watchlistShows: [], watchlistMovies: [], watched: {},
      recommendedShows: [], recommendedMovies: [], progress: [], syncedAt: 0
    });
  }

  return {
    REDIRECT_OOB: REDIRECT_OOB,
    isAvailable: isAvailable,
    isSignedIn: isSignedIn,
    username: username,
    startDeviceAuth: startDeviceAuth,
    cancelDeviceAuth: cancelDeviceAuth,
    ensureAccessToken: ensureAccessToken,
    signOut: signOut,
    syncLists: syncLists,
    getCachedLists: getCachedLists
  };
})();

/* Stremio Account Cloud Datastore Library & Playback Progress Sync (api.strem.io). */
var StremioClient = (function () {
  'use strict';

  var API_BASE = 'https://api.strem.io/api';
  var KEY_AUTH = 'mytv.stremio';
  var KEY_LIB = 'mytv.stremio.library';

  function loadJson(key, fallback) {
    try {
      var raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (e) { return fallback; }
  }
  function saveJson(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }

  function authInfo() {
    var saved = loadJson(KEY_AUTH, null);
    if (saved && saved.authKey) return saved;
    var cfgKey = String((window.CONFIG && window.CONFIG.stremioAuthKey) || '').replace(/^\s+|\s+$/g, '');
    var cfgEmail = String((window.CONFIG && window.CONFIG.stremioEmail) || '').replace(/^\s+|\s+$/g, '');
    if (cfgKey) return {authKey: cfgKey, email: cfgEmail};
    return {authKey: '', email: cfgEmail};
  }

  function isConfigured() { return !!authInfo().authKey; }
  function email() { return authInfo().email || ''; }

  function setAuthKey(authKey, userEmail) {
    var clean = String(authKey || '').replace(/^\s+|\s+$/g, '');
    if (!clean) {
      try { localStorage.removeItem(KEY_AUTH); localStorage.removeItem(KEY_LIB); } catch (e) {}
      return;
    }
    saveJson(KEY_AUTH, {authKey: clean, email: String(userEmail || '').replace(/^\s+|\s+$/g, '')});
  }

  function signOut() { setAuthKey('', ''); }

  /** Logs in via email/password and stores ONLY the resulting authKey (password is discarded immediately). */
  function login(userEmail, password, done) {
    var em = String(userEmail || '').replace(/^\s+|\s+$/g, '');
    if (!em || !password) {
      if (done) done(new Error('Email and password are required'));
      return;
    }
    MyTvHttp.request('POST', API_BASE + '/login', {Accept: 'application/json'}, {
      type: 'Login',
      email: em,
      password: password,
      facebook: false
    }, 10000, function (err, data) {
      if (err) { if (done) done(err); return; }
      var res = data && data.result;
      if (!res || !res.authKey) {
        var msg = (data && data.error && data.error.message) || 'Stremio login failed';
        if (done) done(new Error(msg));
        return;
      }
      var resolvedEmail = (res.user && res.user.email) ? res.user.email : em;
      setAuthKey(res.authKey, resolvedEmail);
      if (done) done(null, {authKey: res.authKey, email: resolvedEmail});
    });
  }

  /** Strips third-party RPDB/Top-Posters rating-overlay poster URLs in favor of clean official CDNs. */
  function sanitizePosterUrl(rawUrl, imdbId) {
    var u = String(rawUrl || '').replace(/^\s+|\s+$/g, '');
    var cleanId = String(imdbId || '').replace(/^\s+|\s+$/g, '');
    var lower = u.toLowerCase();
    var allowed = lower.indexOf('image.tmdb.org') >= 0 || lower.indexOf('images.metahub.space') >= 0 ||
        lower.indexOf('episodes.metahub.space') >= 0 || lower.indexOf('m.media-amazon.com') >= 0 ||
        lower.indexOf('thetvdb.com') >= 0 || lower.indexOf('static.tvmaze.com') >= 0 ||
        lower.indexOf('assets.fanart.tv') >= 0;
    var bad = lower.indexOf('ratingposterdb') >= 0 || lower.indexOf('rpdb') >= 0 ||
        lower.indexOf('top-streaming') >= 0 || lower.indexOf('top-posters') >= 0;
    if (u && allowed && !bad) return u;
    if (cleanId.indexOf('tt') === 0) return 'https://images.metahub.space/poster/medium/' + cleanId + '/img';
    return '';
  }

  /** Parses Stremio video_id strings like "tt0903747:2:5" into {season, episode}. */
  function parseVideoIdSeasonEpisode(videoId) {
    if (!videoId) return {season: 0, episode: 0};
    var parts = String(videoId).split(':');
    if (parts.length >= 3) {
      var s = parseInt(parts[parts.length - 2], 10);
      var e = parseInt(parts[parts.length - 1], 10);
      if (s > 0 && e > 0) return {season: s, episode: e};
    }
    return {season: 0, episode: 0};
  }

  function parseIsoMs(iso) {
    if (!iso) return 0;
    var t = Date.parse(iso);
    return isNaN(t) ? 0 : t;
  }

  /** Pure parser for Stremio datastoreGet (collection: "libraryItem") responses. */
  function parseLibraryResponse(raw) {
    var arr = Array.isArray(raw) ? raw : (raw && Array.isArray(raw.result) ? raw.result : []);
    var out = [], i, item, id, type, kind, state, se, lastWatched, timeOffset, duration, timesWatched, watched;
    for (i = 0; i < arr.length; i++) {
      item = arr[i];
      if (!item) continue;
      id = String(item._id || '').replace(/^\s+|\s+$/g, '');
      if (id.indexOf('tt') !== 0) continue;
      if (item.removed) continue;
      type = item.type || 'series';
      if (type !== 'movie' && type !== 'series' && type !== 'tv') continue;
      kind = type === 'movie' ? 'movie' : 'tv';
      state = item.state || {};
      lastWatched = parseIsoMs(state.lastWatched);
      timesWatched = state.timesWatched || 0;
      timeOffset = state.timeOffset || 0;
      duration = state.duration || 0;
      se = parseVideoIdSeasonEpisode(state.video_id || '');
      watched = kind === 'movie'
        ? (timesWatched > 0 && (timeOffset === 0 || (duration > 0 && timeOffset >= duration * 0.88)))
        : false;
      var isWatchlist = !item.removed && !item.temp;
      var hasContinue = kind === 'movie'
        ? (!watched && !(duration > 0 && timeOffset >= duration * 0.9) && timeOffset > 60000)
        : (timeOffset > 60000 || (lastWatched > 0 && (timesWatched > 0 || se.season > 1 || se.episode > 1)));
      var pct = duration > 0 && timeOffset > 0 ? Math.min(100, Math.round((timeOffset / duration) * 100)) : 0;
      out.push({
        imdbId: id,
        title: String(item.name || '').replace(/^\s+|\s+$/g, '') || id,
        kind: kind,
        posterUrl: sanitizePosterUrl(item.poster, id),
        backdropUrl: 'https://images.metahub.space/background/large/' + id + '/img',
        ctimeMs: parseIsoMs(item._ctime),
        mtimeMs: parseIsoMs(item._mtime),
        lastWatchedMs: lastWatched,
        engaged: Math.max(lastWatched, parseIsoMs(item._mtime), parseIsoMs(item._ctime)),
        season: se.season,
        episode: se.episode,
        progressPct: pct,
        watched: watched,
        isWatchlist: isWatchlist,
        hasContinue: hasContinue
      });
    }
    out.sort(function (a, b) { return b.engaged - a.engaged; });
    return out;
  }

  /** Fetches the user's Stremio Library from api.strem.io/api/datastoreGet. */
  function syncLibrary(done) {
    var auth = authInfo();
    if (!auth.authKey) {
      if (done) done(null, getCachedLibrary());
      return;
    }
    MyTvHttp.request('POST', API_BASE + '/datastoreGet', {Accept: 'application/json'}, {
      authKey: auth.authKey,
      collection: 'libraryItem',
      all: true
    }, 10000, function (err, data) {
      if (err) { if (done) done(err, getCachedLibrary()); return; }
      var parsed = parseLibraryResponse(data);
      saveJson(KEY_LIB, {items: parsed, syncedAt: Date.now()});
      if (done) done(null, parsed);
    });
  }

  function getCachedLibrary() {
    var c = loadJson(KEY_LIB, null);
    return (c && Array.isArray(c.items)) ? c.items : [];
  }

  return {
    isConfigured: isConfigured,
    email: email,
    setAuthKey: setAuthKey,
    signOut: signOut,
    login: login,
    sanitizePosterUrl: sanitizePosterUrl,
    parseVideoIdSeasonEpisode: parseVideoIdSeasonEpisode,
    parseLibraryResponse: parseLibraryResponse,
    syncLibrary: syncLibrary,
    getCachedLibrary: getCachedLibrary
  };
})();

/* Critic Ratings Client: MDBList + OMDb + Stremio Cinemeta fallback with persistent LRU cache. */
var RatingsClient = (function () {
  'use strict';

  var KEY_CACHE = 'mytv.ratings';
  var MAX_CACHE = 300;
  var omdbCooldownUntil = 0;
  var cache = null;

  function loadCache() {
    if (cache) return cache;
    try { cache = JSON.parse(localStorage.getItem(KEY_CACHE)) || {}; } catch (e) { cache = {}; }
    return cache;
  }

  function saveCache() {
    var c = loadCache();
    var keys = Object.keys(c);
    if (keys.length > MAX_CACHE) {
      keys.sort(function (a, b) { return (c[a].at || 0) - (c[b].at || 0); });
      for (var i = 0; i < keys.length - MAX_CACHE; i++) delete c[keys[i]];
    }
    try { localStorage.setItem(KEY_CACHE, JSON.stringify(c)); } catch (e) {}
  }

  function omdbKey() {
    if (typeof Prefs !== 'undefined' && Prefs.omdbKey) return Prefs.omdbKey();
    return String((window.CONFIG && window.CONFIG.omdbKey) || '').replace(/^\s+|\s+$/g, '');
  }

  function mdblistKey() {
    if (typeof Prefs !== 'undefined' && Prefs.mdblistKey) return Prefs.mdblistKey();
    return String((window.CONFIG && window.CONFIG.mdblistKey) || '').replace(/^\s+|\s+$/g, '');
  }

  /** Formats Metacritic 0..100 score into Android TV's 0..10 one-decimal display (e.g. 71 -> "7.1"). */
  function formatMetacritic(raw) {
    var n = parseFloat(String(raw || '').replace('/100', ''));
    if (isNaN(n) || n <= 0) return '';
    return n > 10 ? (n / 10).toFixed(1) : n.toFixed(1);
  }

  function parseMdblist(data) {
    var res = {imdb: '', rt: '', mc: ''};
    var arr = data && data.ratings;
    if (!Array.isArray(arr)) return res;
    for (var i = 0; i < arr.length; i++) {
      var r = arr[i];
      if (!r || !r.source) continue;
      var src = String(r.source).toLowerCase();
      var val = r.value;
      if (src === 'imdb' && typeof val === 'number' && val > 0) res.imdb = val.toFixed(1);
      else if ((src === 'tomatoes' || src.indexOf('rotten') >= 0) && typeof val === 'number' && val > 0) res.rt = Math.round(val) + '%';
      else if (src === 'metacritic' && typeof val === 'number' && val > 0) res.mc = String(Math.round(val));
    }
    return res;
  }

  function parseOmdb(data) {
    var res = {imdb: '', rt: '', mc: ''};
    if (!data || String(data.Response || '').toLowerCase() !== 'true') return res;
    if (data.imdbRating && data.imdbRating !== 'N/A') res.imdb = String(data.imdbRating);
    if (data.Metascore && data.Metascore !== 'N/A') res.mc = String(data.Metascore);
    var arr = data.Ratings;
    for (var i = 0; arr && i < arr.length; i++) {
      var r = arr[i], src = String(r.Source || '').toLowerCase(), val = String(r.Value || '');
      if (!val || val === 'N/A') continue;
      if (src === 'rotten tomatoes') res.rt = val;
      else if (src === 'metacritic' && !res.mc) res.mc = val.replace('/100', '').replace(/^\s+|\s+$/g, '');
      else if (src === 'internet movie database' && !res.imdb) res.imdb = val.replace('/10', '').replace(/^\s+|\s+$/g, '');
    }
    return res;
  }

  function getCached(imdbId) {
    if (!imdbId) return null;
    var c = loadCache();
    return c[imdbId] || null;
  }

  /** Fetches IMDb, Metacritic, and Rotten Tomatoes ratings for `imdbId` ("tt...") using MDBList -> OMDb -> Cinemeta. */
  function fetchRatings(imdbId, kind, done) {
    if (!imdbId || String(imdbId).indexOf('tt') !== 0) {
      if (done) done({imdb: '', rt: '', mc: ''});
      return;
    }
    var mk = mdblistKey(), ok = omdbKey();
    var hasRich = !!(mk || ok);
    var existing = getCached(imdbId);
    if (existing && (existing.rt || existing.mc || !hasRich)) {
      if (done) done(existing);
      return;
    }
    var out = {
      imdb: existing ? existing.imdb || '' : '',
      rt: existing ? existing.rt || '' : '',
      mc: existing ? existing.mc || '' : '',
      at: Date.now()
    };

    function finish() {
      if (out.imdb || out.rt || out.mc) {
        loadCache()[imdbId] = out;
        saveCache();
      }
      if (done) done(out);
    }

    function stepCinemeta() {
      if (out.imdb) { finish(); return; }
      var cType = kind === 'movie' ? 'movie' : 'series';
      MyTvHttp.request('GET', 'https://v3-cinemeta.strem.io/meta/' + cType + '/' + encodeURIComponent(imdbId) + '.json',
        {Accept: 'application/json'}, null, 5000, function (err, data) {
          if (!err && data && data.meta && data.meta.imdbRating && data.meta.imdbRating !== 'N/A') {
            out.imdb = String(data.meta.imdbRating);
          }
          finish();
        });
    }

    function stepOmdb() {
      if (!ok || Date.now() < omdbCooldownUntil || (out.imdb && out.rt && out.mc)) {
        stepCinemeta();
        return;
      }
      MyTvHttp.request('GET', 'https://www.omdbapi.com/?i=' + encodeURIComponent(imdbId) + '&apikey=' + encodeURIComponent(ok),
        {Accept: 'application/json'}, null, 3500, function (err, data, status) {
          if (status === 401 || status === 429) {
            omdbCooldownUntil = Date.now() + 15 * 60000;
          } else if (!err && data) {
            var p = parseOmdb(data);
            if (p.imdb && !out.imdb) out.imdb = p.imdb;
            if (p.rt && !out.rt) out.rt = p.rt;
            if (p.mc && !out.mc) out.mc = p.mc;
          }
          stepCinemeta();
        });
    }

    if (mk) {
      MyTvHttp.request('GET', 'https://mdblist.com/api/?i=' + encodeURIComponent(imdbId) + '&apikey=' + encodeURIComponent(mk),
        {Accept: 'application/json'}, null, 3500, function (err, data) {
          if (!err && data) {
            var p = parseMdblist(data);
            if (p.imdb) out.imdb = p.imdb;
            if (p.rt) out.rt = p.rt;
            if (p.mc) out.mc = p.mc;
          }
          stepOmdb();
        });
    } else {
      stepOmdb();
    }
  }

  return {
    formatMetacritic: formatMetacritic,
    parseMdblist: parseMdblist,
    parseOmdb: parseOmdb,
    getCached: getCached,
    fetchRatings: fetchRatings
  };
})();

/* Google Gemini AI Search + TMDB/Cinemeta Unified Catalog Search (strict ES5 XHR). */
var GeminiSearchClient = (function () {
  'use strict';

  var ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=';
  var SYSTEM_PROMPT = 'You are MyTV\'s built-in AI cinema assistant on a smart TV. ' +
    'Given the user\'s query (natural-language description, mood, actor, plot memory, or title), ' +
    'respond ONLY with a valid JSON object (no markdown fences) in this exact schema: ' +
    '{"summary":"short 1-sentence friendly recommendation","primaryTitle":"exact movie or TV show title",' +
    '"mediaType":"movie or series","candidates":[{"title":"Exact Title","type":"movie or series","year":"YYYY","reason":"short reason"}]}';

  function apiKey() {
    if (typeof Prefs !== 'undefined' && Prefs.geminiKey) return Prefs.geminiKey();
    return String((window.CONFIG && window.CONFIG.geminiKey) || '').replace(/^\s+|\s+$/g, '');
  }

  function isConfigured() { return !!apiKey(); }

  /** Parses Gemini generateContent JSON output cleanly, stripping accidental code fences. */
  function parseGeminiResponse(root, fallbackTitle) {
    var cands = root && root.candidates;
    if (!Array.isArray(cands) || !cands.length) return null;
    var parts = cands[0] && cands[0].content && cands[0].content.parts;
    if (!Array.isArray(parts) || !parts.length) return null;
    var text = String(parts[0].text || '').replace(/^\s+|\s+$/g, '');
    if (text.indexOf('```') === 0) {
      text = text.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '').replace(/^\s+|\s+$/g, '');
    }
    var parsed = null;
    try { parsed = JSON.parse(text); } catch (e) { return null; }
    var mediaType = String(parsed.mediaType || 'movie').toLowerCase();
    var kind = (mediaType.indexOf('series') >= 0 || mediaType.indexOf('tv') >= 0) ? 'tv' : 'movie';
    var list = [], rawList = Array.isArray(parsed.candidates) ? parsed.candidates : [];
    for (var i = 0; i < rawList.length; i++) {
      var o = rawList[i];
      if (!o || !o.title) continue;
      var t = String(o.type || kind).toLowerCase();
      list.push({
        title: String(o.title),
        kind: (t.indexOf('series') >= 0 || t.indexOf('tv') >= 0) ? 'tv' : 'movie',
        year: String(o.year || ''),
        reason: String(o.reason || '')
      });
    }
    return {
      summary: String(parsed.summary || ''),
      primaryTitle: String(parsed.primaryTitle || fallbackTitle || ''),
      kind: kind,
      candidates: list
    };
  }

  /** Queries Gemini 2.5 Flash for conversational media recommendations; done(err, aiResult). */
  function ask(userQuery, done) {
    var q = String(userQuery || '').replace(/^\s+|\s+$/g, '');
    var key = apiKey();
    if (!q) { if (done) done(new Error('Empty query')); return; }
    if (!key) { if (done) done(new Error('Gemini API key not configured')); return; }
    var body = {
      contents: [{role: 'user', parts: [{text: SYSTEM_PROMPT + '\n\nUser query: ' + q}]}],
      generationConfig: {temperature: 0.3, maxOutputTokens: 450, responseMimeType: 'application/json'}
    };
    MyTvHttp.request('POST', ENDPOINT + encodeURIComponent(key), {Accept: 'application/json'}, body, 12000, function (err, data) {
      if (err) { if (done) done(err); return; }
      var parsed = parseGeminiResponse(data, q);
      if (!parsed) { if (done) done(new Error('Could not parse Gemini response')); return; }
      if (done) done(null, parsed);
    });
  }

  /** Keyless Cinemeta catalog search (movie + series) used as universal fallback when TMDB key is absent. */
  function searchCinemeta(query, done) {
    var q = encodeURIComponent(String(query || '').replace(/^\s+|\s+$/g, ''));
    if (!q) { done([]); return; }
    var out = [], left = 2;
    function one(type) {
      MyTvHttp.request('GET', 'https://v3-cinemeta.strem.io/catalog/' + type + '/top/search=' + q + '.json',
        {Accept: 'application/json'}, null, 6000, function (err, data) {
          var metas = (!err && data && Array.isArray(data.metas)) ? data.metas : [];
          for (var i = 0; i < metas.length && i < 10; i++) {
            var m = metas[i];
            if (!m || !m.id || String(m.id).indexOf('tt') !== 0) continue;
            out.push({
              id: m.id,
              imdbId: m.id,
              kind: type === 'movie' ? 'movie' : 'tv',
              title: m.name || m.id,
              posterUrl: StremioClient.sanitizePosterUrl(m.poster, m.id),
              backdropUrl: 'https://images.metahub.space/background/large/' + m.id + '/img',
              year: String(m.releaseInfo || '').slice(0, 4),
              imdbScore: m.imdbRating && m.imdbRating !== 'N/A' ? String(m.imdbRating) : '',
              overview: m.description || '',
              genres: Array.isArray(m.genres) ? m.genres : []
            });
          }
          if (--left === 0) done(out);
        });
    }
    one('movie');
    one('series');
  }

  return {
    isConfigured: isConfigured,
    parseGeminiResponse: parseGeminiResponse,
    ask: ask,
    searchCinemeta: searchCinemeta
  };
})();

/* Smart-Home Controller: Sensibo HTTPS, Tuya OpenAPI v2.0 (HMAC-SHA256), and LG webOS WiZ UDP 38899. */
var SmartHomeClient = (function () {
  'use strict';

  var KEY_STATE = 'mytv.smarthome';
  var KEY_BULBS = 'mytv.smarthome.bulbs';
  var EMPTY_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
  var TUYA_HOSTS = {
    eu: 'https://openapi.tuyaeu.com',
    we: 'https://openapi-weaz.tuyaeu.com',
    us: 'https://openapi.tuyaus.com',
    'in': 'https://openapi.tuyain.com'
  };

  function loadJson(key, fallback) {
    try { var r = localStorage.getItem(key); return r ? JSON.parse(r) : fallback; } catch (e) { return fallback; }
  }
  function saveJson(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }

  var state = loadJson(KEY_STATE, {
    sensibo: {on: false, mode: 'cool', temp: 23, fan: 'auto', podId: '', room: 'Living Room'},
    tuya: {on: false, mode: 'cool', temp: 23, fan: 'auto', devId: '', room: 'Bedroom'},
    wiz: {on: false, dimming: 100, temp: 2700}
  });
  var bulbs = loadJson(KEY_BULBS, []);

  function saveState() { saveJson(KEY_STATE, state); }
  function saveBulbs() { saveJson(KEY_BULBS, bulbs); }

  function pref(name, cfgName) {
    if (typeof Prefs !== 'undefined' && typeof Prefs[name] === 'function') return Prefs[name]();
    return String((window.CONFIG && window.CONFIG[cfgName]) || '').replace(/^\s+|\s+$/g, '');
  }

  function hasSensibo() { return !!(pref('sensiboKey', 'sensiboApiKey') || pref('sensiboPodId', 'sensiboDeviceId')); }
  function hasTuya() { return !!(pref('tuyaAccessId', 'tuyaAccessId') && pref('tuyaAccessSecret', 'tuyaAccessSecret')); }
  function hasWiz() {
    var seeds = pref('wizSeedIps', 'wizLightIps');
    return !!(seeds || bulbs.length > 0);
  }
  function hasAnyConfigured() { return hasSensibo() || hasTuya() || hasWiz(); }

  function cap(s) {
    var str = String(s || '');
    return str ? str.charAt(0).toUpperCase() + str.slice(1).toLowerCase() : '';
  }

  function kelvinLabel(k) {
    if (k <= 2800) return 'Warm';
    if (k <= 3400) return 'Soft White';
    if (k <= 4500) return 'Neutral';
    if (k <= 5600) return 'Daylight';
    return 'Cool';
  }

  /** Returns the active configured Smart-Home device cards for rendering on the Smart-Home screen. */
  function getDevices() {
    var list = [];
    if (hasSensibo()) {
      var s = state.sensibo;
      list.push({
        id: 'sensibo_ac',
        protocol: 'SENSIBO_AC',
        name: (s.room || 'Living Room') + ' AC',
        icon: 'ac',
        on: !!s.on,
        mode: s.mode || 'cool',
        temp: s.temp || 23,
        fan: s.fan || 'auto',
        status: s.on ? (s.temp + '°C · ' + cap(s.mode) + ' · Fan ' + cap(s.fan)) : ('Off · ' + s.temp + '°C')
      });
    }
    if (hasTuya()) {
      var t = state.tuya;
      list.push({
        id: 'tuya_ac',
        protocol: 'TUYA_AC',
        name: (t.room || 'Bedroom') + ' AC',
        icon: 'ac',
        on: !!t.on,
        mode: t.mode || 'cool',
        temp: t.temp || 23,
        fan: t.fan || 'auto',
        status: t.on ? (t.temp + '°C · ' + cap(t.mode) + ' · Fan ' + cap(t.fan)) : ('Off · ' + t.temp + '°C')
      });
    }
    if (hasWiz() || (typeof TV !== 'undefined' && TV.supportsLocalWiz && hasAnyConfigured())) {
      var w = state.wiz;
      var countTxt = bulbs.length ? (' · ' + bulbs.length + (bulbs.length === 1 ? ' bulb' : ' bulbs')) : '';
      list.push({
        id: 'wiz_lights',
        protocol: 'WIZ_LIGHT',
        name: 'Lights',
        icon: 'lightbulb',
        on: !!w.on,
        dimming: w.dimming || 100,
        temp: w.temp || 2700,
        status: w.on ? ('On · ' + w.dimming + '% · ' + kelvinLabel(w.temp)) : ('Off' + countTxt)
      });
    }
    return list;
  }

  // MARK: - Sensibo Cloud API v2 (HTTPS)

  function syncSensibo(done) {
    var key = pref('sensiboKey', 'sensiboApiKey');
    if (!key) { if (done) done(false, 'No Sensibo API key'); return; }
    MyTvHttp.request('GET', 'https://home.sensibo.com/api/v2/users/me/pods?fields=id,room,acState&apiKey=' + encodeURIComponent(key),
      {Accept: 'application/json'}, null, 6000, function (err, data) {
        if (err || !data || !Array.isArray(data.result) || !data.result.length) {
          if (done) done(false, 'Could not reach Sensibo');
          return;
        }
        var pod = data.result[0];
        if (pod.id) state.sensibo.podId = pod.id;
        if (pod.room && pod.room.name) state.sensibo.room = pod.room.name;
        if (pod.acState) {
          state.sensibo.on = !!pod.acState.on;
          if (pod.acState.mode) state.sensibo.mode = pod.acState.mode;
          if (pod.acState.targetTemperature) state.sensibo.temp = pod.acState.targetTemperature;
          if (pod.acState.fanLevel) state.sensibo.fan = pod.acState.fanLevel;
        }
        saveState();
        if (done) done(true, 'Sensibo synced');
      });
  }

  function setSensibo(on, mode, tempC, fanLevel, done) {
    var key = pref('sensiboKey', 'sensiboApiKey');
    state.sensibo.on = !!on;
    if (mode) state.sensibo.mode = mode;
    if (tempC >= 16 && tempC <= 30) state.sensibo.temp = tempC;
    if (fanLevel) state.sensibo.fan = fanLevel;
    saveState();
    if (!key) { if (done) done(false, 'Configure Sensibo API key in Settings'); return; }

    function send(podId) {
      var body = {
        acState: {
          on: state.sensibo.on,
          mode: state.sensibo.mode,
          targetTemperature: state.sensibo.temp,
          temperatureUnit: 'C',
          fanLevel: state.sensibo.fan
        }
      };
      MyTvHttp.request('POST', 'https://home.sensibo.com/api/v2/pods/' + encodeURIComponent(podId) + '/acStates?apiKey=' + encodeURIComponent(key),
        {Accept: 'application/json'}, body, 6000, function (err) {
          if (done) done(!err, err ? 'Sensibo command failed' : (state.sensibo.room + ' AC updated'));
        });
    }

    var podId = pref('sensiboPodId', 'sensiboDeviceId') || state.sensibo.podId;
    if (podId) { send(podId); return; }
    syncSensibo(function (ok) {
      if (ok && state.sensibo.podId) send(state.sensibo.podId);
      else if (done) done(false, 'No Sensibo pod found');
    });
  }

  // MARK: - Tuya OpenAPI v2.0 (HMAC-SHA256 in Pure ES5)

  /** Builds Tuya OpenAPI v2.0 HMAC-SHA256 authentication headers (pure, testable). */
  function buildTuyaHeaders(method, path, bodyStr, clientId, secret, token, timestampMs) {
    var t = String(timestampMs || Date.now());
    var bodyHash = bodyStr ? MyTvCrypto.sha256Hex(bodyStr) : EMPTY_SHA256;
    var stringToSign = method.toUpperCase() + '\n' + bodyHash + '\n\n' + path;
    var signPayload = clientId + (token || '') + t + stringToSign;
    var sign = MyTvCrypto.hmacSha256Hex(signPayload, secret, true);
    var h = {
      'client_id': clientId,
      'sign': sign,
      't': t,
      'sign_method': 'HMAC-SHA256',
      'Accept': 'application/json'
    };
    if (token) h.access_token = token;
    if (bodyStr) h['Content-Type'] = 'application/json';
    return h;
  }

  /** Builds Tuya infrared_ac command list (PowerOn/PowerOff, M, T, F). */
  function buildTuyaIrCommands(on, mode, tempC, fanLevel) {
    if (!on) return [{code: 'PowerOff', value: 'PowerOff'}];
    var mCode = 0, ml = String(mode || 'cool').toLowerCase();
    if (ml.indexOf('heat') >= 0) mCode = 1;
    else if (ml.indexOf('auto') >= 0) mCode = 2;
    else if (ml.indexOf('fan') >= 0) mCode = 3;
    else if (ml.indexOf('dry') >= 0) mCode = 4;
    var fCode = 0, fl = String(fanLevel || 'auto').toLowerCase();
    if (fl.indexOf('low') >= 0) fCode = 1;
    else if (fl.indexOf('med') >= 0) fCode = 2;
    else if (fl.indexOf('high') >= 0) fCode = 3;
    var t = Math.max(16, Math.min(30, parseInt(tempC, 10) || 23));
    return [
      {code: 'PowerOn', value: 'PowerOn'},
      {code: 'M', value: mCode},
      {code: 'T', value: t},
      {code: 'F', value: fCode}
    ];
  }

  function tuyaRegionHosts() {
    var reg = pref('tuyaRegion', 'tuyaRegion') || 'eu';
    var primary = TUYA_HOSTS[reg] || TUYA_HOSTS.eu;
    var list = [primary], keys = ['eu', 'we', 'us', 'in'];
    for (var i = 0; i < keys.length; i++) {
      if (list.indexOf(TUYA_HOSTS[keys[i]]) < 0) list.push(TUYA_HOSTS[keys[i]]);
    }
    return list;
  }

  function setTuya(on, mode, tempC, fanLevel, done) {
    var accessId = pref('tuyaAccessId', 'tuyaAccessId');
    var accessSecret = pref('tuyaAccessSecret', 'tuyaAccessSecret');
    state.tuya.on = !!on;
    if (mode) state.tuya.mode = mode;
    if (tempC >= 16 && tempC <= 30) state.tuya.temp = tempC;
    if (fanLevel) state.tuya.fan = fanLevel;
    saveState();
    if (!accessId || !accessSecret) { if (done) done(false, 'Configure Tuya Access ID & Secret in Settings'); return; }

    var hosts = tuyaRegionHosts(), hostIdx = 0;

    function postCmds(base, token, devId, commands, cb) {
      var path = '/v1.0/iot-03/devices/' + encodeURIComponent(devId) + '/commands';
      var bodyStr = JSON.stringify({commands: commands});
      var h = buildTuyaHeaders('POST', path, bodyStr, accessId, accessSecret, token);
      MyTvHttp.request('POST', base + path, h, bodyStr, 5000, function (err, data) {
        cb(!err && data && data.success === true);
      });
    }

    function sendOnHost(base, token, devId, cb) {
      var ir = buildTuyaIrCommands(state.tuya.on, state.tuya.mode, state.tuya.temp, state.tuya.fan);
      postCmds(base, token, devId, ir, function (ok) {
        if (ok) { cb(true); return; }
        var std = [{code: 'switch', value: state.tuya.on}];
        if (state.tuya.on) {
          std.push({code: 'mode', value: state.tuya.mode === 'cool' ? 'cold' : state.tuya.mode});
          std.push({code: 'temp_set', value: state.tuya.temp});
        }
        postCmds(base, token, devId, std, cb);
      });
    }

    function discoverDev(base, token, cb) {
      var path = '/v2.0/cloud/thing/device?page_size=20';
      var h = buildTuyaHeaders('GET', path, '', accessId, accessSecret, token);
      MyTvHttp.request('GET', base + path, h, null, 5000, function (err, data) {
        var list = (!err && data && Array.isArray(data.result)) ? data.result : [];
        var fallback = '', i, d, cat;
        for (i = 0; i < list.length; i++) {
          d = list[i];
          if (!d || !d.id) continue;
          cat = String(d.category || '');
          if (d.sub || cat === 'qt' || cat === 'kt' || cat === 'infrared_ac') { cb(d.id, d.customName || d.name || ''); return; }
          if (cat !== 'wnykq' && !fallback) fallback = d.id;
        }
        cb(fallback || (list[0] && list[0].id) || '', '');
      });
    }

    function tryHost() {
      if (hostIdx >= hosts.length) {
        if (done) done(false, 'Tuya API unreachable or credentials invalid');
        return;
      }
      var base = hosts[hostIdx++];
      var tokenPath = '/v1.0/token?grant_type=1';
      var h = buildTuyaHeaders('GET', tokenPath, '', accessId, accessSecret, '');
      MyTvHttp.request('GET', base + tokenPath, h, null, 5000, function (err, data) {
        var token = (!err && data && data.success && data.result) ? data.result.access_token : '';
        if (!token) { tryHost(); return; }
        var devId = pref('tuyaDeviceId', 'tuyaDeviceId') || state.tuya.devId;
        if (devId) {
          sendOnHost(base, token, devId, function (ok) {
            if (ok) { if (done) done(true, state.tuya.room + ' AC updated'); }
            else tryHost();
          });
        } else {
          discoverDev(base, token, function (foundId, foundName) {
            if (!foundId) { tryHost(); return; }
            state.tuya.devId = foundId;
            if (foundName) state.tuya.room = foundName.replace(/\s*AC$/i, '') || state.tuya.room;
            saveState();
            sendOnHost(base, token, foundId, function (ok) {
              if (ok) { if (done) done(true, state.tuya.room + ' AC updated'); }
              else tryHost();
            });
          });
        }
      });
    }
    tryHost();
  }

  // MARK: - WiZ V2 LAN UDP 38899 (via LG webOS JS service)

  function seedIpList() {
    var raw = pref('wizSeedIps', 'wizLightIps');
    var parts = raw ? raw.split(',') : [], out = [], i, ip;
    for (i = 0; i < parts.length; i++) {
      ip = parts[i].replace(/^\s+|\s+$/g, '');
      if (ip && out.indexOf(ip) < 0) out.push(ip);
    }
    for (i = 0; i < bulbs.length; i++) {
      if (bulbs[i].ip && out.indexOf(bulbs[i].ip) < 0) out.push(bulbs[i].ip);
    }
    return out;
  }

  function discoverWizBulbs(done) {
    if (typeof TV === 'undefined' || !TV.wizDiscover) {
      if (done) done(false, 'Local WiZ UDP discovery requires LG webOS service');
      return;
    }
    TV.wizDiscover({seedIps: seedIpList()}, function (res) {
      if (!res || !res.supported) {
        if (done) done(false, 'Local WiZ UDP requires LG webOS');
        return;
      }
      var found = res.bulbs || [];
      if (found.length) {
        var anyOn = false;
        bulbs = found;
        for (var i = 0; i < found.length; i++) {
          if (found[i].on) anyOn = true;
        }
        state.wiz.on = anyOn;
        if (found[0].dimming) state.wiz.dimming = found[0].dimming;
        if (found[0].temp) state.wiz.temp = found[0].temp;
        saveBulbs();
        saveState();
        if (done) done(true, 'Found ' + found.length + ' WiZ bulb(s)');
      } else {
        if (done) done(false, 'No WiZ bulbs responded on local Wi-Fi');
      }
    });
  }

  function setWizLights(on, dimming, tempK, done) {
    state.wiz.on = !!on;
    if (dimming >= 10) state.wiz.dimming = Math.max(10, Math.min(100, dimming));
    if (tempK >= 2200) state.wiz.temp = Math.max(2200, Math.min(6500, tempK));
    saveState();
    if (typeof TV === 'undefined' || !TV.wizSetPilot) {
      if (done) done(false, 'Local WiZ UDP requires LG webOS');
      return;
    }
    TV.wizSetPilot({
      targets: seedIpList(),
      on: state.wiz.on,
      dimming: state.wiz.dimming,
      temp: state.wiz.temp
    }, function (res) {
      if (!res || !res.supported) {
        if (done) done(false, 'Local WiZ UDP requires LG webOS');
      } else if (res.ok) {
        if (done) done(true, 'Lights -> ' + (state.wiz.on ? (state.wiz.dimming + '% · ' + kelvinLabel(state.wiz.temp)) : 'Off'));
      } else {
        if (done) done(false, 'No WiZ bulb responded on Wi-Fi');
      }
    });
  }

  function toggleDevice(id, done) {
    if (id === 'sensibo_ac') setSensibo(!state.sensibo.on, state.sensibo.mode, state.sensibo.temp, state.sensibo.fan, done);
    else if (id === 'tuya_ac') setTuya(!state.tuya.on, state.tuya.mode, state.tuya.temp, state.tuya.fan, done);
    else if (id === 'wiz_lights') setWizLights(!state.wiz.on, state.wiz.dimming, state.wiz.temp, done);
    else if (done) done(false, 'Unknown device');
  }

  function executeRoutine(routineId, done) {
    if (routineId === 'discover_wiz') { discoverWizBulbs(done); return; }
    if (routineId === 'movie_time') {
      if (hasWiz()) setWizLights(true, 20, 2700);
      if (hasSensibo()) setSensibo(true, 'cool', 22, 'low');
      if (done) done(true, 'Movie Time activated');
      return;
    }
    if (routineId === 'good_night') {
      if (hasWiz()) setWizLights(false, state.wiz.dimming, state.wiz.temp);
      if (hasSensibo()) setSensibo(false, state.sensibo.mode, state.sensibo.temp, state.sensibo.fan);
      if (hasTuya()) setTuya(false, state.tuya.mode, state.tuya.temp, state.tuya.fan);
      if (done) done(true, 'Good Night: all devices turned off');
      return;
    }
    if (routineId === 'all_lights_bright') {
      setWizLights(true, 100, 4200, done);
    }
  }

  function refreshAll(done) {
    var pending = 0;
    function finishOne() { if (--pending <= 0 && done) done(); }
    if (hasSensibo()) { pending++; syncSensibo(finishOne); }
    if (typeof TV !== 'undefined' && TV.supportsLocalWiz && hasWiz()) { pending++; discoverWizBulbs(finishOne); }
    if (pending === 0 && done) done();
  }

  return {
    state: state,
    bulbs: function () { return bulbs.slice(); },
    hasSensibo: hasSensibo,
    hasTuya: hasTuya,
    hasWiz: hasWiz,
    hasAnyConfigured: hasAnyConfigured,
    getDevices: getDevices,
    syncSensibo: syncSensibo,
    setSensibo: setSensibo,
    setTuya: setTuya,
    buildTuyaHeaders: buildTuyaHeaders,
    buildTuyaIrCommands: buildTuyaIrCommands,
    discoverWizBulbs: discoverWizBulbs,
    setWizLights: setWizLights,
    toggleDevice: toggleDevice,
    executeRoutine: executeRoutine,
    refreshAll: refreshAll,
    kelvinLabel: kelvinLabel
  };
})();

/* Supabase Multi-User Profiles (PostgREST Read/Write), Watchlist/Votes Sync & Realtime Cloud Remote. */
var SupabaseService = (function () {
  'use strict';

  var KEY_PROFILES = 'mytv.profiles';
  var KEY_ACTIVE = 'mytv.profile.active';
  var KEY_WATCHLIST = 'mytv.supabase.watchlist';
  var KEY_VOTES = 'mytv.supabase.votes';
  var PALETTE = ['#38BDF8', '#A855F7', '#EC4899', '#10B981', '#F59E0B', '#6366F1'];

  var SELECT_PROFILES = 'id,slug,display_name,avatar_color,trakt_username,is_default,created_at,updated_at';
  var SELECT_WATCHLIST = 'id,profile_id,media_key,imdb_id,kind,title,poster_url,backdrop_url,source_app,deep_link,created_at,updated_at';

  function loadJson(key, fallback) {
    try { var r = localStorage.getItem(key); return r ? JSON.parse(r) : fallback; } catch (e) { return fallback; }
  }
  function saveJson(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }

  function baseUrl() {
    var u = (typeof Prefs !== 'undefined' && Prefs.supabaseUrl) ? Prefs.supabaseUrl() : ((window.CONFIG && window.CONFIG.supabaseUrl) || '');
    return String(u).replace(/^\s+|\s+$/g, '').replace(/\/+$/, '');
  }
  function anonKey() {
    var k = (typeof Prefs !== 'undefined' && Prefs.supabaseAnonKey) ? Prefs.supabaseAnonKey() : ((window.CONFIG && window.CONFIG.supabaseAnonKey) || '');
    return String(k).replace(/^\s+|\s+$/g, '');
  }
  function householdKey() {
    var k = (typeof Prefs !== 'undefined' && Prefs.supabaseHouseholdKey) ? Prefs.supabaseHouseholdKey() : ((window.CONFIG && window.CONFIG.householdKey) || '');
    return String(k).replace(/^\s+|\s+$/g, '');
  }
  function isConfigured() { return !!(baseUrl() && anonKey()); }

  function isUuid(s) {
    return typeof s === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
  }

  /** Builds Dual-Path PostgREST auth headers (apikey + Bearer + optional x-mytv-household-key). */
  function buildHeaders(prefer) {
    var ak = anonKey(), hk = householdKey();
    var h = {
      'apikey': ak,
      'Authorization': 'Bearer ' + ak,
      'Accept': 'application/json'
    };
    if (hk) h['x-mytv-household-key'] = hk;
    if (prefer) h.Prefer = prefer;
    return h;
  }

  /** Normalizes a profile row (canonical snake_case with camelCase fallback). */
  function normalizeProfile(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var slug = String(raw.slug || raw.username || 'main').toLowerCase().replace(/[^a-z0-9_-]/g, '') || 'main';
    var display = String(raw.display_name || raw.displayName || slug).replace(/^\s+|\s+$/g, '') || slug;
    return {
      id: raw.id || MyTvCrypto.uuidV4(),
      slug: slug,
      display_name: display,
      avatar_color: raw.avatar_color || raw.avatarColor || PALETTE[0],
      trakt_username: String(raw.trakt_username || raw.traktUsername || ''),
      is_default: raw.is_default !== undefined ? !!raw.is_default : !!raw.isDefault
    };
  }

  var profiles = loadJson(KEY_PROFILES, []);
  if (!Array.isArray(profiles) || !profiles.length) {
    profiles = [normalizeProfile({id: MyTvCrypto.uuidV4(), slug: 'main', display_name: 'Main', avatar_color: PALETTE[0], is_default: true})];
    saveJson(KEY_PROFILES, profiles);
  }
  var activeSlug = '';
  try { activeSlug = localStorage.getItem(KEY_ACTIVE) || ''; } catch (e) {}

  function getProfiles() { return profiles.slice(); }

  function getActiveProfile() {
    for (var i = 0; i < profiles.length; i++) {
      if (profiles[i].slug === activeSlug) return profiles[i];
    }
    activeSlug = profiles[0].slug;
    return profiles[0];
  }

  function switchProfile(slug) {
    for (var i = 0; i < profiles.length; i++) {
      if (profiles[i].slug === slug) {
        activeSlug = profiles[i].slug;
        try { localStorage.setItem(KEY_ACTIVE, activeSlug); } catch (e) {}
        return profiles[i];
      }
    }
    return getActiveProfile();
  }

  function cycleProfile() {
    if (profiles.length <= 1) return getActiveProfile();
    var cur = getActiveProfile(), idx = 0;
    for (var i = 0; i < profiles.length; i++) if (profiles[i].slug === cur.slug) { idx = i; break; }
    return switchProfile(profiles[(idx + 1) % profiles.length].slug);
  }

  /** Reads household user profiles from Supabase public.user_profiles and updates local cache. */
  function fetchProfiles(done) {
    if (!isConfigured()) { if (done) done(null, getProfiles()); return; }
    MyTvHttp.request('GET', baseUrl() + '/rest/v1/user_profiles?select=' + SELECT_PROFILES + '&order=is_default.desc,created_at.asc',
      buildHeaders(null), null, 8000, function (err, data) {
        if (!err && Array.isArray(data) && data.length) {
          var merged = [], i, p;
          for (i = 0; i < data.length; i++) {
            p = normalizeProfile(data[i]);
            if (p) merged.push(p);
          }
          if (merged.length) {
            profiles = merged;
            saveJson(KEY_PROFILES, profiles);
          }
        }
        if (done) done(err || null, getProfiles());
      });
  }

  /** Creates or updates a user profile locally and upserts it to Supabase public.user_profiles. */
  function upsertProfile(displayName, traktUser, done) {
    var cleanName = String(displayName || '').replace(/^\s+|\s+$/g, '');
    if (!cleanName) { if (done) done(new Error('Profile name required')); return; }
    var slug = cleanName.toLowerCase().replace(/[^a-z0-9_]/g, '') || ('user' + (profiles.length + 1));
    var existingIdx = -1, i;
    for (i = 0; i < profiles.length; i++) if (profiles[i].slug === slug) { existingIdx = i; break; }
    var prof;
    if (existingIdx >= 0) {
      var old = profiles[existingIdx];
      prof = normalizeProfile({
        id: old.id, slug: old.slug, display_name: cleanName, avatar_color: old.avatar_color,
        trakt_username: traktUser !== undefined ? traktUser : old.trakt_username, is_default: old.is_default
      });
      profiles[existingIdx] = prof;
    } else {
      prof = normalizeProfile({
        id: MyTvCrypto.uuidV4(), slug: slug, display_name: cleanName,
        avatar_color: PALETTE[profiles.length % PALETTE.length],
        trakt_username: traktUser || '', is_default: false
      });
      profiles.push(prof);
    }
    saveJson(KEY_PROFILES, profiles);
    switchProfile(prof.slug);
    if (!isConfigured()) { if (done) done(null, prof); return; }
    MyTvHttp.request('POST', baseUrl() + '/rest/v1/user_profiles?on_conflict=slug',
      buildHeaders('resolution=merge-duplicates,return=minimal'), prof, 8000, function (err) {
        if (done) done(err || null, prof);
      });
  }

  /** Deletes a profile by slug (refusing to remove the last remaining profile). */
  function deleteProfile(slug, done) {
    if (profiles.length <= 1) { if (done) done(new Error('Cannot delete last profile')); return; }
    var next = [], removed = null;
    for (var i = 0; i < profiles.length; i++) {
      if (profiles[i].slug === slug) removed = profiles[i];
      else next.push(profiles[i]);
    }
    if (!removed) { if (done) done(new Error('Profile not found')); return; }
    profiles = next;
    saveJson(KEY_PROFILES, profiles);
    if (activeSlug === slug) switchProfile(profiles[0].slug);
    if (!isConfigured()) { if (done) done(null, true); return; }
    MyTvHttp.request('DELETE', baseUrl() + '/rest/v1/user_profiles?slug=eq.' + encodeURIComponent(slug),
      buildHeaders('return=minimal'), null, 8000, function (err) {
        if (done) done(err || null, true);
      });
  }

  /** Syncs the active profile's Supabase Watchlist. */
  function fetchWatchlist(done) {
    var prof = getActiveProfile();
    var cacheKey = KEY_WATCHLIST + '.' + prof.slug;
    if (!isConfigured() || !isUuid(prof.id)) {
      if (done) done(null, loadJson(cacheKey, []));
      return;
    }
    MyTvHttp.request('GET', baseUrl() + '/rest/v1/watchlist_items?select=' + SELECT_WATCHLIST +
      '&profile_id=eq.' + encodeURIComponent(prof.id) + '&order=created_at.desc&limit=100',
      buildHeaders(null), null, 8000, function (err, data) {
        if (!err && Array.isArray(data)) saveJson(cacheKey, data);
        if (done) done(err || null, loadJson(cacheKey, []));
      });
  }

  function getCachedWatchlist() {
    var prof = getActiveProfile();
    return loadJson(KEY_WATCHLIST + '.' + prof.slug, []);
  }

  function addWatchlistItem(item, done) {
    var prof = getActiveProfile();
    var cacheKey = KEY_WATCHLIST + '.' + prof.slug;
    var row = {
      profile_id: prof.id,
      media_key: item.mediaKey || (item.kind + ':' + item.id),
      imdb_id: item.imdbId || '',
      kind: item.kind === 'movie' ? 'movie' : 'tv',
      title: item.title || '',
      poster_url: item.posterUrl || '',
      backdrop_url: item.backdropUrl || '',
      source_app: item.sourceApp || 'stremio'
    };
    var arr = loadJson(cacheKey, []).filter(function (x) { return x.media_key !== row.media_key; });
    arr.unshift(row);
    saveJson(cacheKey, arr);
    if (!isConfigured() || !isUuid(prof.id)) { if (done) done(null, row); return; }
    MyTvHttp.request('POST', baseUrl() + '/rest/v1/watchlist_items?on_conflict=profile_id,media_key',
      buildHeaders('resolution=merge-duplicates,return=minimal'), row, 8000, function (err) {
        if (done) done(err || null, row);
      });
  }

  function removeWatchlistItem(mediaKey, done) {
    var prof = getActiveProfile();
    var cacheKey = KEY_WATCHLIST + '.' + prof.slug;
    var arr = loadJson(cacheKey, []).filter(function (x) { return x.media_key !== mediaKey; });
    saveJson(cacheKey, arr);
    if (!isConfigured() || !isUuid(prof.id)) { if (done) done(null); return; }
    MyTvHttp.request('DELETE', baseUrl() + '/rest/v1/watchlist_items?profile_id=eq.' + encodeURIComponent(prof.id) +
      '&media_key=eq.' + encodeURIComponent(mediaKey), buildHeaders('return=minimal'), null, 8000, function (err) {
        if (done) done(err || null);
      });
  }

  function fetchVotes(done) {
    var prof = getActiveProfile();
    var cacheKey = KEY_VOTES + '.' + prof.slug;
    if (!isConfigured() || !isUuid(prof.id)) { if (done) done(null, loadJson(cacheKey, {})); return; }
    MyTvHttp.request('GET', baseUrl() + '/rest/v1/media_votes?select=media_key,vote&profile_id=eq.' + encodeURIComponent(prof.id),
      buildHeaders(null), null, 8000, function (err, data) {
        if (!err && Array.isArray(data)) {
          var map = {};
          for (var i = 0; i < data.length; i++) if (data[i].media_key) map[data[i].media_key] = data[i].vote;
          saveJson(cacheKey, map);
        }
        if (done) done(err || null, loadJson(cacheKey, {}));
      });
  }

  function getCachedVotes() {
    var prof = getActiveProfile();
    return loadJson(KEY_VOTES + '.' + prof.slug, {});
  }

  function castVote(mediaKey, kind, title, vote, done) {
    var prof = getActiveProfile();
    var cacheKey = KEY_VOTES + '.' + prof.slug;
    var norm = vote >= 0 ? 1 : -1;
    var map = loadJson(cacheKey, {});
    map[mediaKey] = norm;
    saveJson(cacheKey, map);
    if (!isConfigured() || !isUuid(prof.id)) { if (done) done(null); return; }
    MyTvHttp.request('POST', baseUrl() + '/rest/v1/media_votes?on_conflict=profile_id,media_key',
      buildHeaders('resolution=merge-duplicates,return=minimal'), {
        profile_id: prof.id,
        media_key: mediaKey,
        kind: kind === 'movie' ? 'movie' : 'tv',
        title: title || '',
        vote: norm
      }, 8000, function (err) { if (done) done(err || null); });
  }

  /** Registers this LG/Samsung TV in public.tv_devices for Companion Remote discovery. */
  function registerTvDevice(info, done) {
    if (!isConfigured()) { if (done) done(null); return; }
    var slug = (typeof TV !== 'undefined' && TV.deviceId) ? TV.deviceId() : 'web-tv';
    var brand = (typeof TV !== 'undefined' && TV.name === 'webos') ? 'LG_WEBOS' :
                (typeof TV !== 'undefined' && TV.name === 'tizen') ? 'SAMSUNG_TIZEN' : 'WEB';
    var payload = {
      device_slug: slug,
      device_name: (info && info.model) ? ('MyTV ' + info.model) : ('MyTV ' + brand),
      brand: brand,
      model: (info && info.model) || brand,
      lan_ip: (info && info.ip) || '',
      remote_port: 0
    };
    MyTvHttp.request('POST', baseUrl() + '/rest/v1/tv_devices?on_conflict=device_slug',
      buildHeaders('resolution=merge-duplicates,return=minimal'), payload, 8000, function (err) {
        if (done) done(err || null);
      });
  }

  // MARK: - Supabase Realtime Cloud Remote Channel (Phoenix WebSocket v1)

  var rtSocket = null, rtHb = null, rtReconnect = null, rtRef = 1, rtConnected = false;
  var rtBackoff = 2000, rtWanted = false;
  var rtCommandHandler = null, rtStatusHandler = null;

  /** Builds the Supabase Realtime WebSocket URL (wss://.../realtime/v1/websocket?apikey=...&vsn=1.0.0). */
  function buildRealtimeUrl(url, key) {
    var clean = String(url || '').replace(/^\s+|\s+$/g, '').replace(/\/+$/, '');
    if (!clean || !key) return '';
    var wsBase = clean.replace(/^https:\/\//i, 'wss://').replace(/^http:\/\//i, 'ws://');
    return wsBase + '/realtime/v1/websocket?apikey=' + encodeURIComponent(key) + '&vsn=1.0.0';
  }

  function channelCode() {
    var saved = (typeof Prefs !== 'undefined' && Prefs.cloudRemoteChannel) ? Prefs.cloudRemoteChannel() : '';
    if (saved) return saved;
    return (typeof TV !== 'undefined' && TV.deviceId) ? TV.deviceId() : 'mytv-web';
  }

  function channelTopic() {
    return 'realtime:mytv-remote:' + channelCode();
  }

  function notifyStatus(connected) {
    rtConnected = !!connected;
    if (rtStatusHandler) rtStatusHandler(rtConnected);
  }

  function buildJoinFrame(topic, ref) {
    return JSON.stringify({
      topic: topic,
      event: 'phx_join',
      payload: {config: {broadcast: {ack: false, self: false}, presence: {key: channelCode()}}},
      ref: String(ref)
    });
  }

  function buildBroadcastFrame(topic, eventName, payload, ref) {
    return JSON.stringify({
      topic: topic,
      event: 'broadcast',
      payload: {type: 'broadcast', event: eventName, payload: payload || {}},
      ref: String(ref)
    });
  }

  /** Parses an inbound Phoenix Realtime WebSocket message and returns a remote command object or null. */
  function parseRealtimeCommand(rawData) {
    var msg = null;
    try { msg = typeof rawData === 'string' ? JSON.parse(rawData) : rawData; } catch (e) { return null; }
    if (!msg) return null;
    if (msg.event === 'phx_reply' && msg.payload && msg.payload.status === 'ok') return {__joined: true};
    if (msg.event === 'broadcast' && msg.payload) {
      var inner = msg.payload.payload || msg.payload;
      var evt = msg.payload.event || '';
      if (evt === 'remote_command' && inner && typeof inner === 'object') return inner;
    }
    return null;
  }

  function disconnectRemote() {
    rtWanted = false;
    if (rtHb) { clearInterval(rtHb); rtHb = null; }
    if (rtReconnect) { clearTimeout(rtReconnect); rtReconnect = null; }
    if (rtSocket) {
      try { rtSocket.onclose = null; rtSocket.onerror = null; rtSocket.onmessage = null; rtSocket.close(); } catch (e) {}
      rtSocket = null;
    }
    notifyStatus(false);
  }

  function sendStateSnapshot(snapshot) {
    if (!rtSocket || rtSocket.readyState !== 1 || !rtConnected) return false;
    try {
      rtSocket.send(buildBroadcastFrame(channelTopic(), 'tv_state', snapshot, rtRef++));
      return true;
    } catch (e) { return false; }
  }

  function connectRemote(onCommand, onStatus) {
    if (onCommand) rtCommandHandler = onCommand;
    if (onStatus) rtStatusHandler = onStatus;
    var enabled = (typeof Prefs !== 'undefined' && Prefs.cloudRemoteEnabled) ? Prefs.cloudRemoteEnabled() : true;
    if (!enabled || !isConfigured() || typeof WebSocket === 'undefined') {
      disconnectRemote();
      return;
    }
    disconnectRemote();
    rtWanted = true;
    var wsUrl = buildRealtimeUrl(baseUrl(), anonKey());
    if (!wsUrl) return;
    try {
      rtSocket = new WebSocket(wsUrl);
    } catch (e) {
      notifyStatus(false);
      return;
    }
    var topic = channelTopic();
    rtSocket.onopen = function () {
      rtBackoff = 2000;
      try { rtSocket.send(buildJoinFrame(topic, rtRef++)); } catch (e) {}
      rtHb = setInterval(function () {
        if (rtSocket && rtSocket.readyState === 1) {
          try { rtSocket.send(JSON.stringify({topic: 'phoenix', event: 'heartbeat', payload: {}, ref: String(rtRef++)})); } catch (e) {}
        }
      }, 25000);
    };
    rtSocket.onmessage = function (ev) {
      var parsed = parseRealtimeCommand(ev && ev.data);
      if (!parsed) return;
      if (parsed.__joined) { notifyStatus(true); return; }
      if (rtCommandHandler) rtCommandHandler(parsed);
    };
    rtSocket.onerror = function () { notifyStatus(false); };
    rtSocket.onclose = function () {
      if (rtHb) { clearInterval(rtHb); rtHb = null; }
      notifyStatus(false);
      if (rtWanted) {
        var wait = rtBackoff;
        rtBackoff = Math.min(30000, rtBackoff * 2);
        rtReconnect = setTimeout(function () { if (rtWanted) connectRemote(); }, wait);
      }
    };
  }

  return {
    isConfigured: isConfigured,
    buildHeaders: buildHeaders,
    normalizeProfile: normalizeProfile,
    getProfiles: getProfiles,
    getActiveProfile: getActiveProfile,
    switchProfile: switchProfile,
    cycleProfile: cycleProfile,
    fetchProfiles: fetchProfiles,
    upsertProfile: upsertProfile,
    deleteProfile: deleteProfile,
    fetchWatchlist: fetchWatchlist,
    getCachedWatchlist: getCachedWatchlist,
    addWatchlistItem: addWatchlistItem,
    removeWatchlistItem: removeWatchlistItem,
    fetchVotes: fetchVotes,
    getCachedVotes: getCachedVotes,
    castVote: castVote,
    registerTvDevice: registerTvDevice,
    buildRealtimeUrl: buildRealtimeUrl,
    buildJoinFrame: buildJoinFrame,
    buildBroadcastFrame: buildBroadcastFrame,
    parseRealtimeCommand: parseRealtimeCommand,
    channelCode: channelCode,
    channelTopic: channelTopic,
    isRemoteConnected: function () { return rtConnected; },
    connectRemote: connectRemote,
    disconnectRemote: disconnectRemote,
    sendStateSnapshot: sendStateSnapshot
  };
})();

/* Gaming Tab Integration: Moonlight-TV detection, Gaming PC HDMI input & Console HDMI tiles. */
var GamingService = (function () {
  'use strict';

  var CONSOLE_RE = /playstation|ps[345]|xbox|nintendo|switch|wii|steam\s*deck/i;
  var PC_HDMI_RE = /gaming\s*pc|\bpc\b|laptop|computer/i;

  function configuredHdmiKey() {
    var raw = (typeof Prefs !== 'undefined' && Prefs.gamingHdmi) ? Prefs.gamingHdmi() : ((window.CONFIG && window.CONFIG.gamingPcHdmi) || '');
    var clean = String(raw || '').toLowerCase().replace(/\s+/g, '');
    if (/^hdmi[1-4]$/.test(clean)) return clean;
    return '';
  }

  function parseRoleEntries() {
    var raw = (window.CONFIG && window.CONFIG.hdmiPortRoles) || '';
    var parts = raw ? String(raw).split(',') : [], out = {}, i, seg;
    for (i = 0; i < parts.length; i++) {
      seg = parts[i].split(':');
      if (seg.length >= 2) {
        var port = String(seg[0]).replace(/\D/g, '');
        if (port >= '1' && port <= '4') {
          out['hdmi' + port] = {
            role: String(seg[1]).toLowerCase().replace(/^\s+|\s+$/g, ''),
            label: seg.slice(2).join(':').replace(/^\s+|\s+$/g, '')
          };
        }
      }
    }
    return out;
  }

  /** Builds all launchable Gaming tiles (Moonlight-TV, Gaming PC HDMI, Consoles, other game apps). */
  function buildTiles(apps, inputs) {
    var tiles = [], seenIds = {};
    var moonlight = (typeof TV !== 'undefined' && TV.findMoonlightApp) ? TV.findMoonlightApp(apps) : null;
    var pcIp = (typeof Prefs !== 'undefined' && Prefs.gamingPcIp) ? Prefs.gamingPcIp() : ((window.CONFIG && window.CONFIG.gamingPcIp) || '');
    if (moonlight) {
      seenIds[moonlight.id] = true;
      tiles.push({
        id: 'moonlight_app',
        kind: 'app',
        appId: moonlight.id,
        title: moonlight.title || 'Moonlight',
        subtitle: pcIp ? ('Stream PC (' + pcIp + ') · Moonlight-TV') : 'Moonlight-TV Game Streaming',
        icon: 'moonlight',
        colorTop: '#2563EB',
        colorBottom: '#1D4ED8',
        active: true
      });
    }

    var hdmiKey = configuredHdmiKey();
    var roles = parseRoleEntries();
    var inList = inputs || [], i, inp, roleInfo, label;

    for (i = 0; i < inList.length; i++) {
      inp = inList[i];
      if (!inp || inp.key === 'live') continue;
      roleInfo = roles[inp.key];
      label = (roleInfo && roleInfo.label) || inp.label || inp.key.toUpperCase();
      var isGamingPc = inp.key === hdmiKey || (roleInfo && roleInfo.role === 'laptop') || PC_HDMI_RE.test(label);
      var isConsole = (roleInfo && roleInfo.role === 'console') || CONSOLE_RE.test(label);
      if (isGamingPc) {
        tiles.push({
          id: 'gaming_hdmi_' + inp.key,
          kind: 'input',
          appId: inp.id,
          hdmiKey: inp.key,
          title: 'Gaming PC (' + label + ')',
          subtitle: inp.connected ? 'HDMI Connected · Switch Input' : 'HDMI Input · Standby',
          icon: 'hdmi',
          colorTop: inp.connected ? '#0284C7' : '#2C2D31',
          colorBottom: inp.connected ? '#0369A1' : '#1E2025',
          active: !!inp.connected
        });
      } else if (isConsole) {
        tiles.push({
          id: 'console_hdmi_' + inp.key,
          kind: 'input',
          appId: inp.id,
          hdmiKey: inp.key,
          title: label,
          subtitle: inp.connected ? 'Console Connected · Switch Input' : 'Console HDMI · Standby',
          icon: 'gaming',
          colorTop: inp.connected ? '#4F46E5' : '#2C2D31',
          colorBottom: inp.connected ? '#3730A3' : '#1E2025',
          active: !!inp.connected
        });
      }
    }

    var others = (typeof TV !== 'undefined' && TV.findGamingApps) ? TV.findGamingApps(apps) : [];
    for (i = 0; i < others.length; i++) {
      if (seenIds[others[i].id]) continue;
      seenIds[others[i].id] = true;
      tiles.push({
        id: 'game_app_' + others[i].id,
        kind: 'app',
        appId: others[i].id,
        title: others[i].title,
        subtitle: 'Installed Game Streaming App',
        icon: 'gaming',
        colorTop: '#7C3AED',
        colorBottom: '#4C1D95',
        active: true
      });
    }
    return tiles;
  }

  /** True when Moonlight-TV, another gaming app, Gaming PC HDMI, PC IP/MAC, or a console HDMI is present. */
  function hasGamingSetup(apps, inputs) {
    if (configuredHdmiKey()) return true;
    var pcIp = (typeof Prefs !== 'undefined' && Prefs.gamingPcIp) ? Prefs.gamingPcIp() : ((window.CONFIG && window.CONFIG.gamingPcIp) || '');
    var pcMac = (typeof Prefs !== 'undefined' && Prefs.gamingPcMac) ? Prefs.gamingPcMac() : ((window.CONFIG && window.CONFIG.gamingPcMac) || '');
    if (pcIp || pcMac) return true;
    return buildTiles(apps, inputs).length > 0;
  }

  /** Best-effort check of optional MyTV Laptop Gaming Agent (:8485); never blocks or fails UI. */
  function fetchPcStatus(done) {
    var ip = (typeof Prefs !== 'undefined' && Prefs.gamingPcIp) ? Prefs.gamingPcIp() : ((window.CONFIG && window.CONFIG.gamingPcIp) || '');
    if (!ip) { if (done) done(null); return; }
    MyTvHttp.request('GET', 'http://' + ip + ':8485/api/gaming/status', {Accept: 'application/json'}, null, 2000, function (err, data) {
      if (err || !data) { if (done) done(null); return; }
      if (done) done(data);
    });
  }

  return {
    configuredHdmiKey: configuredHdmiKey,
    buildTiles: buildTiles,
    hasGamingSetup: hasGamingSetup,
    fetchPcStatus: fetchPcStatus
  };
})();
