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

  var P = function (n) { return (C && C.Plugins && C.Plugins[n]) || null; };
  var safe = function (f) { try { return f(); } catch (e) { try { console.warn('[native]', e); } catch (_) {} } };
  var LSget = function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } };
  var LSset = function (k, v) { try { localStorage.setItem(k, v); } catch (e) {} };

  /* ---------- styles : pas de bouton Drive/Google (non disponibles dans l'app, voir README) ---------- */
  safe(function () {
    var st = document.createElement('style');
    st.textContent = 'html.native #dv{display:none!important}';
    document.head.appendChild(st);
  });
  safe(function () {
    window.drvAuth = function () {
      return Promise.reject(new Error("Google Drive n'est pas encore disponible dans l'application Android"));
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
        return { s: c.startTime, e: c.endTime, n: modName(c.moduleId), r: c.room || '' };
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
