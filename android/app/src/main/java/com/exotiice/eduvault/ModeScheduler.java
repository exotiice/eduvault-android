package com.exotiice.eduvault;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

/**
 * Programme les alarmes du « Mode cours » à partir du résumé envoyé par le site :
 * START (début du cours : silence + carte lecteur), END (fin : fin du silence + question de compréhension),
 * CHECK (la veille au soir : vérifie qu'une alarme de réveil existe avant le premier cours).
 */
final class ModeScheduler {

    private ModeScheduler() {}

    static final class Ev {
        String type, id = "", day = "", target = "", name = "", room = "", color = "";
        long at, start, end;

        String key() {
            return type + "|" + id + "|" + day + "|" + target;
        }
    }

    static long timeOf(String day, String hhmm) {
        try {
            String[] d = day.split("-");
            String[] t = hhmm.split(":");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1, Integer.parseInt(d[2]),
                    Integer.parseInt(t[0].trim()), Integer.parseInt(t[1].trim()), 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    static PendingIntent pending(Context c, Ev e, int flags) {
        Intent i = new Intent(c, ModeReceiver.class);
        i.setAction(ModeReceiver.PFX + e.type);
        i.setData(Uri.parse("eduvault://mode/" + e.type + "/" + Uri.encode(e.id) + "/" + e.day + "/" + e.target));
        i.putExtra("id", e.id);
        i.putExtra("day", e.day);
        i.putExtra("target", e.target);
        i.putExtra("name", e.name);
        i.putExtra("room", e.room);
        i.putExtra("color", e.color);
        i.putExtra("start", e.start);
        i.putExtra("end", e.end);
        return PendingIntent.getBroadcast(c, 0, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAt(Context c, long at, PendingIntent pi) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException ex) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Vrai si un autre cours commence dans les `withinMs` ms après `afterMs`. */
    static boolean nextStartWithin(Context c, long afterMs, long withinMs) {
        try {
            JSONObject d = WidgetData.load(c);
            JSONObject days = d == null ? null : d.optJSONObject("days");
            if (days == null) return false;
            Iterator<String> it = days.keys();
            while (it.hasNext()) {
                String day = it.next();
                JSONArray list = days.optJSONArray(day);
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    long s = timeOf(day, list.getJSONObject(i).optString("s", "00:00"));
                    if (s > afterMs - 1000L && s <= afterMs + withinMs) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static synchronized void reschedule(Context c) {
        reschedule(c, false);
    }

    static synchronized void reschedule(Context c, boolean force) {
        try {
            SharedPreferences p = ModeReceiver.modes(c);
            JSONObject d = WidgetData.load(c);
            JSONObject days = d == null ? null : d.optJSONObject("days");
            long now = System.currentTimeMillis();
            List<Ev> evs = new ArrayList<>();
            boolean doClass = p.getBoolean("silent", false) || p.getBoolean("card", true) || p.getBoolean("checkin", true);
            boolean doAlarm = p.getBoolean("alarm", true);
            int hour = p.getInt("hour", 21);
            if (days != null) {
                Iterator<String> it = days.keys();
                while (it.hasNext()) {
                    String day = it.next();
                    JSONArray list = days.optJSONArray(day);
                    if (list == null) continue;
                    if (doClass) {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject cl = list.getJSONObject(i);
                            long s = timeOf(day, cl.optString("s", "00:00"));
                            long e = timeOf(day, cl.optString("e", "00:00"));
                            if (s == 0L || e <= now) continue;
                            String id = cl.optString("i", day + i);
                            Ev st = new Ev();
                            st.type = "START"; st.id = id; st.day = day; st.start = s; st.end = e;
                            st.name = cl.optString("n"); st.room = cl.optString("r", ""); st.color = cl.optString("c", "");
                            st.at = Math.max(s, now + 3000L);
                            evs.add(st);
                            if (p.getBoolean("card", true) && s - ModeReceiver.PRE_MS > now) {
                                Ev pre = new Ev();
                                pre.type = "PRE"; pre.id = id; pre.day = day; pre.start = s; pre.end = e;
                                pre.name = st.name; pre.room = st.room; pre.color = st.color;
                                pre.at = s - ModeReceiver.PRE_MS;
                                evs.add(pre);
                            }
                            Ev en = new Ev();
                            en.type = "END"; en.id = id; en.day = day; en.start = s; en.end = e;
                            en.name = st.name; en.room = st.room; en.color = st.color;
                            en.at = e;
                            evs.add(en);
                        }
                    }
                    if (doAlarm && list.length() > 0) {
                        // la veille de ce jour, à l'heure choisie
                        Calendar dc = Calendar.getInstance();
                        dc.setTimeInMillis(timeOf(day, String.format(java.util.Locale.US, "%02d:00", hour)));
                        dc.add(Calendar.DAY_OF_MONTH, -1);
                        if (dc.getTimeInMillis() > now) {
                            Ev ck = new Ev();
                            ck.type = "CHECK"; ck.target = day; ck.day = day;
                            ck.at = dc.getTimeInMillis();
                            evs.add(ck);
                        }
                    }
                }
            }

            StringBuilder sig = new StringBuilder();
            for (Ev e : evs) sig.append(e.key()).append('@').append(e.type.equals("START") ? e.start : e.at).append(';');
            sig.append("#").append(p.getInt("lead", 75)).append(hour);
            if (!force && sig.toString().equals(p.getString("sig", ""))) return;

            // annule l'ancien programme
            JSONArray old = new JSONArray(p.getString("sched", "[]"));
            for (int i = 0; i < old.length(); i++) {
                String[] k = old.getString(i).split("\\|", -1);
                if (k.length < 4) continue;
                Ev e = new Ev();
                e.type = k[0]; e.id = k[1]; e.day = k[2]; e.target = k[3];
                PendingIntent pi = pending(c, e, PendingIntent.FLAG_NO_CREATE);
                if (pi != null) {
                    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                    if (am != null) am.cancel(pi);
                    pi.cancel();
                }
            }
            JSONArray keys = new JSONArray();
            for (Ev e : evs) {
                setAt(c, e.at, pending(c, e, PendingIntent.FLAG_UPDATE_CURRENT));
                keys.put(e.key());
            }
            p.edit().putString("sched", keys.toString()).putString("sig", sig.toString()).apply();
        } catch (Exception ignored) {
            // la programmation ne doit jamais faire planter l'appli
        }
    }
}
