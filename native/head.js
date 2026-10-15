/* EduVault Android : exécuté AVANT le code du site (injecté dans <head>).
   - désactive le service worker (les fichiers sont déjà dans l'APK ; évite un cache périmé)
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
})();
