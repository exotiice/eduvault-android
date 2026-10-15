// Prépare le dossier www/ de l'application à partir du code du site EduVault.
//   node scripts/prepare-web.mjs <dossier-du-site>        (par défaut : ../eduvault ou $SITE_DIR)
// - copie index.html, manifest et icônes
// - injecte native/head.js au début de <head> et native/native.js à la fin de <body>
// Le site n'est jamais modifié : toute la couche native vit dans native/.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const site = path.resolve(process.argv[2] || process.env.SITE_DIR || path.join(root, '..', 'eduvault'));
const www = path.join(root, 'www');

if (!fs.existsSync(path.join(site, 'index.html'))) {
  console.error('index.html introuvable dans ' + site);
  process.exit(1);
}

fs.rmSync(www, { recursive: true, force: true });
fs.mkdirSync(www, { recursive: true });

const copy = [
  'manifest.webmanifest', 'icon.svg', 'icon-192.png', 'icon-512.png', 'icon-maskable-512.png',
  'apple-touch-icon.png', 'icon-shortcut-agenda.png', 'icon-shortcut-cards.png',
  'icon-shortcut-focus.png', 'icon-shortcut-quiz.png',
];
for (const f of copy) {
  const src = path.join(site, f);
  if (fs.existsSync(src)) fs.copyFileSync(src, path.join(www, f));
  else console.warn('absent (ignoré) : ' + f);
}

let html = fs.readFileSync(path.join(site, 'index.html'), 'utf8');
const head = fs.readFileSync(path.join(root, 'native', 'head.js'), 'utf8');
const nat = fs.readFileSync(path.join(root, 'native', 'native.js'), 'utf8');

if (html.includes('data-eduvault-native')) {
  console.error('index.html contient déjà la couche native : utilise le site d\'origine.');
  process.exit(1);
}
const hi = html.search(/<head[^>]*>/i);
const hm = html.match(/<head[^>]*>/i);
if (hi < 0) { console.error('<head> introuvable'); process.exit(1); }
const hEnd = hi + hm[0].length;
html = html.slice(0, hEnd) + '\n<script data-eduvault-native="head">\n' + head + '\n</script>' + html.slice(hEnd);

const bi = html.lastIndexOf('</body>');
if (bi < 0) { console.error('</body> introuvable'); process.exit(1); }
html = html.slice(0, bi) + '<script data-eduvault-native="main">\n' + nat + '\n</script>\n' + html.slice(bi);

fs.writeFileSync(path.join(www, 'index.html'), html);
console.log('www prêt : ' + (html.length / 1024).toFixed(0) + ' Ko (site : ' + site + ')');
