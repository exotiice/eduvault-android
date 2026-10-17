package com.exotiice.eduvault;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Widget « Calendrier » (grille du mois, style Google Agenda) : numéros des jours, pastilles colorées
 * (cours, échéances, vacances), flèches pour changer de mois. Données : clé « cal » du résumé envoyé par le site.
 */
public class MonthWidget extends AppWidgetProvider {

    static final String ACT_PREV = "com.exotiice.eduvault.MONTH_PREV";
    static final String ACT_NEXT = "com.exotiice.eduvault.MONTH_NEXT";
    static final String ACT_TODAY = "com.exotiice.eduvault.MONTH_TODAY";
    private static final int MIN_OFF = -1;
    private static final int MAX_OFF = 2;
    private static final int GRAY = 0xFF5B6075;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle opts) {
        update(context, manager, id);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        android.content.SharedPreferences.Editor e = context.getSharedPreferences(WidgetData.PREFS, Context.MODE_PRIVATE).edit();
        for (int id : ids) e.remove("moff_" + id);
        e.apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        int off = offset(context, id);
        if (ACT_PREV.equals(a)) off = Math.max(MIN_OFF, off - 1);
        else if (ACT_NEXT.equals(a)) off = Math.min(MAX_OFF, off + 1);
        else if (ACT_TODAY.equals(a)) off = 0;
        else return;
        context.getSharedPreferences(WidgetData.PREFS, Context.MODE_PRIVATE).edit().putInt("moff_" + id, off).apply();
        update(context, AppWidgetManager.getInstance(context), id);
    }

    private static int offset(Context c, int id) {
        return c.getSharedPreferences(WidgetData.PREFS, Context.MODE_PRIVATE).getInt("moff_" + id, 0);
    }

    private static void paint(RemoteViews v, int id, int color) {
        if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color));
        else v.setInt(id, "setBackgroundColor", color);
    }

    private static PendingIntent broadcast(Context c, int widgetId, String action) {
        Intent i = new Intent(c, MonthWidget.class);
        i.setAction(action);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        i.setData(Uri.parse("eduvault://month/" + widgetId + "/" + action));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent open(Context c, String kind, String id, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("ev_kind", kind);
        i.putExtra("ev_id", id);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void update(Context c, AppWidgetManager m, int widgetId) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_month);
        Calendar now = Calendar.getInstance();
        String todayKey = WidgetData.isoDay(now);
        int off = Math.max(MIN_OFF, Math.min(MAX_OFF, offset(c, widgetId)));

        Calendar first = Calendar.getInstance();
        first.set(Calendar.DAY_OF_MONTH, 1);
        first.add(Calendar.MONTH, off);
        String title = WidgetData.capitalize(new SimpleDateFormat("MMMM", Locale.FRENCH).format(first.getTime()));
        if (first.get(Calendar.YEAR) != now.get(Calendar.YEAR)) title += " " + first.get(Calendar.YEAR);
        v.setTextViewText(R.id.mt, title);

        JSONObject cal = null;
        try {
            JSONObject d = WidgetData.load(c);
            if (d != null) cal = d.optJSONObject("cal");
        } catch (Exception ignored) {
        }

        int text = ContextCompat.getColor(c, R.color.ag_text);
        int muted = ContextCompat.getColor(c, R.color.ag_muted);
        Calendar g = (Calendar) first.clone();
        int dow = (g.get(Calendar.DAY_OF_WEEK) + 5) % 7; // lundi = 0
        g.add(Calendar.DAY_OF_MONTH, -dow);
        String pkg = c.getPackageName();
        String[] slot = {"a", "b", "c"};

        for (int r = 0; r < 6; r++) {
            for (int col = 0; col < 7; col++) {
                String k = "m" + r + col;
                String key = WidgetData.isoDay(g);
                boolean inMonth = g.get(Calendar.MONTH) == first.get(Calendar.MONTH);
                int nId = c.getResources().getIdentifier(k + "n", "id", pkg);
                if (nId != 0) {
                    v.setTextViewText(nId, String.valueOf(g.get(Calendar.DAY_OF_MONTH)));
                    if (key.equals(todayKey)) {
                        v.setInt(nId, "setBackgroundResource", R.drawable.ag_circle);
                        v.setTextColor(nId, 0xFFFFFFFF);
                    } else {
                        v.setInt(nId, "setBackgroundResource", 0);
                        v.setTextColor(nId, inMonth ? text : muted);
                    }
                }
                JSONArray list = cal == null ? null : cal.optJSONArray(key);
                int n = list == null ? 0 : list.length();
                for (int s = 0; s < 3; s++) {
                    int cId = c.getResources().getIdentifier(k + slot[s], "id", pkg);
                    if (cId == 0) continue;
                    if (s < n) {
                        String label;
                        int color;
                        if (n > 3 && s == 2) {
                            label = "+" + (n - 2);
                            color = GRAY;
                        } else {
                            JSONArray e = list.optJSONArray(s);
                            label = e == null ? "" : e.optString(0, "");
                            color = e == null ? GRAY : AgendaWidget.color(e.optString(1, "#6366F1"));
                        }
                        v.setViewVisibility(cId, View.VISIBLE);
                        v.setTextViewText(cId, label);
                        paint(v, cId, color);
                    } else {
                        v.setViewVisibility(cId, View.GONE);
                    }
                }
                g.add(Calendar.DAY_OF_MONTH, 1);
            }
        }

        v.setOnClickPendingIntent(R.id.prev, broadcast(c, widgetId, ACT_PREV));
        v.setOnClickPendingIntent(R.id.next, broadcast(c, widgetId, ACT_NEXT));
        v.setOnClickPendingIntent(R.id.mt, broadcast(c, widgetId, ACT_TODAY));
        v.setOnClickPendingIntent(R.id.plus, open(c, "add", "", 22));
        v.setOnClickPendingIntent(R.id.root, open(c, "go", "ag", 23));
        m.updateAppWidget(widgetId, v);
    }
}
