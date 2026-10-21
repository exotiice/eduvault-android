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
      if (c.skip && c.skip.indexOf(k) >= 0) return false;   // séance non assurée ce jour-là
      return !hol && (c.week === 'all' || c.week === wk);
    }).sort(function (a, b) { return a.startTime.localeCompare(b.startTime); });
  }
  /* calendrier du mois (widget Calendrier) : du 1er du mois précédent à la fin du mois +2 ; [libellé court, couleur] par jour */
  function buildCal(ids) {
    var out = {}, now = new Date();
    var d = new Date(now.getFullYear(), now.getMonth() - 1, 1), end = new Date(now.getFullYear(), now.getMonth() + 3, 0);
    var color = {}; A.modules.forEach(function (m) { color[m.id] = m.color || '#6366F1'; });
    var tasks = A.tasks.filter(function (t) { return !t.completed && t.type !== 'review'; });
    for (; d <= end; d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1)) {
      var k = iso(d), list = [];
      if (isHol(k)) list.push(['Vacances', '#0d9488']);
      sessionsFor(d, ids).forEach(function (c) { list.push([String(modName(c.moduleId)).slice(0, 14), color[c.moduleId] || '#6366F1']); });
      tasks.forEach(function (t) { if (iso(new Date(t.dueDate)) === k) list.push([(t.type === 'exam' ? '🚨' : '📝') + String(t.title).slice(0, 12), t.type === 'exam' ? '#dc2626' : '#d97706']); });
      if (list.length) out[k] = list;
    }
    return out;
  }
  function buildSummary() {
    var ids = curIds(), now = new Date(), days = {}, hol = {}, abs = {};
    for (var i = 0; i < 8; i++) {
      var d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i), k = iso(d);
      if (isHol(k)) hol[k] = 1;
      days[k] = sessionsFor(d, ids).map(function (c) {
        var mo = A.modules.find(function (m) { return m.id === c.moduleId; });
        var le = c.early && c.early[k];   // « Sorti tôt » : l'heure réelle de fin remplace l'heure prévue
        var endT = le && le > c.startTime && le < c.endTime ? le : c.endTime;
        return { i: c.id, s: c.startTime, e: endT, n: modName(c.moduleId), r: c.room || '', c: (mo && mo.color) || '', ty: String(c.typ || '').toUpperCase(), p: c.prof || '', ab: abs[c.moduleId] === undefined ? (abs[c.moduleId] = absCount(c.moduleId)) : abs[c.moduleId] };
      });
    }
    var tasks = A.tasks.filter(function (t) {
      return !t.completed && t.type !== 'review' && new Date(t.dueDate).getTime() > Date.now() - 864e5;
    }).sort(function (a, b) { return new Date(a.dueDate) - new Date(b.dueDate); }).slice(0, 8).map(function (t) {
      return { i: t.id, t: new Date(t.dueDate).getTime(), n: t.title, m: modName(t.moduleId), x: t.type === 'exam' ? 1 : 0 };
    });
    var cards = A.flashcards.filter(function (f) { return ids.has(f.moduleId) && (!f.nextRev || f.nextRev <= Date.now()); }).length;
    return { u: Date.now(), days: days, hol: hol, tasks: tasks, cards: cards, cal: buildCal(ids) };
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

  /* ---------- appui sur le widget Agenda : fiche de la séance / de l'échéance ---------- */
  function relTxt(ms) {
    var m = Math.max(1, Math.round(ms / 60000));
    if (m < 60) return m + ' min';
    var h = Math.floor(m / 60);
    if (h < 24) return h + ' h' + (m % 60 ? ' ' + (m % 60 < 10 ? '0' : '') + (m % 60) : '');
    return Math.round(h / 24) + ' j';
  }
  function dayLong(iso) {
    try { return new Date(iso + 'T00:00').toLocaleDateString(LOC(), { weekday: 'long', day: 'numeric', month: 'long' }); } catch (e) { return iso; }
  }
  function infoClose() { try { $('dlg').close(); } catch (e) {} }
  function showSession(a) {
    var c = A.timetable.find(function (x) { return x.id === a.i; });
    if (!c) { go('ag'); return; }
    /* on ouvre directement la fiche de la séance du site (notes + photos), puis on y ajoute l'état et les infos du module */
    act.cls(c.id);
    safe(function () {
      var d = $('dlg'), nt = $('nt'), sm = d.querySelector('small.mu');
      if (!d.open || !nt || !sm) return;
      var m = A.modules.find(function (x) { return x.id === c.moduleId; }) || {};
      var st = new Date(a.d + 'T' + c.startTime), en = new Date(a.d + 'T' + c.endTime), now = Date.now();
      var state = now < st ? '⏳ ' + Lg('Commence dans ', 'Starts in ', 'تبدأ بعد ') + relTxt(st - now)
        : now < en ? '● ' + Lg('En cours · reste ', 'In progress · ', 'جارية · ') + relTxt(en - now)
        : '✔ ' + Lg('Terminée', 'Finished', 'انتهت');
      var tasks = A.tasks.filter(function (t) { return t.moduleId === c.moduleId && !t.completed && t.type !== 'review'; })
        .sort(function (x, y) { return new Date(x.dueDate) - new Date(y.dueDate); }).slice(0, 3);
      var cards = A.flashcards.filter(function (f) { return f.moduleId === c.moduleId && (!f.nextRev || f.nextRev <= Date.now()); }).length;
      var h = '<div class="rs" style="display:block;margin:8px 0"><b>' + state + '</b> <small class="mu">· ' + esc(dayLong(a.d)) + '</small>' +
        (m.prof ? '<div>👤 ' + esc(m.prof) + '</div>' : '') +
        tasks.map(function (t) { return '<div>' + (t.type === 'exam' ? '🚨 ' : '📝 ') + esc(t.title) + ' <small class="mu">· ' + Lg('dans ', 'in ', 'بعد ') + relTxt(new Date(t.dueDate) - now) + '</small></div>'; }).join('') +
        (cards ? '<div>🧠 ' + cards + ' ' + Lg('fiche(s) à réviser', 'card(s) to review', 'بطاقة للمراجعة') + '</div>' : '') + '</div>';
      sm.insertAdjacentHTML('afterend', h);
      nt.rows = 7;
      if (!nt.value) setTimeout(function () { try { nt.focus(); } catch (e) {} }, 250);
    });
  }
  function showTask(a) {
    var t = A.tasks.find(function (x) { return x.id === a.i; });
    if (!t) { go('da'); return; }
    var due = new Date(t.dueDate), now = Date.now(), over = due.getTime() < now;
    dlgShow('<h3>' + (t.type === 'exam' ? '🚨 ' : '📝 ') + esc(t.title) + '</h3>' +
      '<div class="rs" style="display:block;margin:8px 0"><b>' + (over ? '⚠️ ' + Lg('En retard de ', 'Overdue by ', 'متأخر بـ ') + relTxt(now - due) : '⏳ ' + Lg('Dans ', 'In ', 'بعد ') + relTxt(due - now)) + '</b>' +
      '<div style="margin-top:6px">📚 ' + esc(modName(t.moduleId)) + '</div>' +
      '<div>🗓 ' + esc(due.toLocaleDateString(LOC(), { weekday: 'long', day: 'numeric', month: 'long' })) + ' · ' + esc(due.toTimeString().slice(0, 5)) + '</div></div>' +
      '<div class="row" style="justify-content:flex-start;flex-wrap:wrap">' +
      '<button class="b p" data-a="evDone" data-i="' + esc(t.id) + '">✔ ' + Lg('Terminé', 'Done', 'تم') + '</button>' +
      '<button class="b" data-a="editTask" data-i="' + esc(t.id) + '">✏️ ' + Lg('Modifier', 'Edit', 'تعديل') + '</button></div>' + closeBtn());
  }
  function showAdd() {
    dlgShow('<h3>➕ ' + Lg('Ajouter', 'Add', 'إضافة') + '</h3><div class="row" style="justify-content:flex-start;flex-wrap:wrap">' +
      '<button class="b p" data-a="evAddCls">🕒 ' + Lg('Séance', 'Class', 'حصة') + '</button>' +
      '<button class="b p" data-a="evAddTask">📝 ' + Lg('Échéance', 'Deadline', 'موعد') + '</button></div>' + closeBtn());
  }
  safe(function () {
    Object.assign(act, {
      evGo: function (i) { infoClose(); go(i); },
      evDone: function (i) { act.done(i); infoClose(); setTimeout(pushWidget, 800); },
      evAddCls: function () { infoClose(); act.addCls(); },
      evAddTask: function () { infoClose(); act.addTask(); }
    });
  });
  var acting = false;
  function drainAction() {
    var W = P('EduWidget');
    if (!W || !W.getAction || acting) return;
    acting = true;
    W.getAction().then(function (r) {
      var a = null; try { a = r && r.action ? JSON.parse(r.action) : null; } catch (e) {}
      if (!a) return;
      if (!A.settings.level) return;   // pas encore configuré
      if (a.k === 's') showSession(a);
      else if (a.k === 't') showTask(a);
      else if (a.k === 'add') showAdd();
      else if (a.k === 'go') go(a.i || 'ag');
    }).catch(function () {}).then(function () { acting = false; });
  }
  safe(function () {
    var W = P('EduWidget');
    if (W && W.addListener) W.addListener('widgetAction', function () { setTimeout(drainAction, 200); });
    var App = P('App');
    if (App && App.addListener) App.addListener('appStateChange', function (s) { if (s && s.isActive) setTimeout(drainAction, 400); });
    setTimeout(drainAction, 1800);
  });

  /* ================= Mode cours ================= */
  var MK = 'eduVault_mode';
  var MDEF = { silent: true, card: true, checkin: true, alarm: true, lead: 90, hour: 21 };
  function modeGet() {
    var o = {}, s = {};
    try { s = JSON.parse(LSget(MK) || '{}') || {}; } catch (e) {}
    for (var k in MDEF) o[k] = (k in s) ? s[k] : MDEF[k];
    return o;
  }
  function modePush() {
    var W = P('EduWidget');
    if (W && W.setPrefs) W.setPrefs({ json: JSON.stringify(modeGet()) }).catch(function () {});
  }
  function modeSave(o) { try { LS(MK, JSON.stringify(o)); } catch (e) {} modePush(); }
  function modeStats() {
    var out = {};
    (A.notes || []).forEach(function (n) {
      var m = String(n.text || '').match(/📊 \d\d\/\d\d · [^\n]*?(\d+) ?%/g);
      if (!m) return;
      var c = A.timetable.find(function (x) { return x.id === n.classId; });
      if (!c) return;
      var o = out[c.moduleId] || (out[c.moduleId] = { s: 0, n: 0 });
      m.forEach(function (l) { o.s += +l.match(/(\d+) ?%$/)[1]; o.n++; });
    });
    return out;
  }
  function modeDlg() {
    var W = P('EduWidget'), m = modeGet();
    var row = function (k, ico, t, d) {
      return '<label style="display:flex;gap:10px;align-items:flex-start;margin:10px 0"><input type="checkbox" data-mk="' + k + '"' + (m[k] ? ' checked' : '') + ' style="width:20px;height:20px;margin-top:2px"><span><b>' + ico + ' ' + t + '</b><br><small class="mu">' + d + '</small></span></label>';
    };
    var st = modeStats(), lines = Object.keys(st).map(function (id) {
      return '<div>' + esc(modName(id)) + ' : <b>' + Math.round(st[id].s / st[id].n) + ' %</b> <small class="mu">(' + st[id].n + ')</small></div>';
    }).join('');
    dlgShow('<h3>🎓 ' + Lg('Mode cours', 'Class mode', 'وضع الحصة') + '</h3>' +
      row('silent', '🔕', Lg('Silence pendant les cours', 'Silent during classes', 'صامت أثناء الحصص'), Lg('Active « Ne pas déranger » au début, le remet à la fin.', 'Turns on Do Not Disturb at start, restores it at the end.', 'يفعّل عدم الإزعاج عند البداية ويعيده عند النهاية.')) +
      '<div id="mdnd"></div>' +
      row('card', '🎵', Lg('Carte du cours (écran verrouillé)', 'Class card (lock screen)', 'بطاقة الحصة (شاشة القفل)'), Lg('Style lecteur de musique : cours, salle, heure.', 'Music-player style: class, room, time.', 'على شكل مشغل موسيقى: المادة والقاعة والوقت.')) +
      row('checkin', '📊', Lg('Bilan après chaque cours', 'Check-in after each class', 'تقييم بعد كل حصة'), Lg('Notification : « Tu as compris combien ? » 30 / 60 / 100 %.', 'Notification: “How much did you understand?” 30 / 60 / 100 %.', 'إشعار: «شحال فهمتي؟» 30 / 60 / 100 %.')) +
      row('alarm', '⏰', Lg('Aide au réveil', 'Wake-up helper', 'مساعد المنبه'), Lg('Le soir, vérifie ton alarme et propose l\'heure selon le 1er cours.', 'In the evening, checks your alarm and suggests a time from the first class.', 'في المساء يتحقق من المنبه ويقترح وقتا حسب أول حصة.')) +
      '<div class="row" style="justify-content:flex-start;gap:8px;align-items:center"><span>' + Lg('Me réveiller', 'Wake me', 'أيقظني') + '</span><input id="mlead" type="number" min="30" max="240" step="5" value="' + m.lead + '" style="width:70px"><span>' + Lg('min avant', 'min before', 'دقيقة قبل') + '</span></div>' +
      '<div class="row" style="justify-content:flex-start;gap:8px;align-items:center"><span>' + Lg('Rappel à', 'Reminder at', 'التذكير على') + '</span><input id="mhour" type="number" min="18" max="23" value="' + m.hour + '" style="width:70px"><span>h</span></div>' +
      (lines ? '<h4 style="margin:14px 0 4px">📈 ' + Lg('Compréhension moyenne', 'Average understanding', 'متوسط الفهم') + '</h4>' + lines : '') +
      closeBtn());
    var d = $('dlg');
    function read() {
      var o = modeGet();
      d.querySelectorAll('[data-mk]').forEach(function (i) { o[i.dataset.mk] = i.checked; });
      var l = parseInt($('mlead').value, 10), h = parseInt($('mhour').value, 10);
      if (l >= 30 && l <= 240) o.lead = l;
      if (h >= 18 && h <= 23) o.hour = h;
      modeSave(o);
    }
    d.querySelectorAll('[data-mk],#mlead,#mhour').forEach(function (i) { i.addEventListener('change', read); });
    if (W && W.getModeInfo) W.getModeInfo().then(function (r) {
      var box = $('mdnd');
      if (!box || r.dnd) return;
      box.innerHTML = '<div class="rs" style="display:block;margin:0 0 8px">⚠️ ' + Lg('Accès « Ne pas déranger » requis pour le silence automatique.', 'Do Not Disturb access is needed for automatic silence.', 'يلزم إذن «عدم الإزعاج» للصمت التلقائي.') +
        '<div><button class="b p" id="mdndb">' + Lg('Autoriser', 'Allow', 'السماح') + '</button></div></div>';
      $('mdndb').onclick = function () { W.openDnd().catch(function () {}); };
    }).catch(function () {});
  }
  /* bilans reçus depuis les notifications → on les écrit dans les notes du cours */
  /* absences d'un module (compteur affiché sur la carte) */
  function absCount(mid) {
    var n = 0;
    (A.timetable || []).forEach(function (c) { if (c.moduleId === mid && c.abs) n += c.abs.length; });
    return n;
  }
  /* prochaine date (> aujourd'hui) d'une séance du même module : pour l'échéance d'un devoir noté depuis la carte */
  function nextDueFor(c) {
    var d0 = new Date(), ids = new Set([c.moduleId]);
    for (var i = 1; i <= 21; i++) {
      var d = new Date(d0.getFullYear(), d0.getMonth(), d0.getDate() + i), l = sessionsFor(d, ids);
      if (l.length) return iso(d) + 'T' + (l[0].startTime || '08:00');
    }
    return iso(new Date(d0.getFullYear(), d0.getMonth(), d0.getDate() + 1)) + 'T18:00';
  }
  function drainRates() {
    var W = P('EduWidget');
    if (!W || !W.getRates) return;
    // données pas encore chargées : on ne vide pas la file native (les actions de la carte attendent le prochain essai)
    if (!A || !A.settings || !A.settings.level || !(A.timetable || []).length) return;
    W.getRates().then(function (r) {
      var list = (r && r.rates) || [];
      if (!list.length) return;
      var nNote = 0, nSkip = 0, nAbs = 0, nTask = 0, nEarly = 0;
      list.forEach(function (x) {
        var c = A.timetable.find(function (t) { return t.id === x.i; });
        if (!c) return;
        var dd = String(x.d || '').split('-'), dm = dd.length === 3 ? dd[2] + '/' + dd[1] : '';
        if (x.k === 'skip') {                       // « Pas cours » depuis la carte
          if (x.d && (c.skip || []).indexOf(x.d) < 0) c.skip = (c.skip || []).concat(x.d);
          nSkip++; return;
        }
        var n = A.notes.find(function (z) { return z.classId === x.i; });
        if (!n) { n = { classId: x.i, text: '', photos: [] }; A.notes.push(n); }
        var line;
        if (x.k === 'abs') {                        // « Absent » depuis la carte
          if (x.d && (c.abs || []).indexOf(x.d) < 0) c.abs = (c.abs || []).concat(x.d);
          line = '✋ ' + dm + ' · ' + Lg('Absent', 'Absent', 'غائب'); nAbs++;
        } else if (x.k === 'early') {               // « Sorti » depuis la carte
          var eh = x.e ? new Date(x.e) : null, em = eh ? ('0' + eh.getHours()).slice(-2) + ':' + ('0' + eh.getMinutes()).slice(-2) : '';
          if (x.d && em) { c.early = c.early || {}; c.early[x.d] = em; }
          line = '🚪 ' + dm + ' · ' + Lg('Sorti à ', 'Left at ', 'خرجت على ') + em + ' (' + Lg('prévu ', 'planned ', 'المقرر ') + c.endTime + ')'; nEarly++;
        } else if (x.t && /^\s*(devoir|dv|hw|!)\s*[:\-]?\s*\S/i.test(x.t)) {   // « devoir … » => échéance
          var title = String(x.t).replace(/^\s*(devoir|dv|hw|!)\s*[:\-]?\s*/i, '').replace(/\s+/g, ' ').trim().slice(0, 120);
          A.tasks.push({ id: uid('t'), moduleId: c.moduleId, type: 'homework', title: title, dueDate: nextDueFor(c), completed: false });
          line = '📝 ' + dm + ' · ' + Lg('Devoir : ', 'Homework: ', 'واجب: ') + title; nTask++;
        } else if (x.t) {
          line = '📝 ' + dm + ' · ' + String(x.t).replace(/\s+/g, ' ').trim(); nNote++;
        } else {
          line = '📊 ' + dm + ' · ' + Lg('Compris', 'Understood', 'فهم') + ' ' + x.p + ' %'; nNote++;
        }
        n.text = (n.text ? n.text + '\n' : '') + line;
        n.date = new Date().toISOString();
      });
      try { upd(); } catch (e) {}
      var msg = [];
      if (nNote) msg.push('📝 ' + nNote);
      if (nTask) msg.push('📌 ' + nTask);
      if (nAbs) msg.push('✋ ' + nAbs);
      if (nEarly) msg.push('🚪 ' + nEarly);
      if (nSkip) msg.push('🚫 ' + nSkip);
      if (msg.length) toast(msg.join(' · ') + ' ✔ ' + Lg('enregistré', 'saved', 'تم الحفظ'), 'ok');
      sched();
    }).catch(function () {});
  }
  safe(function () {
    var W = P('EduWidget'), App = P('App');
    act.modeDlg = modeDlg;
    modePush();
    if (App && App.addListener) App.addListener('appStateChange', function (s) { if (s && s.isActive) setTimeout(drainRates, 600); });
    if (W && W.addListener) W.addListener('widgetAction', function () { setTimeout(drainRates, 400); });
    setTimeout(drainRates, 2500);
    setTimeout(drainRates, 9000);
    /* bouton d'accès dans les réglages du site */
    new MutationObserver(function () {
      var b = document.querySelector('[data-a="resHub"]');
      if (b && !document.querySelector('[data-a="modeDlg"]')) {
        var n = document.createElement('button');
        n.className = 'b'; n.dataset.a = 'modeDlg';
        n.textContent = '🎓 ' + Lg('Mode cours', 'Class mode', 'وضع الحصة');
        b.parentNode.insertBefore(n, b);
      }
    }).observe(document.body, { childList: true, subtree: true });
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
