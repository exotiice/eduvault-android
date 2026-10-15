# EduVault pour Android (application native)

Application Android **native** (APK) pour le site EduVault, construite avec **Capacitor** :
elle embarque le code du site (donc **toutes** les fonctions, y compris le chiffrement AES-GCM) et y ajoute
du code natif : **widgets sur l'écran d'accueil**, **rappels natifs** (même appli fermée), bouton retour Android,
export de fichiers via le menu Partager d'Android.

Les données sont les **mêmes que sur le site** (Firebase `eduvault-22224`) : tu te connectes avec le même compte.

L'APK est construit par **GitHub Actions** (rien à installer sur ton PC).

---

## 1. Mise en place (une seule fois)

1. Crée un dépôt GitHub **`eduvault-android`** (privé ou public) et mets-y **tout le contenu de ce dossier**
   (y compris le dossier caché `.github/`). Le plus simple :
   ```bash
   cd eduvault-android
   git init && git add -A && git commit -m "EduVault Android"
   git branch -M main
   git remote add origin https://github.com/exotiice/eduvault-android.git
   git push -u origin main
   ```
2. Va dans l'onglet **Actions** du dépôt : le workflow **Build EduVault APK** démarre tout seul (≈ 6–10 min).
   Sinon : Actions > Build EduVault APK > **Run workflow**.
3. Quand il est vert : onglet **Releases** (à droite sur la page du dépôt) > **EduVault APK #N** > télécharge
   **`EduVault.apk`** depuis ton téléphone et installe-le (autorise « sources inconnues » pour ton navigateur).

> Chaque build s'installe **par-dessus** le précédent (même clé de signature `android/app/debug.keystore`,
> numéro de version = numéro du build). Tes données ne sont pas perdues.
> Cette clé est publique dans le dépôt : c'est OK pour une appli que tu installes toi-même, mais ne l'utilise pas pour Google Play.

## 2. À FAIRE dans Firebase / Google Cloud (sinon la connexion échoue)

Dans l'appli, le site tourne sur l'adresse **`https://localhost`** (c'est normal pour Capacitor).

- **Google Cloud Console > APIs & Services > Credentials > « Browser key » > Application restrictions : Websites** :
  si tu as limité la clé à `https://exotiice.github.io/*` (LISEZ-MOI du site §3 bis), ajoute aussi **`https://localhost/*`**.
  Sans ça : erreurs `API key not valid` / `requests from referer … are blocked`. (Si la clé n'est pas restreinte, rien à faire.)
- **Firebase console > Authentication > Settings > Authorized domains** : `localhost` y est normalement déjà. Vérifie.
- Si un jour tu actives **App Check (reCAPTCHA v3)** : il ne marchera pas dans l'appli tel quel
  (il faudra le fournisseur Play Integrity). Laisse `APPCHECK_KEY` vide tant que l'appli est utilisée.

## 3. Widgets

Appui long sur l'écran d'accueil > **Widgets** > **EduVault** :

| Widget | Contenu |
|---|---|
| **Aujourd'hui** | les cours restants de la journée (● = en cours), salle |
| **Échéances** | prochains examens 🚨 / devoirs 📝 (« Dans 2 j »), et le nombre de fiches à réviser |

Comment ça marche : à chaque modification, le site envoie un petit résumé (8 jours de cours, 8 échéances) au widget.
Le widget calcule l'heure lui-même, donc il reste juste **même sans ouvrir l'appli pendant plusieurs jours**
(il se redessine toutes les 30 min et quand tu quittes l'appli). Un appui ouvre EduVault.
Les semaines A/B et les vacances sont respectées.

## 4. Rappels

À la première ouverture, Android demande l'autorisation d'envoyer des notifications. Ensuite :
- cours : rappel X minutes avant (réglage « Rappel avant les cours » du site, 10 min par défaut, 0 = désactivé) ;
- échéances : 1 h avant, et la veille à 18 h.
Les 7 prochains jours sont programmés à l'avance (donc ça marche appli fermée) et recalculés à chaque modification.

## 5. Différences avec le site

| Fonction | Dans l'appli |
|---|---|
| Comptes, synchro Firebase, chiffrement, fusion, historique | ✅ identique |
| Notes, fiches, quiz, focus, stats, kanban, ENIAD (suivi du site) | ✅ identique |
| Export `.ics` / sauvegarde `.json` / fichiers | ✅ via le menu **Partager** d'Android |
| Liens externes | ✅ ouverts dans le navigateur |
| Photos de notes (appareil photo) | ✅ |
| **Google Drive** (copie des fichiers) et **Google Agenda** (synchro) | ❌ pas encore : Google bloque la connexion OAuth dans un WebView. Il faudra une connexion Google native (étape suivante). Le bouton ☁️ est masqué. |
| Notifications push d'annonces (FCM) | ❌ pas encore (nécessite `google-services.json`) |
| Dictée vocale 🎤 | ❌ non supportée par le WebView Android (la lecture vocale 🔊/🎧 marche) |
| « Partager vers EduVault » | ❌ pas encore |

⚠️ **Fichiers (PDF…) « sur cet appareil »** : ils sont stockés dans l'appli. **Désinstaller l'appli les efface**
(les données Firebase, elles, sont gardées). Sans Drive dans l'appli, garde une copie ailleurs de tes fichiers importants.

## 6. Mettre à jour

- **Le site a changé** (nouveau `index.html`) : l'appli embarque le code du site **au moment du build**.
  Relance **Actions > Build EduVault APK > Run workflow** pour obtenir un nouvel APK. Les *données* Firebase,
  elles, sont toujours en direct.
- Le workflow récupère le site sur `exotiice/eduvault` (branche `main`) ; modifiable dans « Run workflow ».
- Le site n'est **jamais modifié** : toute la couche native est dans `native/` et injectée au build
  (`scripts/prepare-web.mjs`). Si une fonction du site change de nom, la couche native se désactive proprement
  (tout est protégé par `try/catch`).

## 7. Développement local (facultatif)

```bash
npm ci
node scripts/prepare-web.mjs ../eduvault     # dossier du site
npx cap sync android
# puis ouvrir android/ dans Android Studio
```

## 8. Structure

```
native/head.js      avant le site : désactive le service worker, flux ENIAD en ligne
native/native.js    après le site : widgets, rappels, retour, export, liens, barre d'état
scripts/prepare-web.mjs   copie le site dans www/ + injecte native/*.js
android/…/EduWidgetPlugin.java   plugin Capacitor (site → widgets)
android/…/TodayWidget.java, TasksWidget.java, WidgetData.java   les widgets
.github/workflows/android.yml    build de l'APK
```

Note technique : le code natif est en **Java** (pas Kotlin) pour garder une configuration Gradle minimale, plus sûre
à construire sans pouvoir la tester localement. Le comportement est identique.
