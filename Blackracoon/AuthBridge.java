package com.blackracoon.estimator;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
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
    public static final int RC_CONTACT = 9002;
    private static final int RC_PERMS = 9003;
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
        if (requestCode == RC_CONTACT) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                final Uri contactUri = data.getData();
                new Thread(new Runnable() {
                    @Override public void run() { readContact(contactUri); }
                }).start();
            }
            return true;
        }
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

    /* ═══════ DEVICE FEATURES (contacts, calendar, notifications, location) ═══════ */

    private void notifyDevice(String json) {
        webView.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.__onDeviceEvent&&window.__onDeviceEvent(" + JSONObject.quote(json) + ")", null);
            }
        });
    }

    private void deviceError(String where, Exception e) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "deviceError");
            o.put("where", where);
            o.put("message", e == null ? "Error" : (e.getMessage() == null ? e.toString() : e.getMessage()));
            notifyDevice(o.toString());
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void pickContact() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
                    activity.startActivityForResult(i, RC_CONTACT);
                } catch (Exception e) { deviceError("contact", e); }
            }
        });
    }

    private void readContact(Uri uri) {
        Cursor c = null;
        try {
            JSONObject o = new JSONObject();
            o.put("event", "contact");
            String id = null;
            c = activity.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                id = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID));
                o.put("name", c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)));
            }
            if (c != null) { c.close(); c = null; }
            if (id != null) {
                c = activity.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[]{id}, null);
                if (c != null && c.moveToFirst()) o.put("phone", c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)));
                if (c != null) { c.close(); c = null; }
                c = activity.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?", new String[]{id}, null);
                if (c != null && c.moveToFirst()) o.put("email", c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)));
                if (c != null) { c.close(); c = null; }
                c = activity.getContentResolver().query(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID + " = ?", new String[]{id}, null);
                if (c != null && c.moveToFirst()) o.put("address", c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)));
                if (c != null) { c.close(); c = null; }
            }
            notifyDevice(o.toString());
        } catch (Exception e) {
            deviceError("contact", e);
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public void addToCalendar(final String title, final double beginMs, final double endMs, final String description, final String location) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, title == null ? "" : title)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, (long) beginMs)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, (long) endMs)
                            .putExtra(CalendarContract.Events.DESCRIPTION, description == null ? "" : description)
                            .putExtra(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
                    activity.startActivity(i);
                } catch (Exception e) { deviceError("calendar", e); }
            }
        });
    }

    @JavascriptInterface
    public void requestNotifications() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 33
                            && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_PERMS);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void scheduleReminders(final String json) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AlarmManager am = (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
                    SharedPreferences prefs = activity.getSharedPreferences("br_reminders", Context.MODE_PRIVATE);
                    String old = prefs.getString("ids", "");
                    if (old != null && !old.isEmpty()) {
                        for (String s : old.split(",")) {
                            try {
                                int oldId = Integer.parseInt(s.trim());
                                PendingIntent pi = PendingIntent.getBroadcast(activity, oldId,
                                        new Intent(activity, NotifyReceiver.class),
                                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                                if (pi != null) { am.cancel(pi); pi.cancel(); }
                            } catch (Exception ignored) {}
                        }
                    }
                    org.json.JSONArray arr = new org.json.JSONArray(json == null ? "[]" : json);
                    StringBuilder ids = new StringBuilder();
                    long now = System.currentTimeMillis();
                    int count = 0;
                    for (int i = 0; i < arr.length() && count < 24; i++) {
                        JSONObject o = arr.getJSONObject(i);
                        long at = o.optLong("at", 0);
                        if (at <= now) continue;
                        int id = o.optInt("id", 1000 + i);
                        Intent intent = new Intent(activity, NotifyReceiver.class);
                        intent.putExtra("title", o.optString("title", "Blackracoon"));
                        intent.putExtra("body", o.optString("body", ""));
                        PendingIntent pi = PendingIntent.getBroadcast(activity, id, intent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); }
                        catch (Exception e) { try { am.set(AlarmManager.RTC_WAKEUP, at, pi); } catch (Exception ignored) {} }
                        if (ids.length() > 0) ids.append(",");
                        ids.append(id);
                        count++;
                    }
                    prefs.edit().putString("ids", ids.toString()).apply();
                } catch (Exception e) { deviceError("reminders", e); }
            }
        }).start();
    }

    private void emitLocation(Location l) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "location");
            o.put("lat", l.getLatitude());
            o.put("lng", l.getLongitude());
            o.put("accuracy", l.getAccuracy());
            notifyDevice(o.toString());
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void getLocation() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                            && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(activity,
                                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, RC_PERMS);
                        JSONObject o = new JSONObject();
                        o.put("event", "locationError");
                        o.put("message", "permission");
                        notifyDevice(o.toString());
                        return;
                    }
                    final LocationManager lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
                    Location best = null;
                    for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                        try {
                            Location l = lm.getLastKnownLocation(p);
                            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                        } catch (Exception ignored) {}
                    }
                    final boolean[] emitted = {false};
                    if (best != null) { emitted[0] = true; emitLocation(best); }
                    final LocationListener listener = new LocationListener() {
                        @Override public void onLocationChanged(Location l) {
                            if (!emitted[0]) { emitted[0] = true; emitLocation(l); }
                        }
                        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                        @Override public void onProviderEnabled(String provider) {}
                        @Override public void onProviderDisabled(String provider) {}
                    };
                    boolean any = false;
                    for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                        try { lm.requestSingleUpdate(p, listener, Looper.getMainLooper()); any = true; } catch (Exception ignored) {}
                    }
                    if (!any && !emitted[0]) {
                        JSONObject o = new JSONObject();
                        o.put("event", "locationError");
                        o.put("message", "unavailable");
                        notifyDevice(o.toString());
                    } else if (!emitted[0]) {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                if (!emitted[0]) {
                                    emitted[0] = true;
                                    try {
                                        JSONObject o = new JSONObject();
                                        o.put("event", "locationError");
                                        o.put("message", "unavailable");
                                        notifyDevice(o.toString());
                                    } catch (Exception ignored) {}
                                }
                            }
                        }, 12000);
                    }
                } catch (Exception e) { deviceError("location", e); }
            }
        });
    }

    @JavascriptInterface
    public void openMaps(final String destination) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    String dest = destination == null ? "" : destination;
                    Uri uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest));
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) { deviceError("maps", e); }
            }
        });
    }

    public static class NotifyReceiver extends BroadcastReceiver {
        public static final String CHANNEL_ID = "br_reminders";
        @Override public void onReceive(Context context, Intent intent) {
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Recordatorios / Reminders", NotificationManager.IMPORTANCE_HIGH);
                    nm.createNotificationChannel(ch);
                }
                String title = intent.getStringExtra("title");
                String body = intent.getStringExtra("body");
                Intent open = new Intent(context, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(context, (int) (System.currentTimeMillis() % 100000), open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title == null ? "Blackracoon" : title)
                        .setContentText(body == null ? "" : body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body == null ? "" : body))
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);
                nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
            } catch (Exception ignored) {}
        }
    }
}
