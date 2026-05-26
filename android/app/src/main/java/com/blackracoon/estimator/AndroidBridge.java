package com.blackracoon.estimator;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class AndroidBridge {

    static final int REQUEST_BACKUP  = 1001;
    static final int REQUEST_CAMERA  = 1003;

    private Context context;
    private WebView webView;
    private WebView printWebView;
    private Uri pendingPhotoUri;

    public AndroidBridge(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
    }

    private byte[] cleanDecode(String base64Data) {
        if (base64Data.contains(",")) {
            base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
        }
        return Base64.decode(base64Data, Base64.DEFAULT);
    }

    // ── BACKUP FILE PICKER ──────────────────────────────────────────────────

    @JavascriptInterface
    public void pickBackupFile() {
        final Activity activity = (Activity) context;
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.startActivityForResult(intent, REQUEST_BACKUP);
                } catch (Exception e) {
                    mostrarMensaje("Error al abrir selector");
                }
            }
        });
    }

    // ── NATIVE CAMERA ────────────────────────────────────────────────────────

    @JavascriptInterface
    public void takePhoto() {
        final Activity activity = (Activity) context;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME,
                            "receipt_" + System.currentTimeMillis() + ".jpg");
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

                    pendingPhotoUri = context.getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                    if (pendingPhotoUri == null) {
                        mostrarMensaje("Error preparando cámara");
                        return;
                    }

                    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
                    // Request rear camera (non-standard extras, supported by most OEMs)
                    intent.putExtra("android.intent.extras.CAMERA_FACING", 0);
                    intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 0);
                    intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", false);
                    activity.startActivityForResult(intent, REQUEST_CAMERA);
                } catch (Exception e) {
                    mostrarMensaje("Error al abrir cámara");
                }
            }
        });
    }

    // ── ACTIVITY RESULT ──────────────────────────────────────────────────────

    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == REQUEST_BACKUP
                && resultCode == Activity.RESULT_OK
                && data != null) {

            try {
                Uri uri = data.getData();
                if (uri == null) { mostrarMensaje("No se seleccionó archivo"); return; }

                InputStream is = context.getContentResolver().openInputStream(uri);
                if (is == null) { mostrarMensaje("No se pudo leer el archivo"); return; }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) baos.write(buffer, 0, read);
                is.close();

                String base64Json = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                webView.evaluateJavascript("importBackupBase64('" + base64Json + "')", null);

            } catch (Exception e) {
                mostrarMensaje("Error al leer archivo");
            }
        }

        if (requestCode == REQUEST_CAMERA
                && resultCode == Activity.RESULT_OK
                && pendingPhotoUri != null) {

            try {
                InputStream is = context.getContentResolver().openInputStream(pendingPhotoUri);
                if (is == null) { mostrarMensaje("No se pudo leer la foto"); return; }

                // Decode and scale down to ~1200px max dimension
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
                is.close();

                if (bitmap == null) { mostrarMensaje("Error procesando foto"); return; }

                // Scale to max 1200px
                int maxDim = 1200;
                int w = bitmap.getWidth(), h = bitmap.getHeight();
                if (w > maxDim || h > maxDim) {
                    float scale = Math.min((float) maxDim / w, (float) maxDim / h);
                    bitmap = Bitmap.createScaledBitmap(
                            bitmap,
                            Math.round(w * scale),
                            Math.round(h * scale),
                            true
                    );
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                bitmap.recycle();

                String base64 = "data:image/jpeg;base64," +
                        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                final String jsCall = "receiveNativePhoto('" + base64 + "')";
                webView.post(new Runnable() {
                    @Override public void run() {
                        webView.evaluateJavascript(jsCall, null);
                    }
                });

            } catch (Exception e) {
                mostrarMensaje("Error guardando foto");
            } finally {
                pendingPhotoUri = null;
            }
        }
    }

    // ── SAVE TEXT FILE ───────────────────────────────────────────────────────

    @JavascriptInterface
    public void saveTextFile(String content, String filename, String mimeType) {

        try {

            byte[] bytes = content.getBytes("UTF-8");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

                if (uri != null) {
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    os.write(bytes);
                    os.close();
                    mostrarMensaje("✅ Backup guardado");
                } else {
                    mostrarMensaje("❌ No se pudo crear backup");
                }

            } else {

                File downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();

                File file = new File(downloadsDir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                mostrarMensaje("✅ Backup guardado");
            }

        } catch (Exception e) {
            mostrarMensaje("❌ Error guardando backup");
        }
    }

    // ── PRINT / PDF ───────────────────────────────────────────────────────────

    @JavascriptInterface
    public void printHtml(final String htmlContent, final String filename) {

        final Activity activity = (Activity) context;

        activity.runOnUiThread(new Runnable() {

            @Override
            public void run() {

                try {

                    if (printWebView != null) {
                        android.view.ViewParent parent = printWebView.getParent();
                        if (parent instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) parent).removeView(printWebView);
                        }
                        printWebView.destroy();
                        printWebView = null;
                    }

                    printWebView = new WebView(activity);
                    printWebView.getSettings().setJavaScriptEnabled(true);
                    printWebView.getSettings().setLoadWithOverviewMode(true);
                    printWebView.getSettings().setUseWideViewPort(true);

                    activity.addContentView(
                            printWebView,
                            new android.view.ViewGroup.LayoutParams(1, 1)
                    );

                    printWebView.setWebViewClient(new android.webkit.WebViewClient() {
                        @Override
                        public void onPageFinished(final WebView view, String url) {
                            view.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        PrintManager printManager = (PrintManager)
                                                activity.getSystemService(Context.PRINT_SERVICE);
                                        PrintDocumentAdapter adapter =
                                                view.createPrintDocumentAdapter(filename);
                                        PrintAttributes attributes = new PrintAttributes.Builder()
                                                .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                                                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                                .build();
                                        printManager.print(filename, adapter, attributes);
                                    } catch (Exception e) {
                                        mostrarMensaje("Error al imprimir");
                                    }
                                }
                            }, 800);
                        }
                    });

                    printWebView.loadDataWithBaseURL(
                            "file:///android_asset/", htmlContent, "text/html", "UTF-8", null);

                } catch (Exception e) {
                    mostrarMensaje("Error preparando impresión");
                }
            }
        });
    }

    @JavascriptInterface
    public void saveHtmlAsPdf(final String htmlContent, final String filename) {
        printHtml(htmlContent, filename);
    }

    // ── SHARE AS IMAGE ────────────────────────────────────────────────────────

    @JavascriptInterface
    public void shareHtmlAsImage(final String html, final String name, final String channel) {
        final Activity activity = (Activity) context;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                final WebView iv = new WebView(activity);
                iv.getSettings().setJavaScriptEnabled(true);
                activity.addContentView(iv, new ViewGroup.LayoutParams(1, 1));
                iv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                iv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        view.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    int w = 1080;
                                    int h = Math.min(Math.max(view.getContentHeight(), 400), 8000);
                                    view.layout(0, 0, w, h);
                                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                                    Canvas c = new Canvas(bmp);
                                    c.drawColor(Color.WHITE);
                                    view.draw(c);
                                    // Remove hidden WebView
                                    if (iv.getParent() instanceof ViewGroup)
                                        ((ViewGroup) iv.getParent()).removeView(iv);
                                    iv.destroy();
                                    // Save to MediaStore to get a shareable content URI
                                    String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                                    Uri imgUri = null;
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        ContentValues cv = new ContentValues();
                                        cv.put(MediaStore.Images.Media.DISPLAY_NAME, safeName + ".png");
                                        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                                        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BuildRacoon");
                                        imgUri = context.getContentResolver().insert(
                                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                                        if (imgUri != null) {
                                            OutputStream os = context.getContentResolver().openOutputStream(imgUri);
                                            bmp.compress(Bitmap.CompressFormat.PNG, 90, os);
                                            os.close();
                                        }
                                    } else {
                                        String uriStr = MediaStore.Images.Media.insertImage(
                                                context.getContentResolver(), bmp, safeName, name);
                                        if (uriStr != null) imgUri = Uri.parse(uriStr);
                                    }
                                    bmp.recycle();
                                    if (imgUri == null) { mostrarMensaje("Error guardando imagen"); return; }
                                    Intent intent = new Intent(Intent.ACTION_SEND);
                                    intent.setType("image/png");
                                    intent.putExtra(Intent.EXTRA_STREAM, imgUri);
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    if ("whatsapp".equals(channel)) intent.setPackage("com.whatsapp");
                                    try {
                                        activity.startActivity(intent);
                                    } catch (Exception e) {
                                        activity.startActivity(Intent.createChooser(intent, "Compartir"));
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    mostrarMensaje("Error al generar imagen");
                                }
                            }
                        }, 800);
                    }
                });
            }
        });
    }

    // ── SHARE AS PDF ──────────────────────────────────────────────────────────

    @JavascriptInterface
    public void shareHtmlAsPdf(final String html, final String name, final String channel) {
        final Activity activity = (Activity) context;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                final WebView pv = new WebView(activity);
                pv.getSettings().setJavaScriptEnabled(true);
                activity.addContentView(pv, new ViewGroup.LayoutParams(1, 1));
                pv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                pv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        view.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    final PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(name);
                                    final PrintAttributes attrs = new PrintAttributes.Builder()
                                            .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                                            .setResolution(new PrintAttributes.Resolution("pdf","PDF",300,300))
                                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                            .build();
                                    String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                                    final File pdfFile = new File(activity.getCacheDir(), safeName + ".pdf");
                                    final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfFile,
                                            ParcelFileDescriptor.MODE_READ_WRITE |
                                            ParcelFileDescriptor.MODE_CREATE |
                                            ParcelFileDescriptor.MODE_TRUNCATE);
                                    adapter.onStart();
                                    adapter.onLayout(null, attrs, new CancellationSignal(),
                                        new PrintDocumentAdapter.LayoutResultCallback() {
                                            @Override public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                                                try {
                                                    adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd,
                                                        new CancellationSignal(),
                                                        new PrintDocumentAdapter.WriteResultCallback() {
                                                            @Override public void onWriteFinished(PageRange[] pages) {
                                                                try { pfd.close(); } catch (Exception ignored) {}
                                                                adapter.onFinish();
                                                                if (pv.getParent() instanceof ViewGroup)
                                                                    ((ViewGroup) pv.getParent()).removeView(pv);
                                                                pv.destroy();
                                                                // Share PDF via RsFileProvider
                                                                Uri uri = Uri.parse("content://com.blackracoon.estimator.rsprovider"
                                                                        + pdfFile.getAbsolutePath());
                                                                Intent intent = new Intent(Intent.ACTION_SEND);
                                                                intent.setType("application/pdf");
                                                                intent.putExtra(Intent.EXTRA_STREAM, uri);
                                                                intent.putExtra(Intent.EXTRA_SUBJECT, name);
                                                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                                                if ("whatsapp".equals(channel)) intent.setPackage("com.whatsapp");
                                                                try {
                                                                    activity.startActivity(intent);
                                                                } catch (Exception e) {
                                                                    activity.startActivity(Intent.createChooser(intent, "Compartir PDF"));
                                                                }
                                                            }
                                                        });
                                                } catch (Exception e) {
                                                    try { pfd.close(); } catch (Exception ignored) {}
                                                    mostrarMensaje("Error generando PDF");
                                                }
                                            }
                                        }, null);
                                } catch (Exception e) {
                                    mostrarMensaje("Error al preparar PDF");
                                }
                            }
                        }, 800);
                    }
                });
            }
        });
    }

    // ── SAVE BASE64 FILE ──────────────────────────────────────────────────────

    @JavascriptInterface
    public void saveBase64File(final String base64Data, final String fileName, final String mimeType) {
        try {
            byte[] bytes = cleanDecode(base64Data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    os.write(bytes);
                    os.close();
                    mostrarMensaje("Guardado en Descargas: " + fileName);
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                mostrarMensaje("Guardado en Descargas: " + fileName);
            }
        } catch (Exception e) {
            mostrarMensaje("Error guardando archivo");
        }
    }

    // ── TOAST ─────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void mostrarMensaje(final String mensaje) {
        new android.os.Handler(context.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show();
            }
        });
    }
}
