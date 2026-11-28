package com.blackracoon.estimator;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import org.json.JSONObject;
import java.util.concurrent.Executor;

public class AuthBridge {
    public static final int RC_GOOGLE = 9001;
    private final Activity activity;
    private final WebView webView;
    private FirebaseAuth auth;
    private GoogleSignInClient googleClient;

    public AuthBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    private FirebaseAuth auth() {
        if (auth == null) auth = FirebaseAuth.getInstance();
        return auth;
    }

    private GoogleSignInClient googleClient() {
        if (googleClient == null) {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(activity.getString(R.string.default_web_client_id))
                    .requestEmail()
                    .build();
            googleClient = GoogleSignIn.getClient(activity, gso);
        }
        return googleClient;
    }

    private String userJson(String event) {
        try {
            JSONObject o = new JSONObject();
            FirebaseUser u = auth().getCurrentUser();
            o.put("event", event);
            o.put("signedIn", u != null);
            if (u != null) {
                o.put("email", u.getEmail() == null ? "" : u.getEmail());
                o.put("name", u.getDisplayName() == null ? "" : u.getDisplayName());
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"event\":\"" + event + "\",\"signedIn\":false}";
        }
    }

    private void notifyJs(String json) {
        webView.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.__onAuthEvent&&window.__onAuthEvent(" + JSONObject.quote(json) + ")", null);
            }
        });
    }

    private void notifyError(Exception e) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "error");
            o.put("message", e == null ? "Error" : (e.getMessage() == null ? e.toString() : e.getMessage()));
            notifyJs(o.toString());
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String getUser() {
        return userJson("state");
    }

    @JavascriptInterface
    public void signInWithGoogle() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent intent = googleClient().getSignInIntent();
                    activity.startActivityForResult(intent, RC_GOOGLE);
                } catch (Exception e) {
                    notifyError(e);
                }
            }
        });
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != RC_GOOGLE) return false;
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            auth().signInWithCredential(credential).addOnCompleteListener(task2 -> {
                if (task2.isSuccessful()) notifyJs(userJson("signedIn"));
                else notifyError(task2.getException());
            });
        } catch (ApiException e) {
            notifyError(e);
        }
        return true;
    }

    @JavascriptInterface
    public void signInWithEmail(String email, String password) {
        if (email == null || password == null) return;
        auth().signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener(task -> {
            if (task.isSuccessful()) notifyJs(userJson("signedIn"));
            else notifyError(task.getException());
        });
    }

    @JavascriptInterface
    public void createAccount(String email, String password) {
        if (email == null || password == null) return;
        auth().createUserWithEmailAndPassword(email.trim(), password).addOnCompleteListener(task -> {
            if (task.isSuccessful()) notifyJs(userJson("signedIn"));
            else notifyError(task.getException());
        });
    }

    @JavascriptInterface
    public void signOut() {
        auth().signOut();
        try { googleClient().signOut(); } catch (Exception ignored) {}
        notifyJs(userJson("signedOut"));
    }

    private void notifyKv(String event, String k1, Object v1, String k2, Object v2) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", event);
            if (k1 != null) o.put(k1, v1);
            if (k2 != null) o.put(k2, v2);
            notifyJs(o.toString());
        } catch (Exception ignored) {}
    }

    private com.google.firebase.firestore.FirebaseFirestore firestore() {
        return com.google.firebase.firestore.FirebaseFirestore.getInstance();
    }

    @JavascriptInterface
    public void backupToCloud(final String json) {
        try {
            FirebaseUser u = auth().getCurrentUser();
            if (u == null) { notifyKv("backupError", "message", "not-signed-in", null, null); return; }
            java.util.Map<String, Object> doc = new java.util.HashMap<>();
            doc.put("data", json == null ? "{}" : json);
            doc.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
            firestore().collection("users").document(u.getUid()).set(doc).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    notifyKv("backupOk", "updatedAt", System.currentTimeMillis(), null, null);
                } else {
                    Exception e = task.getException();
                    notifyKv("backupError", "message", e == null ? "Error" : (e.getMessage() == null ? e.toString() : e.getMessage()), null, null);
                }
            });
        } catch (Exception e) {
            notifyKv("backupError", "message", e.getMessage() == null ? e.toString() : e.getMessage(), null, null);
        }
    }

    @JavascriptInterface
    public void restoreFromCloud() {
        try {
            FirebaseUser u = auth().getCurrentUser();
            if (u == null) { notifyKv("restoreError", "message", "not-signed-in", null, null); return; }
            firestore().collection("users").document(u.getUid()).get().addOnCompleteListener(task -> {
              try {
                if (task.isSuccessful() && task.getResult() != null) {
                    com.google.firebase.firestore.DocumentSnapshot snap = task.getResult();
                    if (snap.exists()) {
                        String data = snap.getString("data");
                        com.google.firebase.Timestamp ts = snap.getTimestamp("updatedAt");
                        long updated = ts != null ? ts.toDate().getTime() : 0;
                        notifyKv("restoreData", "data", data == null ? "{}" : data, "updatedAt", updated);
                    } else {
                        notifyKv("restoreEmpty", null, null, null, null);
                    }
                } else {
                    Exception e = task.getException();
                    notifyKv("restoreError", "message", e == null ? "Error" : (e.getMessage() == null ? e.toString() : e.getMessage()), null, null);
                }
              } catch (Exception e) {
                notifyKv("restoreError", "message", e.toString(), null, null);
              }
            });
        } catch (Exception e) {
            notifyKv("restoreError", "message", e.getMessage() == null ? e.toString() : e.getMessage(), null, null);
        }
    }

    @JavascriptInterface
    public void biometricUnlock() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    BiometricManager manager = BiometricManager.from(activity);
                    int can = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                    if (can != BiometricManager.BIOMETRIC_SUCCESS) {
                        JSONObject o = new JSONObject();
                        o.put("event", "biometricUnavailable");
                        notifyJs(o.toString());
                        return;
                    }
                    Executor executor = ContextCompat.getMainExecutor(activity);
                    BiometricPrompt prompt = new BiometricPrompt((FragmentActivity) activity, executor,
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                    try {
                                        JSONObject o = new JSONObject();
                                        o.put("event", "biometricOk");
                                        notifyJs(o.toString());
                                    } catch (Exception ignored) {}
                                }
                                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                                    try {
                                        JSONObject o = new JSONObject();
                                        o.put("event", "biometricError");
                                        o.put("message", String.valueOf(errString));
                                        notifyJs(o.toString());
                                    } catch (Exception ignored) {}
                                }
                            });
                    BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Blackracoon Construction Estimator")
                            .setSubtitle("Usa tu huella para entrar / Use your fingerprint to enter")
                            .setNegativeButtonText("Cancelar / Cancel")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                            .build();
                    prompt.authenticate(info);
                } catch (Exception e) {
                    notifyError(e);
                }
            }
        });
    }
}
