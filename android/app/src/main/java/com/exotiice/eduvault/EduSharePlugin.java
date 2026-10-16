package com.exotiice.eduvault;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * « Partager vers EduVault » : reçoit les ACTION_SEND / SEND_MULTIPLE, copie les fichiers dans le cache de l'appli
 * puis prévient le site (événement « shared »). Le site les range ensuite dans le cache « eduvault-share ».
 * getPending() -> { items:[{path,name,type} | {text,title}] } ; clear({paths}) supprime les copies.
 */
@CapacitorPlugin(name = "EduShare")
public class EduSharePlugin extends Plugin {

    private static final JSONArray PENDING = new JSONArray();
    private static EduSharePlugin inst;

    @Override
    public void load() { inst = this; }

    /** Appelé par MainActivity (onCreate / onNewIntent). */
    static void handle(final Context ctx, final Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        final Intent copy = new Intent(intent);
        intent.setAction(Intent.ACTION_MAIN); // ne pas retraiter le même partage
        new Thread(() -> {
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                if (Intent.ACTION_SEND.equals(action)) {
                    Uri u = streamOf(copy);
                    if (u != null) uris.add(u);
                } else {
                    ArrayList<Uri> l = streamsOf(copy);
                    if (l != null) uris.addAll(l);
                }
                long t = System.currentTimeMillis();
                int n = 0;
                for (Uri u : uris) {
                    JSONObject o = copyToCache(ctx, u, t + "-" + (n++));
                    if (o != null) synchronized (PENDING) { PENDING.put(o); }
                }
                String text = copy.getStringExtra(Intent.EXTRA_TEXT);
                String title = copy.getStringExtra(Intent.EXTRA_SUBJECT);
                if (text != null && text.length() > 0 && uris.isEmpty()) {
                    JSONObject o = new JSONObject();
                    o.put("text", text);
                    o.put("title", title == null ? "" : title);
                    synchronized (PENDING) { PENDING.put(o); }
                }
                EduSharePlugin p = inst;
                if (p != null) p.notifyListeners("shared", new JSObject(), true);
            } catch (Exception ignored) { }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private static Uri streamOf(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        return i.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> streamsOf(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        return i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    }

    private static JSONObject copyToCache(Context ctx, Uri uri, String prefix) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String name = "fichier";
            try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && n.length() > 0) name = n;
                }
            } catch (Exception ignored) { }
            String type = cr.getType(uri);
            File dir = new File(ctx.getCacheDir(), "share");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, prefix + "-" + name.replaceAll("[^\\w.\\- ]+", "_"));
            try (InputStream in = cr.openInputStream(uri); FileOutputStream os = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            }
            JSONObject o = new JSONObject();
            o.put("path", out.getAbsolutePath());
            o.put("name", name);
            o.put("type", type == null ? "application/octet-stream" : type);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    @PluginMethod
    public void getPending(PluginCall call) {
        JSObject res = new JSObject();
        JSArray items = new JSArray();
        synchronized (PENDING) {
            for (int i = 0; i < PENDING.length(); i++) items.put(PENDING.opt(i));
            while (PENDING.length() > 0) PENDING.remove(0);
        }
        res.put("items", items);
        call.resolve(res);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        try {
            JSArray paths = call.getArray("paths");
            File base = new File(getContext().getCacheDir(), "share");
            if (paths != null) {
                for (int i = 0; i < paths.length(); i++) {
                    File f = new File(paths.getString(i));
                    // sécurité : on ne supprime que dans cache/share
                    if (f.getCanonicalPath().startsWith(base.getCanonicalPath())) f.delete();
                }
            }
        } catch (Exception ignored) { }
        call.resolve();
    }
}
