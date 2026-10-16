/* EduVault Android : couche native, injectée à la FIN de index.html (après le code du site).
   Ne modifie pas le site : elle enveloppe quelques fonctions et ajoute les fonctions natives
   (widgets, rappels, bouton retour, téléchargements, liens externes, barre d'état).
   Tout est protégé par try/catch : si le site change, l'appli continue de fonctionner. */
(function () {
  'use strict';
  var C = window.Capacitor;
  if (!(window.__NATIVE || (C && C.isNativePlatform && C.isNativePlatform()))) return;
  window.__NATIVE = true;
  document.documentElement.classList.add('native');

  var P = function (n) {
    try { if (C && C.Plugins && C.Plugins[n]) return C.Plugins[n]; } catch (e) {}
    try { if (C && C.registerPlugin) return C.registerPlugin(n); } catch (e) {}
    return null;
  };
  var safe = function (f) { try { return f(); } catch (e) { try { console.warn('[native]', e); } catch (_) {} } };
  var LSget = function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } };
  var LSset = function (k, v) { try { localStorage.setItem(k, v); } catch (e) {} };

  /* ---------- Google Drive + Google Agenda : connexion native (Google bloque OAuth dans un WebView) ----------
     Drive : on remplace drvAuth() du site (même effet : jeton mis dans tok). Agenda : le code du site est dans une
     fonction fermée, donc head.js fournit un faux google.accounts.oauth2 qui appelle le même plugin.
     Le jeton vient de l'API Authorization de Google Play Services (plugin EduGoogle), silencieux si déjà accordé. */
  function gToken(scope) {
    var G = P('EduGoogle');
    if (!G) return Promise.reject(new Error('Connexion Google indisponible dans cette version'));
    return G.authorize({ scope: scope }).catch(function (e) { throw new Error((e && e.message) || String(e)); });
  }
  safe(function () {
    window.drvAuth = function () {
      return gToken(SCOPE).then(function (r) {
        tok = r.accessToken;
        for (var k in DRVF) delete DRVF[k];
        clearTimeout(tokT);
        tokT = setTimeout(function () { tok = null; paintDrive(); armReconnect(); }, ((+r.expiresIn || 3300) - 90) * 1000);
        LS(LK(), '1'); drvErr = ''; paintDrive();
      });
    };
  });

  /* ---------- barre d'état aux couleurs du thème ---------- */
  function paintStatusBar() {
    safe(function () {
      var S = P('StatusBar'); if (!S) return;
      var dark = document.documentElement.classList.contains('dark');
      S.setOverlaysWebView({ overlay: false });
      S.setStyle({ style: dark ? 'DARK' : 'LIGHT' });
      S.setBackgroundColor({ color: dark ? '#1a1714' : '#fbf9f5' });
    });
  }
  paintStatusBar();
  safe(function () {
    new MutationObserver(paintStatusBar).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  });

  /* ---------- bouton retour du téléphone ---------- */
  safe(function () {
    var App = P('App'); if (!App || !App.addListener) return;
    App.addListener('backButton', function (e) {
      var dialogs = document.querySelectorAll('dialog[open]');
      if (dialogs.length) { dialogs[dialogs.length - 1].close(); return; }
      if (e && e.canGoBack) { history.back(); return; }
      if (typeof tab !== 'undefined' && tab !== 'da' && typeof go === 'function') { go('da'); return; }
      App.exitApp();
    });
  });

  /* ---------- téléchargements (export .ics, sauvegarde .json, fichiers) : on partage via le menu Android ---------- */
  function blobToBase64(blob) {
    return new Promise(function (ok, ko) {
      var r = new FileReader();
      r.onload = function () { ok(String(r.result).split(',')[1] || ''); };
      r.onerror = ko;
      r.readAsDataURL(blob);
    });
  }
  function saveAndShare(name, blob) {
    var FS = P('Filesystem'), SH = P('Share');
    if (!FS || !SH) return Promise.reject(new Error('Partage indisponible'));
    return blobToBase64(blob).then(function (b64) {
      var clean = String(name || 'fichier').replace(/[^\w.\- ]+/g, '_');
      return FS.writeFile({ path: 'export/' + Date.now() + '-' + clean, data: b64, directory: 'CACHE', recursive: true });
    }).then(function (w) {
      return SH.share({ title: name, dialogTitle: name, url: w.uri });
    });
  }
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    /* fichier généré par le site (blob:/data:) avec attribut download */
    if (a.hasAttribute('download') && /^(blob:|data:)/.test(href)) {
      ev.preventDefault(); ev.stopPropagation();
      fetch(href).then(function (r) { return r.blob(); })
        .then(function (b) { return saveAndShare(a.getAttribute('download') || 'fichier', b); })
        .catch(function (e) { safe(function () { toast('Export impossible : ' + (e && e.message || e), 'er'); }); });
      return;
    }
    /* liens externes : navigateur du téléphone (le WebView ne sait pas ouvrir target=_blank) */
    if (/^https?:\/\//i.test(href)) {
      var u; try { u = new URL(href); } catch (e) { return; }
      if (u.origin !== location.origin) {
        ev.preventDefault();
        var B = P('Browser');
        if (B) B.open({ url: href }); else window.open(href, '_system');
      }
    }
  }, true);

  /* ---------- résumé pour les widgets ---------- */
  function weekFor(d) {
    var s = A.settings, c = s.currentWeek || 'A';
    if (!s.weekRef) return c;
    var k = Math.round((monday(d) - monday(new Date(s.weekRef + 'T00:00'))) / 6048e5);
    return Math.abs(k) % 2 === 0 ? c : (c === 'A' ? 'B' : 'A');
  }
  function sessionsFor(date, ids) {
    var k = iso(date), dn = DAYS[(date.getDay() + 6) % 7], hol = isHol(k), wk = weekFor(date);
    return A.timetable.filter(function (c) {
      if (!ids.has(c.moduleId) || c.day !== dn) return false;
      if (c.date) return c.date === k;
      return !hol && (c.week === 'all' || c.week === wk);
    }).sort(function (a, b) { return a.startTime.localeCompare(b.startTime); });
  }
  function buildSummary() {
    var ids = curIds(), now = new Date(), days = {}, hol = {};
    for (var i = 0; i < 8; i++) {
      var d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i), k = iso(d);
      if (isHol(k)) hol[k] = 1;
      days[k] = sessionsFor(d, ids).map(function (c) {
        var mo = A.modules.find(function (m) { return m.id === c.moduleId; });
        return { s: c.startTime, e: c.endTime, n: modName(c.moduleId), r: c.room || '', c: (mo && mo.color) || '' };
      });
    }
    var tasks = A.tasks.filter(function (t) {
      return !t.completed && t.type !== 'review' && new Date(t.dueDate).getTime() > Date.now() - 864e5;
    }).sort(function (a, b) { return new Date(a.dueDate) - new Date(b.dueDate); }).slice(0, 8).map(function (t) {
      return { t: new Date(t.dueDate).getTime(), n: t.title, m: modName(t.moduleId), x: t.type === 'exam' ? 1 : 0 };
    });
    var cards = A.flashcards.filter(function (f) { return ids.has(f.moduleId) && (!f.nextRev || f.nextRev <= Date.now()); }).length;
    return { u: Date.now(), days: days, hol: hol, tasks: tasks, cards: cards };
  }
  var lastWidget = '';
  function pushWidget() {
    safe(function () {
      var W = P('EduWidget'); if (!W) return;
      var sum = buildSummary(), j = JSON.stringify(Object.assign({}, sum, { u: 0 }));
      if (j === lastWidget) return;
      lastWidget = j;
      W.setData({ json: JSON.stringify(sum) }).catch(function () {});
    });
  }

  /* ---------- rappels natifs (cours + échéances), même si l'appli est fermée ---------- */
  var lastNotif = '', notifAsked = false;
  var hash = function (s) { var h = 2166136261; for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 1) || 1; };
  function buildNotifs() {
    var now = Date.now(), out = [], ids = curIds();
    var mins = +(LSget('eduVault_clsRem') || 10);
    if (mins > 0) {
      for (var i = 0; i < 7; i++) {
        var d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + i);
        sessionsFor(d, ids).forEach(function (c) {
          var p = c.startTime.split(':'), at = new Date(d); at.setHours(+p[0], +p[1] - mins, 0, 0);
          if (at.getTime() > now) out.push({ key: 'c' + c.id + iso(d), at: at, title: modName(c.moduleId) + ' · dans ' + mins + ' min', body: '📍 ' + (c.room || '') + ' · ' + c.startTime });
        });
      }
    }
    A.tasks.filter(function (t) { return !t.completed && t.type !== 'review'; }).forEach(function (t) {
      var due = new Date(t.dueDate), h1 = new Date(due.getTime() - 36e5);
      if (h1.getTime() > now) out.push({ key: 't' + t.id + 'h', at: h1, title: 'Rappel : ' + t.title, body: "Échéance dans moins d'une heure !" });
      var ev = new Date(due); ev.setDate(ev.getDate() - 1); ev.setHours(18, 0, 0, 0);
      if (ev.getTime() > now && ev.getTime() < due.getTime()) out.push({ key: 't' + t.id + 'd', at: ev, title: 'Demain : ' + t.title, body: 'Révisez ce soir !' });
    });
    out.sort(function (a, b) { return a.at - b.at; });
    return out.slice(0, 60);
  }
  function scheduleNotifs() {
    safe(function () {
      var LN = P('LocalNotifications'); if (!LN) return;
      var list = buildNotifs(), sig = JSON.stringify(list.map(function (n) { return [n.key, +n.at, n.title]; }));
      if (sig === lastNotif) return;
      LN.checkPermissions().then(function (p) {
        if (p.display === 'granted') return p;
        if (notifAsked || LSget('eduVault_nativeNotifAsked') === '1') return p;
        notifAsked = true; LSset('eduVault_nativeNotifAsked', '1');
        return LN.requestPermissions();
      }).then(function (p) {
        if (!p || p.display !== 'granted') return;
        lastNotif = sig;
        var old = []; try { old = JSON.parse(LSget('eduVault_nIds') || '[]'); } catch (e) {}
        var cancel = old.length ? LN.cancel({ notifications: old.map(function (id) { return { id: id }; }) }) : Promise.resolve();
        return cancel.catch(function () {}).then(function () {
          return LN.createChannel({ id: 'eduvault', name: 'Rappels EduVault', description: 'Cours et échéances', importance: 4, visibility: 1 }).catch(function () {});
        }).then(function () {
          var notifs = list.map(function (n) {
            return { id: hash(n.key), title: n.title, body: n.body, channelId: 'eduvault', schedule: { at: n.at, allowWhileIdle: true } };
          });
          LSset('eduVault_nIds', JSON.stringify(notifs.map(function (n) { return n.id; })));
          return notifs.length ? LN.schedule({ notifications: notifs }) : null;
        });
      }).catch(function () { lastNotif = ''; });
    });
  }

  /* ---------- notifications push (FCM) : annonces même appli fermée ---------- */
  var PN = P('PushNotifications'), lastTok = '', tokWaiters = [], hasFb = null;
  function firebaseConfigured() {
    if (hasFb !== null) return Promise.resolve(hasFb);
    var G = P('EduGoogle');
    if (!G || !G.hasFirebase) return Promise.resolve(false);
    return G.hasFirebase().then(function (r) { hasFb = !!(r && r.value); return hasFb; }).catch(function () { return false; });
  }
  function fbReady() { return typeof fb !== 'undefined' && fb && typeof fbUser !== 'undefined' && fbUser; }
  function saveToken(t) {
    return fb.db.collection('tokens').doc(fbUser.uid).set({ t: t, ts: Date.now(), ua: 'EduVault Android' });
  }
  safe(function () {
    if (!PN) return;
    PN.addListener('registration', function (t) {
      lastTok = (t && t.value) || '';
      var w = tokWaiters; tokWaiters = [];
      w.forEach(function (f) { f.ok(lastTok); });
      if (lastTok && LSget('eduVault_push') === '1' && fbReady()) saveToken(lastTok).catch(function () {});
    });
    PN.addListener('registrationError', function (e) {
      var w = tokWaiters; tokWaiters = [];
      w.forEach(function (f) { f.ko(new Error((e && e.error) || 'Enregistrement FCM impossible')); });
    });
    PN.addListener('pushNotificationReceived', function (n) {
      safe(function () { toast('🔔 ' + ((n && n.title) || 'EduVault') + (n && n.body ? ' — ' + n.body : '')); });
      safe(function () { if (typeof annCheck === 'function') annCheck(); });
    });
    PN.addListener('pushNotificationActionPerformed', function () {
      safe(function () { if (typeof annCheck === 'function') annCheck(); });
    });
  });
  function getPushToken() {
    return new Promise(function (ok, ko) {
      tokWaiters.push({ ok: ok, ko: ko });
      PN.register().catch(ko);
      setTimeout(function () { ko(new Error('Délai dépassé : pas de jeton FCM (vérifie la connexion)')); }, 20000);
    });
  }
  safe(function () {
    window.pushEnable = function () {
      if (!PN) return Promise.resolve(toast('Notifications indisponibles', 'er'));
      return firebaseConfigured().then(function (okFb) {
        if (!okFb) return toast(Lg('Push non configuré dans cet APK : ajoute google-services.json (voir README §2 ter).', 'Push not configured in this APK: add google-services.json (see README §2 ter).', 'الإشعارات غير مهيأة في هذا التطبيق (راجع README)'), 'er');
        return needFb().then(function (ready) {
          if (!ready) return;
          return PN.checkPermissions().then(function (p) { return p.receive === 'granted' ? p : PN.requestPermissions(); }).then(function (p) {
            if (p.receive !== 'granted') return toast(Lg('Permission refusée', 'Permission denied', 'تم رفض الإذن'), 'er');
            return PN.createChannel({ id: 'eduvault', name: 'Rappels EduVault', description: 'Annonces, cours et échéances', importance: 4, visibility: 1 }).catch(function () {})
              .then(function () { return getPushToken(); })
              .then(function (t) { return saveToken(t); })
              .then(function () { LS('eduVault_push', '1'); toast(Lg('Annonces activées ✔', 'Announcements enabled ✔', 'تم تفعيل الإعلانات ✔'), 'ok'); pushDlg(); });
          });
        });
      }).catch(function (e) { toast(typeof shErr === 'function' ? shErr(e) : String(e && e.message || e), 'er'); });
    };
    /* le WebView n'a pas l'API Notification : on remplace la boîte de dialogue (l'état vient de notre réglage) */
    window.pushDlg = function () {
      var on = LS('eduVault_push') === '1';
      dlgShow('<h3>🔔 ' + Lg('Annonces', 'Announcements', 'الإعلانات') + '</h3><p class="mu">' +
        Lg("Reçois les annonces importantes (examen reporté, salle changée…) même quand l'appli est fermée.", 'Receive important announcements (exam postponed, room changed…) even when the app is closed.', 'استقبل الإعلانات المهمة حتى عندما يكون التطبيق مغلقا.') +
        '</p><small class="mu">' + Lg('État', 'Status', 'الحالة') + ' : ' + (on ? '✅ ' + Lg('activées', 'enabled', 'مفعّلة') : '⚪ ' + Lg('désactivées', 'disabled', 'معطلة')) + '</small>' +
        '<div class="row"><button class="b p" data-a="pushOn">' + Lg('Activer', 'Enable', 'تفعيل') + '</button><button class="b" data-a="pushOff">' + Lg('Désactiver', 'Disable', 'تعطيل') + '</button></div>' + closeBtn());
    };
  });
  /* au démarrage : si les annonces sont activées, on rafraîchit le jeton FCM (il peut changer) */
  safe(function () {
    if (!PN || LSget('eduVault_push') !== '1') return;
    var tries = 0, iv = setInterval(function () {
      if (++tries > 40) return clearInterval(iv);
      if (!fbReady()) return;
      clearInterval(iv);
      firebaseConfigured().then(function (okFb) { if (okFb) PN.register().catch(function () {}); });
    }, 3000);
  });

  /* ---------- « Partager vers EduVault » : fichiers/texte reçus d'autres applis ---------- */
  function readFileBlob(it) {
    return fetch(C.convertFileSrc('file://' + it.path)).then(function (r) {
      if (!r.ok) throw new Error('lecture ' + r.status);
      return r.blob();
    }).catch(function () {
      var FS = P('Filesystem');
      if (!FS) throw new Error('lecture impossible');
      return FS.readFile({ path: 'file://' + it.path }).then(function (r) {
        var bin = atob(r.data), u = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
        return new Blob([u], { type: it.type || 'application/octet-stream' });
      });
    });
  }
  /* shareDlg() est dans une fonction fermée du site : prepare-web.mjs l'expose (window.__shareDlg).
     Sinon (site modifié) : on recharge la page une fois, le site propose alors lui-même l'import au démarrage. */
  function showShare() {
    safe(function () {
      var f = window.__shareDlg || (typeof shareDlg === 'function' ? shareDlg : null);
      if (f) return f();
      if (sessionStorage.getItem('eduVault_shareReload') !== '1') { sessionStorage.setItem('eduVault_shareReload', '1'); location.reload(); }
    });
  }
  var draining = false;
  function drainShare() {
    var SH = P('EduShare');
    if (!SH || !window.caches || draining) return;
    draining = true;
    SH.getPending().then(function (r) {
      var items = (r && r.items) || [];
      if (!items.length) return;
      return caches.open('eduvault-share').then(function (c) {
        var t = Date.now(), n = 0, paths = [];
        return items.reduce(function (p, it) {
          return p.then(function () {
            if (it.path) {
              return readFileBlob(it).then(function (b) {
                paths.push(it.path);
                return c.put(new Request('shared/' + t + '-' + (n++)), new Response(b, { headers: { 'Content-Type': it.type || b.type || 'application/octet-stream', 'X-Name': encodeURIComponent(it.name || 'fichier') } }));
              });
            }
            return c.put(new Request('shared/' + t + '-' + (n++) + '-t'), new Response(JSON.stringify({ title: it.title || '', text: it.text || '', url: '' }), { headers: { 'Content-Type': 'application/json', 'X-Kind': 'text' } }));
          });
        }, Promise.resolve()).then(function () {
          if (paths.length) SH.clear({ paths: paths }).catch(function () {});
          setTimeout(function () { showShare(); }, 800);
        });
      });
    }).catch(function (e) { safe(function () { toast('Partage impossible : ' + (e && e.message || e), 'er'); }); })
      .then(function () { draining = false; });
  }
  safe(function () {
    var SH = P('EduShare');
    if (SH && SH.addListener) SH.addListener('shared', function () { setTimeout(drainShare, 300); });
    var App = P('App');
    if (App && App.addListener) App.addListener('appStateChange', function (s) { if (s && s.isActive) setTimeout(drainShare, 500); });
    setTimeout(drainShare, 1500);
  });

  var tm = null;
  function sched() { clearTimeout(tm); tm = setTimeout(function () { pushWidget(); scheduleNotifs(); }, 2500); }

  /* le site appelle save() à chaque modification : on enveloppe */
  safe(function () {
    if (typeof window.save === 'function') {
      var orig = window.save;
      window.save = function () { var r = orig.apply(this, arguments); sched(); return r; };
    }
  });
  document.addEventListener('visibilitychange', function () { if (document.hidden) { pushWidget(); } else { sched(); } });
  setInterval(sched, 5 * 60000);
  setTimeout(sched, 4000);
})();
