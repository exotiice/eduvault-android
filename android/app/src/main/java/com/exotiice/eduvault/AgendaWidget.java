package com.exotiice.eduvault;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Widget « Agenda » (style Google Agenda) : cours restants aujourd'hui puis les jours suivants, en pastilles colorées. */
public class AgendaWidget extends AppWidgetProvider {

    private static final int[] ROW = {R.id.a1, R.id.a2, R.id.a3, R.id.a4, R.id.a5, R.id.a6};
    private static final int[] DT = {R.id.a1dt, R.id.a2dt, R.id.a3dt, R.id.a4dt, R.id.a5dt, R.id.a6dt};
    private static final int[] DO = {R.id.a1do, R.id.a2do, R.id.a3do, R.id.a4do, R.id.a5do, R.id.a6do};
    private static final int[] BG = {R.id.a1bg, R.id.a2bg, R.id.a3bg, R.id.a4bg, R.id.a5bg, R.id.a6bg};
    private static final int[] TITLE = {R.id.a1t, R.id.a2t, R.id.a3t, R.id.a4t, R.id.a5t, R.id.a6t};
    private static final int[] SUB = {R.id.a1s, R.id.a2s, R.id.a3s, R.id.a4s, R.id.a5s, R.id.a6s};
    private static final int GRAY = 0xFF5B6075;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle opts) {
        update(context, manager, id);
    }

    private static int color(String s) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return 0xFF6366F1;
        }
    }

    static void update(Context c, AppWidgetManager m, int widgetId) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_agenda);
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        v.setTextViewText(R.id.mh, WidgetData.capitalize(new SimpleDateFormat("MMMM", Locale.FRENCH).format(now.getTime())));
        for (int i = 0; i < ROW.length; i++) v.setViewVisibility(ROW[i], View.GONE);
        v.setViewVisibility(R.id.empty, View.GONE);

        String empty = null;
        try {
            JSONObject d = WidgetData.load(c);
            JSONObject days = d == null ? null : d.optJSONObject("days");
            if (days == null) {
                empty = "Ouvre EduVault une fois pour afficher ton agenda.";
            } else {
                int row = 0;
                for (int off = 0; off < 8 && row < ROW.length; off++) {
                    Calendar day = (Calendar) now.clone();
                    day.add(Calendar.DAY_OF_MONTH, off);
                    JSONArray list = days.optJSONArray(WidgetData.isoDay(day));
                    boolean first = true;
                    int added = 0;
                    if (list != null) {
                        for (int i = 0; i < list.length() && row < ROW.length; i++) {
                            JSONObject cl = list.getJSONObject(i);
                            int start = WidgetData.minutes(cl.optString("s", "00:00"));
                            int end = WidgetData.minutes(cl.optString("e", "00:00"));
                            if (off == 0 && end <= nowMin) continue;
                            boolean live = off == 0 && start <= nowMin;
                            String room = cl.optString("r", "");
                            String sub = (live ? "● " : "") + cl.optString("s") + " – " + cl.optString("e")
                                    + (room.length() > 0 ? " · " + room : "");
                            put(v, row++, day, off == 0, first, cl.optString("n"), sub, color(cl.optString("c", "#6366F1")));
                            first = false;
                            added++;
                        }
                    }
                    if (off == 0 && added == 0 && row < ROW.length) {
                        put(v, row++, day, true, true, "Rien d'autre aujourd'hui", "", GRAY);
                    }
                }
                if (row == 0) empty = "Aucun cours dans les prochains jours";
            }
        } catch (Exception e) {
            empty = "Ouvre EduVault pour actualiser.";
        }
        if (empty != null) {
            v.setViewVisibility(R.id.empty, View.VISIBLE);
            v.setTextViewText(R.id.empty, empty);
        }
        v.setOnClickPendingIntent(R.id.root, WidgetData.openApp(c));
        v.setOnClickPendingIntent(R.id.plus, WidgetData.openApp(c));
        m.updateAppWidget(widgetId, v);
    }

    private static void put(RemoteViews v, int r, Calendar day, boolean today, boolean first,
                            String title, String sub, int color) {
        v.setViewVisibility(ROW[r], View.VISIBLE);
        v.setViewVisibility(DT[r], View.GONE);
        v.setViewVisibility(DO[r], View.GONE);
        if (first) {
            String dow = WidgetData.capitalize(new SimpleDateFormat("EEE", Locale.FRENCH).format(day.getTime()).replace(".", ""));
            int dom = day.get(Calendar.DAY_OF_MONTH);
            if (today) {
                v.setViewVisibility(DT[r], View.VISIBLE);
                v.setTextViewText(DT[r], dow.substring(0, 1) + "\n" + dom);
            } else {
                v.setViewVisibility(DO[r], View.VISIBLE);
                v.setTextViewText(DO[r], dow + "\n" + dom);
            }
        }
        v.setInt(BG[r], "setColorFilter", color);
        v.setTextViewText(TITLE[r], title);
        v.setTextViewText(SUB[r], sub);
        v.setViewVisibility(SUB[r], sub.length() > 0 ? View.VISIBLE : View.GONE);
    }
}
