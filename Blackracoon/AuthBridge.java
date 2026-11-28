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
