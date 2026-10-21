package com.exotiice.eduvault;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;

import java.util.ArrayList;
import java.util.List;

/**
 * Connexion Google native (Drive, Agenda) : Google bloque OAuth dans un WebView,
 * on passe donc par l'API Authorization de Google Play Services.
 * authorize({scope, silent, hint}) -> { accessToken, expiresIn }. Silencieux si l'accès a déjà été accordé.
 * silent=true : n'ouvre JAMAIS de fenêtre Google ; si une interaction serait nécessaire, rejette « interaction_required »
 * (reconnexion automatique au lancement de l'appli). hint = e-mail du compte Google à utiliser.
 * hasFirebase() -> { value } : vrai si google-services.json était présent au build (push FCM).
 */
@CapacitorPlugin(name = "EduGoogle")
public class EduGooglePlugin extends Plugin {

    static final int REQ = 9417;
    private static PluginCall pending;
    private static EduGooglePlugin inst;

    @PluginMethod
    public void authorize(final PluginCall call) {
        final String scope = call.getString("scope");
        if (scope == null || scope.length() == 0) { call.reject("scope manquant"); return; }
        if (pending != null) { call.reject("Connexion Google déjà en cours"); return; }
        inst = this;
        try {
            List<Scope> scopes = new ArrayList<>();
            for (String s : scope.split(" ")) if (s.length() > 0) scopes.add(new Scope(s));
            final boolean silent = Boolean.TRUE.equals(call.getBoolean("silent", false));
            String hint = call.getString("hint");
            AuthorizationRequest.Builder rb = AuthorizationRequest.builder().setRequestedScopes(scopes);
            if (hint != null && hint.contains("@")) rb.setAccount(new Account(hint, "com.google"));
            AuthorizationRequest req = rb.build();
            Identity.getAuthorizationClient(getActivity()).authorize(req)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        if (silent) { call.reject("interaction_required"); return; }
                        try {
                            PendingIntent pi = result.getPendingIntent();
                            pending = call;
                            getActivity().startIntentSenderForResult(pi.getIntentSender(), REQ, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            pending = null;
                            call.reject("Impossible d'ouvrir la fenêtre Google : " + e.getMessage());
                        }
                    } else {
                        resolve(call, result);
                    }
                })
                .addOnFailureListener(e -> call.reject(describe(e)));
        } catch (Exception e) {
            call.reject(describe(e));
        }
    }

    @PluginMethod
    public void hasFirebase(PluginCall call) {
        int id = getContext().getResources().getIdentifier("google_app_id", "string", getContext().getPackageName());
        JSObject o = new JSObject();
        o.put("value", id != 0);
        call.resolve(o);
    }

    /** Appelé par MainActivity.onActivityResult. */
    static void onResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ) return;
        PluginCall call = pending;
        pending = null;
        if (call == null) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            call.reject("access_denied");
            return;
        }
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data);
            resolve(call, r);
        } catch (ApiException e) {
            call.reject(describe(e));
        } catch (Exception e) {
            call.reject(describe(e));
        }
    }

    private static void resolve(PluginCall call, AuthorizationResult r) {
        String token = r.getAccessToken();
        if (token == null) { call.reject("Google n'a pas renvoyé de jeton"); return; }
        JSObject o = new JSObject();
        o.put("accessToken", token);
        o.put("expiresIn", 3300); // l'API ne donne pas l'expiration : les jetons Google durent ~1 h
        call.resolve(o);
    }

    private static String describe(Exception e) {
        if (e instanceof ApiException) {
            int c = ((ApiException) e).getStatusCode();
            if (c == 10) return "DEVELOPER_ERROR (10) : client OAuth Android absent ou SHA-1 incorrect dans Google Cloud (voir README)";
            if (c == 12501 || c == 16) return "access_denied";
            return "Erreur Google " + c + " : " + e.getMessage();
        }
        return String.valueOf(e.getMessage());
    }
}
