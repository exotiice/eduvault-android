package com.exotiice.eduvault;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * « Mode cours » : début de cours -> silence + carte « lecteur » sur l'écran de verrouillage ;
 * fin de cours -> fin du silence + question « tu as compris combien ? » ; la veille -> rappel d'alarme.
 */
public class ModeReceiver extends BroadcastReceiver {

    static final String PFX = "com.exotiice.eduvault.MODE_";
    static final int CARD_ID = 7001;
    static final int ALARM_ID = 7002;
    static final String CH_CARD = "eduvault_live";
    static final String CH_CHECK = "eduvault_checkin";
    static final String CH_ALARM = "eduvault_alarm";

    private static MediaSession session;

    static SharedPreferences modes(Context c) {
        return c.getSharedPreferences("eduvault_modes", Context.MODE_PRIVATE);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null || !a.startsWith(PFX)) return;
        String t = a.substring(PFX.length());
        try {
            if ("START".equals(t)) onStart(c, i);
            else if ("END".equals(t)) onEnd(c, i);
            else if ("CHECK".equals(t)) onCheck(c, i);
            else if ("RATE".equals(t)) onRate(c, i);
            else if ("TOGGLE".equals(t)) onToggle(c);
            else if ("STOP".equals(t)) onStop(c);
            else if ("PRE".equals(t)) onPre(c);
            else if ("PREV".equals(t)) onNav(c, -1);
            else if ("NEXT".equals(t)) onNav(c, 1);
            else if ("NOTE".equals(t)) onNote(c, i);
            else if ("TICK".equals(t)) postLive(c);
            else if ("SKIP".equals(t)) onSkip(c, i);
            else if ("ABS".equals(t)) onAbs(c, i);
            else if ("LEAVE".equals(t)) onLeave(c, i);
        } catch (Exception ignored) {
            // un rappel ne doit jamais faire planter l'appli
        }
    }

    // ---------------------------------------------------------------- canaux

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel live = new NotificationChannel(CH_CARD, "Cours en cours", NotificationManager.IMPORTANCE_LOW);
        live.setDescription("Carte sur l'écran de verrouillage pendant un cours");
        live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(live);
        NotificationChannel chk = new NotificationChannel(CH_CHECK, "Fin de cours", NotificationManager.IMPORTANCE_DEFAULT);
        chk.setDescription("Question de compréhension après un cours");
        nm.createNotificationChannel(chk);
        NotificationChannel al = new NotificationChannel(CH_ALARM, "Alarme de réveil", NotificationManager.IMPORTANCE_HIGH);
        al.setDescription("Rappel pour régler une alarme avant ton premier cours");
        nm.createNotificationChannel(al);
    }

    private static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, channel);
        return new Notification.Builder(c);
    }

    // ---------------------------------------------------------------- silence

    static void applySilent(Context c) {
        SharedPreferences p = modes(c);
        if (p.getBoolean("silenced", false)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        SharedPreferences.Editor e = p.edit();
        try {
            if (Build.VERSION.SDK_INT >= 23 && nm.isNotificationPolicyAccessGranted()) {
                e.putString("prior_kind", "dnd").putInt("prior_val", nm.getCurrentInterruptionFilter());
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            } else if (am != null) {
                e.putString("prior_kind", "ringer").putInt("prior_val", am.getRingerMode());
                am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            } else {
                return;
            }
            e.putBoolean("silenced", true);
        } catch (SecurityException ex) {
            return;
        } finally {
            e.apply();
        }
    }

    static void restoreSilent(Context c) {
        SharedPreferences p = modes(c);
        if (!p.getBoolean("silenced", false)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        try {
            if ("dnd".equals(p.getString("prior_kind", ""))) {
                int v = p.getInt("prior_val", NotificationManager.INTERRUPTION_FILTER_ALL);
                if (v == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) v = NotificationManager.INTERRUPTION_FILTER_ALL;
                if (Build.VERSION.SDK_INT >= 23 && nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(v);
            } else if (am != null) {
                am.setRingerMode(p.getInt("prior_val", AudioManager.RINGER_MODE_NORMAL));
            }
        } catch (SecurityException ignored) {
        }
        p.edit().putBoolean("silenced", false).apply();
    }

    // ---------------------------------------------------------------- cours

    private static void onStart(Context c, Intent i) {
        if (WidgetData.isLocalSkip(c, i.getStringExtra("id"), i.getStringExtra("day"))) return;
        SharedPreferences p = modes(c);
        channels(c);
        p.edit()
                .putString("active_id", i.getStringExtra("id"))
                .putString("card_name", i.getStringExtra("name"))
                .putString("card_room", i.getStringExtra("room"))
                .putString("card_day", i.getStringExtra("day"))
                .putString("card_color", i.getStringExtra("color"))
                .putLong("card_start", i.getLongExtra("start", 0L))
                .putLong("card_end", i.getLongExtra("end", 0L))
                .putInt("view_i", -1)
                .apply();
        if (p.getBoolean("silent", false)) applySilent(c);
        if (p.getBoolean("card", true)) postCard(c);
    }

    private static void onEnd(Context c, Intent i) {
        if (WidgetData.isLocalSkip(c, i.getStringExtra("id"), i.getStringExtra("day"))) return;
        if (WidgetData.earlyAt(c, i.getStringExtra("id"), i.getStringExtra("day")) > 0L) return; // déjà terminé via « Sorti »
        SharedPreferences p = modes(c);
        channels(c);
        String id = i.getStringExtra("id");
        long s = i.getLongExtra("start", 0L);
        long e = i.getLongExtra("end", 0L);
        if (id != null && id.equals(p.getString("active_id", ""))) {
            p.edit().putInt("view_i", -1).apply();
            if (!ModeScheduler.nextStartWithin(c, e, 10 * 60000L)) {
                restoreSilent(c);
                p.edit().remove("active_id").apply();
            }
            postLive(c); // montre le prochain cours s'il commence dans moins de 30 min, sinon retire la carte
        }
        if (p.getBoolean("checkin", true) && e - s >= 30 * 60000L) {
            postCheckin(c, id, i.getStringExtra("day"), i.getStringExtra("name"));
        }
    }

    private static PendingIntent openNotes(Context c, String id, String day, int req) {
        Intent o = new Intent(c, MainActivity.class);
        o.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        o.setData(Uri.parse("eduvault://open/" + Uri.encode(id == null ? "" : id) + "/" + day + "/" + req));
        o.putExtra("ev_kind", "s");
        o.putExtra("ev_id", id);
        o.putExtra("ev_day", day);
        return PendingIntent.getActivity(c, req, o, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent self(Context c, String type, int req) {
        Intent b = new Intent(c, ModeReceiver.class);
        b.setAction(PFX + type);
        b.setData(Uri.parse("eduvault://mode/self/" + type));
        return PendingIntent.getBroadcast(c, req, b, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String hm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.FRENCH).format(new Date(ms));
    }

    static final long PRE_MS = 30 * 60000L;

    private static final class It {
        String id, name, room, color, day, ty = "", prof = "";
        int ab;
        long s, e;
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Cours d'aujourd'hui (triés par heure de début), lus dans le résumé envoyé par le site. */
    private static java.util.List<It> today(Context c) {
        java.util.List<It> out = new java.util.ArrayList<>();
        try {
            String day = todayKey();
            JSONObject d = WidgetData.load(c);
            JSONObject days = d == null ? null : d.optJSONObject("days");
            JSONArray list = days == null ? null : days.optJSONArray(day);
            if (list == null) return out;
            for (int k = 0; k < list.length(); k++) {
                JSONObject cl = list.getJSONObject(k);
                It it = new It();
                it.day = day;
                it.id = cl.optString("i", day + k);
                if (WidgetData.isLocalSkip(c, it.id, day)) continue; // « Pas cours » noté depuis la carte
                it.ty = cl.optString("ty", "");
                it.prof = cl.optString("p", "");
                it.ab = cl.optInt("ab", 0);
                long le = WidgetData.earlyAt(c, it.id, day);
                it.name = cl.optString("n", "Cours");
                it.room = cl.optString("r", "");
                it.color = cl.optString("c", "");
                it.s = ModeScheduler.timeOf(day, cl.optString("s", "00:00"));
                it.e = ModeScheduler.timeOf(day, cl.optString("e", "00:00"));
                if (le > 0L && le < it.e) it.e = Math.max(it.s, le); // sorti avant l'heure
                if (it.s > 0L && it.e > it.s) out.add(it);
            }
            java.util.Collections.sort(out, new java.util.Comparator<It>() {
                @Override
                public int compare(It x, It y) {
                    return Long.compare(x.s, y.s);
                }
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Index du cours à montrer : choix manuel (‹ ›) s'il est récent, sinon en cours, sinon le prochain. */
    private static int pick(SharedPreferences p, java.util.List<It> l, long now) {
        int cur = -1, nxt = -1;
        for (int k = 0; k < l.size(); k++) {
            It it = l.get(k);
            if (cur < 0 && it.s <= now && now < it.e) cur = k;
            else if (nxt < 0 && it.s > now) nxt = k;
        }
        int view = p.getInt("view_i", -1);
        if (view >= 0 && view < l.size() && now < p.getLong("view_until", 0L)) return view;
        return cur >= 0 ? cur : nxt;
    }

    /** « 45 min » ou « 1 h 15 » (arrondi à la minute supérieure). */
    private static String left(long ms) {
        long m = Math.max(0L, (ms + 59999L) / 60000L);
        if (m < 60) return m + " min";
        return (m / 60) + " h" + (m % 60 > 0 ? " " + String.format(Locale.US, "%02d", m % 60) : "");
    }

    /** Bouton « Note » avec champ de saisie directement dans la notification (réponse rapide). */
    private static Notification.Action noteAction(Context c, It it) {
        Intent r = new Intent(c, ModeReceiver.class);
        r.setAction(PFX + "NOTE");
        r.setData(Uri.parse("eduvault://mode/note/" + Uri.encode(it.id == null ? "" : it.id) + "/" + it.day));
        r.putExtra("id", it.id);
        r.putExtra("day", it.day);
        r.putExtra("name", it.name);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(c, 37, r, flags);
        RemoteInput ri = new RemoteInput.Builder("note").setLabel("Écris ta note…").build();
        return new Notification.Action.Builder(R.drawable.ic_act_note, "Note", pi).addRemoteInput(ri).build();
    }

    /** Note tapée dans la notification : mise de côté, ajoutée aux notes du cours à la prochaine ouverture de l'appli. */
    private static void onNote(Context c, Intent i) throws Exception {
        Bundle in = RemoteInput.getResultsFromIntent(i);
        CharSequence cs = in == null ? null : in.getCharSequence("note");
        String t = cs == null ? "" : cs.toString().trim();
        if (t.length() > 0) {
            SharedPreferences p = modes(c);
            JSONArray arr = new JSONArray(p.getString("rates", "[]"));
            JSONObject o = new JSONObject();
            o.put("i", i.getStringExtra("id"));
            o.put("d", i.getStringExtra("day"));
            o.put("n", i.getStringExtra("name"));
            o.put("t", t);
            arr.put(o);
            p.edit().putString("rates", arr.toString()).apply();
            Toast.makeText(c, "📝 Note enregistrée", Toast.LENGTH_SHORT).show();
        }
        postLive(c); // rafraîchit la carte (arrête l'indicateur de saisie)
    }

    /** PendingIntent d'un bouton lié au cours affiché (id + jour dans l'intent). */
    private static PendingIntent itemPi(Context c, String type, It it, int req) {
        Intent r = new Intent(c, ModeReceiver.class);
        r.setAction(PFX + type);
        r.setData(Uri.parse("eduvault://mode/" + type + "/" + Uri.encode(it.id == null ? "" : it.id) + "/" + it.day));
        r.putExtra("id", it.id);
        r.putExtra("day", it.day);
        r.putExtra("name", it.name);
        return PendingIntent.getBroadcast(c, req, r, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void queue(Context c, Intent i, String kind) throws Exception {
        queue(c, i, kind, 0L);
    }

    private static void queue(Context c, Intent i, String kind, long ts) throws Exception {
        SharedPreferences p = modes(c);
        JSONArray arr = new JSONArray(p.getString("rates", "[]"));
        JSONObject o = new JSONObject();
        o.put("i", i.getStringExtra("id"));
        o.put("d", i.getStringExtra("day"));
        o.put("n", i.getStringExtra("name"));
        o.put("k", kind);
        if (ts > 0L) o.put("e", ts);
        arr.put(o);
        p.edit().putString("rates", arr.toString()).apply();
    }

    /** « Pas cours » : la séance disparaît tout de suite de la carte et des widgets ; l'appli l'enregistre à la prochaine ouverture. */
    private static void onSkip(Context c, Intent i) throws Exception {
        String id = i.getStringExtra("id"), day = i.getStringExtra("day");
        if (id == null) return;
        queue(c, i, "skip");
        WidgetData.addLocalSkip(c, id, day);
        SharedPreferences p = modes(c);
        if (id.equals(p.getString("active_id", ""))) {
            restoreSilent(c);
            p.edit().remove("active_id").putInt("view_i", -1).apply();
        }
        Toast.makeText(c, "🚫 Pas cours : séance retirée pour aujourd'hui", Toast.LENGTH_SHORT).show();
        postLive(c);
        WidgetData.refreshAll(c);
        ModeScheduler.reschedule(c, true);
    }

    /** « Sorti » : le prof nous a libérés avant l'heure. Fin du cours maintenant (silence levé, carte retirée), heure réelle notée. */
    private static void onLeave(Context c, Intent i) throws Exception {
        String id = i.getStringExtra("id"), day = i.getStringExtra("day");
        if (id == null) return;
        long now = System.currentTimeMillis();
        java.util.List<It> l = today(c);
        It cur = null, nxt = null;
        for (It it : l) if (id.equals(it.id)) cur = it;
        if (cur == null || now >= cur.e) return; // déjà terminé
        for (It it : l) if (!id.equals(it.id) && it.s > now && (nxt == null || it.s < nxt.s)) nxt = it;

        WidgetData.setEarly(c, id, day, now);
        queue(c, i, "early", now);
        SharedPreferences p = modes(c);
        if (id.equals(p.getString("active_id", "")) && !ModeScheduler.nextStartWithin(c, now, 10 * 60000L)) {
            restoreSilent(c);
            p.edit().remove("active_id").apply();
        }
        p.edit().putInt("view_i", -1).apply();
        postLive(c);
        if (p.getBoolean("checkin", true) && now - cur.s >= 30 * 60000L) postCheckin(c, id, day, cur.name);

        String msg = nxt != null ? "Libre jusqu'à " + hm(nxt.s) + " (" + left(nxt.s - now) + ")" : "Plus de cours aujourd'hui 🎉";
        Toast.makeText(c, "🚪 Sorti à " + hm(now) + " · " + msg, Toast.LENGTH_LONG).show();
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            channels(c);
            Notification.Builder b = builder(c, CH_CHECK);
            b.setSmallIcon(R.drawable.ic_stat_eduvault);
            b.setContentTitle("🎉 Sorti à " + hm(now) + " · " + left(cur.e - now) + " gagnées");
            b.setContentText(msg);
            b.setAutoCancel(true);
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(nxt != null ? Math.max(60000L, nxt.s - now) : 2 * 3600000L);
            nm.notify(8990, b.build());
        } catch (Exception ignored) {
        }
        WidgetData.refreshAll(c);
    }

    /** « Absent » : compté dans le module à la prochaine ouverture de l'appli. */
    private static void onAbs(Context c, Intent i) throws Exception {
        queue(c, i, "abs");
        Toast.makeText(c, "✋ Absence notée", Toast.LENGTH_SHORT).show();
        postLive(c);
    }

    private static PendingIntent tickPi(Context c) {
        return self(c, "TICK", 38);
    }

    /** Remet à jour la carte chaque minute pendant un cours (pour « reste X min »). */
    private static void scheduleTick(Context c, boolean on) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            if (!on) {
                am.cancel(tickPi(c));
                return;
            }
            long now = System.currentTimeMillis();
            long next = now - (now % 60000L) + 60000L + 500L;
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, tickPi(c));
            else am.set(AlarmManager.RTC_WAKEUP, next, tickPi(c));
        } catch (Exception ignored) {
        }
    }

    static void postCard(Context c) {
        postLive(c);
    }

    /**
     * Barre « live » façon Live Activity / lecteur de musique : le cours en cours (ou le prochain, 30 min avant),
     * compte à rebours en direct, progression, et ‹ › pour parcourir les cours du jour.
     */
    static void postLive(Context c) {
        SharedPreferences p = modes(c);
        if (!p.getBoolean("card", true)) {
            cancelCard(c);
            return;
        }
        java.util.List<It> l = today(c);
        long now = System.currentTimeMillis();
        boolean running = false, soon = false;
        for (It it : l) {
            if (it.s <= now && now < it.e) running = true;
            else if (it.s > now && it.s - now <= PRE_MS) soon = true;
        }
        int idx = pick(p, l, now);
        boolean manual = p.getInt("view_i", -1) >= 0 && now < p.getLong("view_until", 0L);
        if (idx < 0 || !(running || soon || manual)) {
            cancelCard(c);
            return;
        }
        It it = l.get(idx);
        boolean upcoming = now < it.s, live = !upcoming && now < it.e;
        int color;
        try {
            color = Color.parseColor(it.color.length() > 0 ? it.color : "#2A5CC6");
        } catch (Exception ex) {
            color = 0xFF2A5CC6;
        }
        boolean silenced = p.getBoolean("silenced", false);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        channels(c);
        String when = hm(it.s) + " – " + hm(it.e);
        String line = (it.ty.length() > 0 ? it.ty + " · " : "") + (it.room.length() > 0 ? it.room + " · " : "") + when;
        String next = idx + 1 < l.size() ? "Ensuite · " + hm(l.get(idx + 1).s) + " " + l.get(idx + 1).name
                + (l.get(idx + 1).room.length() > 0 ? " · " + l.get(idx + 1).room : "") : "";
        String rest = upcoming ? "⏳ dans " + left(it.s - now) : live ? "⏳ reste " + left(it.e - now) : "";
        String text = rest.length() > 0 ? rest + " · " + line : line;
        String state = upcoming ? "Commence bientôt" : live ? "En cours" : "Terminé";
        String pos = (idx + 1) + "/" + l.size();

        Notification.Builder b = builder(c, CH_CARD);
        b.setSmallIcon(R.drawable.ic_stat_eduvault);
        b.setContentTitle(it.name);
        b.setContentText(text);
        b.setSubText(state + " · " + pos + (it.prof.length() > 0 ? " · 👤 " + it.prof : "") + (it.ab > 0 ? " · ✋ " + it.ab + " abs." : ""));
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setColor(color);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        b.setCategory(Notification.CATEGORY_PROGRESS);
        if (!(upcoming || live)) {
            b.setShowWhen(false);
        } else {
            // compte à rebours en direct (jusqu'au début, puis jusqu'à la fin)
            b.setShowWhen(true);
            b.setWhen(upcoming ? it.s : it.e);
            b.setUsesChronometer(true);
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        }
        b.setContentIntent(openNotes(c, it.id, it.day, 31));
        b.addAction(noteAction(c, it));
        b.addAction(silenced ? R.drawable.ic_act_bell : R.drawable.ic_act_mute, silenced ? "Sonnerie" : "Silence", self(c, "TOGGLE", 33));
        b.addAction(R.drawable.ic_act_stop, "Pas cours", itemPi(c, "SKIP", it, 39));
        b.addAction(R.drawable.ic_act_prev, "Absent", itemPi(c, "ABS", it, 40));
        b.addAction(R.drawable.ic_act_next, "Sorti", itemPi(c, "LEAVE", it, 41));

        try {
            if (session != null) {
                try {
                    session.release();
                } catch (Exception ignored) {
                }
            }
            session = new MediaSession(c, "EduVaultCours");
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, next.length() > 0 ? next : "EduVault · " + state + " · " + pos)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(1000L, it.e - it.s))
                    .build());
            long done = Math.max(0L, Math.min(it.e - it.s, now - it.s));
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(0L)
                    .setState(live ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, upcoming ? 0L : done, live ? 1.0f : 0f)
                    .build());
            session.setActive(true);
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        } catch (Exception ex) {
            // sans session média : notification classique avec les mêmes boutons
        }
        nm.notify(CARD_ID, b.build());
        scheduleTick(c, upcoming || live);
    }

    private static void onNav(Context c, int dir) {
        SharedPreferences p = modes(c);
        java.util.List<It> l = today(c);
        if (l.isEmpty()) return;
        long now = System.currentTimeMillis();
        int cur = pick(p, l, now);
        if (cur < 0) cur = l.size() - 1;
        int n = (cur + dir + l.size()) % l.size();
        p.edit().putInt("view_i", n).putLong("view_until", now + 10 * 60000L).apply();
        postLive(c);
    }

    private static void onPre(Context c) {
        modes(c).edit().putInt("view_i", -1).apply();
        channels(c);
        postLive(c);
    }

    static void cancelCard(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(CARD_ID);
        scheduleTick(c, false);
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
    }

    private static void onToggle(Context c) {
        SharedPreferences p = modes(c);
        if (p.getBoolean("silenced", false)) restoreSilent(c);
        else applySilent(c);
        postCard(c);
    }

    private static void onStop(Context c) {
        restoreSilent(c);
        cancelCard(c);
        modes(c).edit().remove("active_id").apply();
    }

    // ---------------------------------------------------------------- compréhension

    private static void postCheckin(Context c, String id, String day, String name) {
        if (id == null) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        int nid = 8000 + (Math.abs(id.hashCode() + (day == null ? 0 : day.hashCode())) % 900);
        Notification.Builder b = builder(c, CH_CHECK);
        b.setSmallIcon(R.drawable.ic_stat_eduvault);
        b.setContentTitle("🎓 Cours terminé : " + name);
        b.setContentText("Tu as compris combien ? (ça s'ajoute à tes notes du cours)");
        b.setAutoCancel(true);
        b.setContentIntent(openNotes(c, id, day, nid));
        int[] pct = {30, 60, 100};
        String[] label = {"😕 30 %", "🙂 60 %", "😃 100 %"};
        for (int k = 0; k < 3; k++) {
            Intent r = new Intent(c, ModeReceiver.class);
            r.setAction(PFX + "RATE");
            r.setData(Uri.parse("eduvault://mode/rate/" + Uri.encode(id) + "/" + day + "/" + pct[k]));
            r.putExtra("id", id);
            r.putExtra("day", day);
            r.putExtra("name", name);
            r.putExtra("pct", pct[k]);
            r.putExtra("nid", nid);
            b.addAction(R.drawable.ic_act_note, label[k],
                    PendingIntent.getBroadcast(c, nid * 10 + k, r, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        nm.notify(nid, b.build());
    }

    private static void onRate(Context c, Intent i) {
        SharedPreferences p = modes(c);
        try {
            JSONArray arr = new JSONArray(p.getString("rates", "[]"));
            JSONObject o = new JSONObject();
            o.put("i", i.getStringExtra("id"));
            o.put("d", i.getStringExtra("day"));
            o.put("p", i.getIntExtra("pct", 0));
            o.put("n", i.getStringExtra("name"));
            arr.put(o);
            p.edit().putString("rates", arr.toString()).apply();
        } catch (Exception ignored) {
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(i.getIntExtra("nid", 0));
        Toast.makeText(c, "📊 " + i.getIntExtra("pct", 0) + " % enregistré", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- alarme de réveil

    private static void onCheck(Context c, Intent i) throws Exception {
        SharedPreferences p = modes(c);
        if (!p.getBoolean("alarm", true)) return;
        String target = i.getStringExtra("target");
        JSONObject d = WidgetData.load(c);
        JSONObject days = d == null ? null : d.optJSONObject("days");
        JSONArray list = days == null || target == null ? null : days.optJSONArray(target);
        if (list == null || list.length() == 0) return;
        long first = Long.MAX_VALUE;
        String firstName = "";
        String firstTime = "";
        for (int k = 0; k < list.length(); k++) {
            JSONObject cl = list.getJSONObject(k);
            long s = ModeScheduler.timeOf(target, cl.optString("s", "00:00"));
            if (s > 0L && s < first) {
                first = s;
                firstName = cl.optString("n");
                firstTime = cl.optString("s");
            }
        }
        if (first == Long.MAX_VALUE) return;
        Calendar fc = Calendar.getInstance();
        fc.setTimeInMillis(first);
        if (fc.get(Calendar.HOUR_OF_DAY) >= 12) return; // seulement les cours du matin
        long now = System.currentTimeMillis();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo next = am == null ? null : am.getNextAlarmClock();
        if (next != null) {
            long nt = next.getTriggerTime();
            if (nt > now && nt <= first - 10 * 60000L) return; // une alarme existe déjà avant le cours
        }
        int lead = p.getInt("lead", 75);
        Calendar w = Calendar.getInstance();
        w.setTimeInMillis(first - lead * 60000L);
        int hh = w.get(Calendar.HOUR_OF_DAY), mm = w.get(Calendar.MINUTE);
        String when = String.format(Locale.US, "%02d:%02d", hh, mm);

        Intent set = new Intent(AlarmClock.ACTION_SET_ALARM);
        set.putExtra(AlarmClock.EXTRA_HOUR, hh);
        set.putExtra(AlarmClock.EXTRA_MINUTES, mm);
        set.putExtra(AlarmClock.EXTRA_MESSAGE, "EduVault · " + firstName);
        set.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        set.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        channels(c);
        int cards = d.optInt("cards", 0);
        Notification.Builder b = builder(c, CH_ALARM);
        b.setSmallIcon(R.drawable.ic_stat_eduvault);
        b.setContentTitle("⏰ Pas d'alarme pour demain ?");
        b.setContentText("1er cours à " + firstTime + " (" + firstName + "). Aucune alarme avant.");
        b.setStyle(new Notification.BigTextStyle().bigText("1er cours demain à " + firstTime + " (" + firstName + "). "
                + "Aucune alarme n'est réglée avant." + (cards > 0 ? "\n🧠 " + cards + " fiche(s) à réviser ce soir." : "")));
        b.setAutoCancel(true);
        b.setContentIntent(WidgetData.openApp(c));
        b.addAction(R.drawable.ic_act_bell, "⏰ Régler " + when,
                PendingIntent.getActivity(c, 7100, set, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ALARM_ID, b.build());
    }
}
