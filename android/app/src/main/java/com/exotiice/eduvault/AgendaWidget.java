package com.exotiice.eduvault;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Widget « Agenda » (style Google Agenda), défilant : cours restants aujourd'hui puis les jours suivants
 * + échéances (examens / devoirs), en pastilles colorées. Un appui sur une pastille ouvre EduVault avec
 * la fiche de la séance / de l'échéance ; « + » propose d'ajouter une séance ou une échéance.
 */
public class AgendaWidget extends AppWidgetProvider {

    static final int GRAY = 0xFF5B6075;
    static final int RED = 0xFFDC2626;
    static final int AMBER = 0xFFD97706;

    /** Une ligne de la liste. */
    static class Entry {
        String kind = "o";   // s = séance, t = échéance, o = autre (rien)
        String id = "";
        String day = "";
        String title = "";
        String sub = "";
        int color = GRAY;
        int sort = 0;
        boolean first = false;
        boolean today = false;
        String dow = "";
        int dom = 0;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle opts) {
        update(context, manager, id);
    }

    static int color(String s) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return 0xFF6366F1;
        }
    }

    /** Construit les lignes à partir du résumé envoyé par le site. Renvoie null si pas de données. */
    static List<Entry> build(Context c) {
        JSONObject d = WidgetData.load(c);
        JSONObject days = d == null ? null : d.optJSONObject("days");
        if (days == null) return null;
        JSONArray tasks = d.optJSONArray("tasks");
        List<Entry> out = new ArrayList<>();
        Calendar now = Calendar.getInstance();
        long nowMs = now.getTimeInMillis();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        SimpleDateFormat dowFmt = new SimpleDateFormat("EEE", Locale.FRENCH);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.FRENCH);
        try {
            for (int off = 0; off < 8; off++) {
                Calendar day = (Calendar) now.clone();
                day.add(Calendar.DAY_OF_MONTH, off);
                String key = WidgetData.isoDay(day);
                List<Entry> list = new ArrayList<>();
                JSONArray ss = days.optJSONArray(key);
                if (ss != null) {
                    for (int i = 0; i < ss.length(); i++) {
                        JSONObject cl = ss.getJSONObject(i);
                        int start = WidgetData.minutes(cl.optString("s", "00:00"));
                        int end = WidgetData.minutes(cl.optString("e", "00:00"));
                        if (off == 0 && end <= nowMin) continue;
                        boolean live = off == 0 && start <= nowMin;
                        String room = cl.optString("r", "");
                        Entry e = new Entry();
                        e.kind = "s";
                        e.id = cl.optString("i", "");
                        e.day = key;
                        e.title = cl.optString("n");
                        e.sub = (live ? "● " : "") + cl.optString("s") + " – " + cl.optString("e")
                                + (room.length() > 0 ? " · " + room : "");
                        e.color = color(cl.optString("c", "#6366F1"));
                        e.sort = start;
                        list.add(e);
                    }
                }
                if (tasks != null) {
                    for (int i = 0; i < tasks.length(); i++) {
                        JSONObject t = tasks.getJSONObject(i);
                        long due = t.optLong("t", 0);
                        if (due <= 0 || (off == 0 && due < nowMs)) continue;
                        Calendar dc = Calendar.getInstance();
                        dc.setTimeInMillis(due);
                        if (!WidgetData.isoDay(dc).equals(key)) continue;
                        boolean exam = t.optInt("x", 0) == 1;
                        String mod = t.optString("m", "");
                        Entry e = new Entry();
                        e.kind = "t";
                        e.id = t.optString("i", "");
                        e.day = key;
                        e.title = (exam ? "🚨 " : "📝 ") + t.optString("n");
                        e.sub = hm.format(dc.getTime()) + (mod.length() > 0 ? " · " + mod : "");
                        e.color = exam ? RED : AMBER;
                        e.sort = dc.get(Calendar.HOUR_OF_DAY) * 60 + dc.get(Calendar.MINUTE);
                        list.add(e);
                    }
                }
                if (list.isEmpty()) {
                    if (off != 0) continue;
                    Entry e = new Entry();
                    e.kind = "o";
                    e.day = key;
                    e.title = "Rien d'autre aujourd'hui";
                    list.add(e);
                }
                Collections.sort(list, new Comparator<Entry>() {
                    @Override
                    public int compare(Entry a, Entry b) {
                        return Integer.compare(a.sort, b.sort);
                    }
                });
                String dow = WidgetData.capitalize(dowFmt.format(day.getTime()).replace(".", ""));
                for (int i = 0; i < list.size(); i++) {
                    Entry e = list.get(i);
                    e.first = i == 0;
                    e.today = off == 0;
                    e.dow = dow;
                    e.dom = day.get(Calendar.DAY_OF_MONTH);
                    out.add(e);
                }
            }
        } catch (Exception ignored) {
            // données abîmées : on affiche ce qu'on a
        }
        return out;
    }

    private static String subtitle(Context c, List<Entry> items) {
        try {
            JSONObject d = WidgetData.load(c);
            if (d == null) return "";
            int cls = 0, due = 0;
            if (items != null) {
                for (Entry e : items) {
                    if (!e.today) continue;
                    if ("s".equals(e.kind)) cls++;
                    else if ("t".equals(e.kind)) due++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(cls == 0 ? "Plus de cours aujourd'hui" : cls + (cls > 1 ? " cours restants" : " cours restant"));
            if (due > 0) sb.append(" · ").append(due).append(due > 1 ? " échéances" : " échéance");
            int cards = d.optInt("cards", 0);
            if (cards > 0) sb.append(" · 🧠 ").append(cards);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static void update(Context c, AppWidgetManager m, int widgetId) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_agenda);
        Calendar now = Calendar.getInstance();
        v.setTextViewText(R.id.mh, WidgetData.capitalize(new SimpleDateFormat("MMMM", Locale.FRENCH).format(now.getTime())));

        List<Entry> items = null;
        try {
            items = build(c);
        } catch (Exception ignored) {
        }
        v.setTextViewText(R.id.sub, subtitle(c, items));
        v.setTextViewText(R.id.empty, items == null
                ? "Ouvre EduVault une fois pour afficher ton agenda."
                : "Aucun cours dans les prochains jours");

        Intent svc = new Intent(c, AgendaService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.list, svc);
        v.setEmptyView(R.id.list, R.id.empty);

        // Modèle de clic des lignes : chaque ligne y ajoute ses infos (ev_kind, ev_id, ev_day)
        Intent tpl = new Intent(c, MainActivity.class);
        tpl.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        v.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, 11, tpl, flags));

        Intent add = new Intent(c, MainActivity.class);
        add.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        add.putExtra("ev_kind", "add");
        v.setOnClickPendingIntent(R.id.plus, PendingIntent.getActivity(c, 12, add,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setOnClickPendingIntent(R.id.mh, WidgetData.openApp(c));

        m.updateAppWidget(widgetId, v);
        m.notifyAppWidgetViewDataChanged(widgetId, R.id.list);
    }
}
