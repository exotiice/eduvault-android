/* EduVault Android : exécuté AVANT le code du site (injecté dans <head>).
   - désactive le service worker (les fichiers sont déjà dans l'APK ; évite un cache périmé)
   - ajoute la dictée vocale (webkitSpeechRecognition) via le plugin natif SpeechRecognition
   - pointe le suivi ENIAD vers le site en ligne (data/timetables.json n'est pas dans l'APK) */
(function () {
  var C = window.Capacitor;
  var native = !!(C && C.isNativePlatform && C.isNativePlatform()) ||
    (location.hostname === 'localhost' && location.protocol === 'https:' && /Android/.test(navigator.userAgent));
  if (!native) return;
  window.__NATIVE = true;
  try {
    if (navigator.serviceWorker) {
      navigator.serviceWorker.register = function () {
        return Promise.reject(new Error('service worker désactivé dans l\'app'));
      };
    }
  } catch (e) {}
  try {
    if (!localStorage.getItem('eduVault_enfeed')) {
      localStorage.setItem('eduVault_enfeed', 'https://raw.githubusercontent.com/exotiice/eduvault/main/data/timetables.json');
    }
  } catch (e) {}

  /* ---------- dictée vocale : le WebView Android n'a pas webkitSpeechRecognition ----------
     On fournit la même API (start/stop, onresult/onerror/onend, continuous, interimResults, lang)
     au-dessus du plugin natif. Android arrête l'écoute à chaque pause : en mode continu on relance. */
  try {
    var sp = function () {
      try { return (C.Plugins && C.Plugins.SpeechRecognition) || (C.registerPlugin && C.registerPlugin('SpeechRecognition')); } catch (e) { return null; }
    };
    var mk = function (txt, fin) { var r = [{ transcript: txt, confidence: 0.9 }]; r.isFinal = fin; return r; };
    var SRShim = function () {
      this.lang = 'fr-FR'; this.continuous = false; this.interimResults = false;
      this.onresult = this.onerror = this.onend = this.onstart = null;
      this._on = false;
    };
    SRShim.prototype._emit = function (name, ev) { try { if (typeof this[name] === 'function') this[name](ev || {}); } catch (e) {} };
    SRShim.prototype.start = function () {
      var self = this, SP = sp();
      if (self._on) throw new Error('already started');
      self._on = true; self._stop = false; self._last = ''; self._quick = 0; self._ending = false;
      Promise.resolve().then(function () {
        if (!SP) throw 'service-not-allowed';
        return SP.available().then(function (a) { if (!a || !a.available) throw 'service-not-allowed'; });
      }).then(function () {
        return SP.checkPermissions();
      }).then(function (p) {
        return p && p.speechRecognition === 'granted' ? p : SP.requestPermissions();
      }).then(function (p) {
        if (!p || p.speechRecognition !== 'granted') throw 'not-allowed';
        return SP.addListener('partialResults', function (e) {
          var t = e && e.matches && e.matches[0];
          if (!t || !self._on) return;
          self._last = String(t);
          if (self.interimResults) self._emit('onresult', { resultIndex: 0, results: [mk(self._last, false)] });
        });
      }).then(function (h) {
        self._h = h;
        self._emit('onstart');
        return self._begin();
      }).catch(function (err) { self._fail(err); });
    };
    SRShim.prototype._begin = function () {
      var self = this, SP = sp();
      self._t0 = Date.now(); self._ending = false;
      return SP.start({ language: self.lang || 'fr-FR', maxResults: 1, partialResults: true, popup: false }).then(function () {
        clearInterval(self._iv);
        self._iv = setInterval(function () {
          if (!self._on || self._ending) return;
          SP.isListening().then(function (r) {
            if (!self._on || self._ending || (r && r.listening)) return;
            self._ending = true; clearInterval(self._iv);
            setTimeout(function () { self._segEnd(); }, 650); // laisse arriver le résultat final
          }).catch(function () {});
        }, 350);
      });
    };
    SRShim.prototype._segEnd = function () {
      var self = this;
      if (!self._on) return;
      var txt = self._last; self._last = '';
      if (txt) { self._quick = 0; self._emit('onresult', { resultIndex: 0, results: [mk(txt, true)] }); }
      else if (Date.now() - self._t0 < 1500) self._quick++;
      if (self._stop || !self.continuous) return self._finish();
      if (self._quick >= 4) return self._fail('no-speech');
      setTimeout(function () { if (self._on && !self._stop) self._begin().catch(function (e) { self._fail(e); }); else self._finish(); }, 250);
    };
    SRShim.prototype._fail = function (err) {
      var m = String((err && err.message) || err || ''), code = 'network';
      if (/not-allowed|permission|denied/i.test(m)) code = 'not-allowed';
      else if (/service-not-allowed|unavailable|not available/i.test(m)) code = 'service-not-allowed';
      else if (/no-speech|no match|speech timeout/i.test(m)) code = 'no-speech';
      this._emit('onerror', { error: code, message: m });
      this._finish();
    };
    SRShim.prototype._finish = function () {
      if (!this._on) return;
      this._on = false; clearInterval(this._iv);
      try { if (this._h && this._h.remove) this._h.remove(); } catch (e) {}
      this._h = null;
      this._emit('onend');
    };
    SRShim.prototype.stop = SRShim.prototype.abort = function () {
      var self = this, SP = sp();
      if (!self._on) return;
      self._stop = true;
      try { SP && SP.stop(); } catch (e) {}
      // si rien n'écoutait (entre deux segments), on termine nous-mêmes
      setTimeout(function () { if (self._on && !self._ending) { self._ending = true; clearInterval(self._iv); self._segEnd(); } }, 1200);
    };
    // toujours remplacer : si le WebView expose une version, elle est inutilisable sans service de reconnaissance
    window.webkitSpeechRecognition = SRShim; window.SpeechRecognition = SRShim;
  } catch (e) {}

  /* ---------- Google (Agenda) : faux google.accounts.oauth2 branché sur la connexion native ----------
     Le site appelle initTokenClient({scope, callback, error_callback}).requestAccessToken() ; gLib() considère
     la bibliothèque déjà chargée si window.google.accounts.oauth2 existe : le vrai script Google n'est jamais chargé. */
  /* Le site charge le VRAI script Google (accounts.google.com/gsi/client) pour Drive, ce qui REMPLACE le faux google.accounts.oauth2
     ci-dessous par la version web (fenêtre « Sign in with Google » à chaque lancement). On neutralise ce script : il « se charge »
     (onload part) mais ne fait rien, donc seul notre branchement sur la connexion native reste en place. */
  try {
    var GSI = /accounts\.google\.com\/gsi\/client/i, EMPTY = 'data:text/javascript;charset=utf-8,%2F%2F%20neutralise';
    var sd = Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype, 'src');
    if (sd && sd.set) {
      Object.defineProperty(HTMLScriptElement.prototype, 'src', {
        configurable: true, enumerable: sd.enumerable, get: sd.get,
        set: function (v) { sd.set.call(this, GSI.test(String(v)) ? EMPTY : v); }
      });
    }
    var sa = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function (k, v) {
      if (this instanceof HTMLScriptElement && String(k).toLowerCase() === 'src' && GSI.test(String(v))) v = EMPTY;
      return sa.call(this, k, v);
    };
  } catch (e) {}
  try {
    document.addEventListener('click', function (e) {
      try {
        var t = e.target && e.target.closest && e.target.closest('[data-a]');
        if (t && /^(drive|dh|gc\w*)$/i.test(t.getAttribute('data-a') || '')) window.__evTap = Date.now();
      } catch (x) {}
    }, true);
  } catch (e) {}
  try {
    window.google = window.google || {};
    window.google.accounts = window.google.accounts || {};
    var SHIM = {
      initTokenClient: function (cfg) {
        return {
          requestAccessToken: function (o) {
            o = o || {};
            // reconnexion automatique (au lancement / au premier toucher) = silencieuse ; fenêtre Google seulement après un clic explicite sur ☁️ / Google Agenda
            var silent = !(window.__evTap && Date.now() - window.__evTap < 2500);
            var G; try { G = (C.Plugins && C.Plugins.EduGoogle) || (C.registerPlugin && C.registerPlugin('EduGoogle')); } catch (e) {}
            if (!G) { cfg.error_callback && cfg.error_callback({ type: 'popup_failed_to_open' }); return; }
            G.authorize({ scope: cfg.scope, silent: silent, hint: o.hint || '' }).then(function (r) {
              cfg.callback({ access_token: r.accessToken, expires_in: r.expiresIn });
            }).catch(function (e) {
              var m = (e && e.message) || String(e);
              cfg.callback({ error: /access_denied/.test(m) ? 'access_denied' : m });   // « interaction_required » : le site réessaiera au clic sur ☁️
            });
          }
        };
      },
      revoke: function (t, done) { try { done && done(); } catch (e) {} }
    };
    var ACC = window.google.accounts;
    /* verrouillé : même si un script Google se charge quand même, il ne peut plus remplacer notre connexion native */
    Object.defineProperty(ACC, 'oauth2', { configurable: false, enumerable: true, get: function () { return SHIM; }, set: function () {} });
    Object.defineProperty(window.google, 'accounts', {
      configurable: false, enumerable: true, get: function () { return ACC; },
      set: function (v) { try { for (var k in v) if (k !== 'oauth2') ACC[k] = v[k]; } catch (e) {} }
    });
  } catch (e) {}
  /* plus jamais de page accounts.google.com dans le navigateur (l'erreur 400) : toute connexion passe par la fenêtre native */
  try {
    var wo = window.open;
    window.open = function (u) {
      try { if (/^(https?:)?\/\/accounts\.google\.com/i.test(String(u || ''))) return null; } catch (e) {}
      return wo.apply(window, arguments);
    };
  } catch (e) {}
})();
