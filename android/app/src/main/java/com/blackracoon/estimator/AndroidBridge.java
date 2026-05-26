package com.blackracoon.estimator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
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
                    showToastByKey("toastErrOpenSelector");
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
                        showToastByKey("toastErrPrepCamera");
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
                    showToastByKey("toastErrOpenCamera");
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
                if (uri == null) { showToastByKey("toastNoFileSelected"); return; }

                InputStream is = context.getContentResolver().openInputStream(uri);
                if (is == null) { showToastByKey("toastErrReadFile"); return; }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) baos.write(buffer, 0, read);
                is.close();

                String base64Json = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                webView.evaluateJavascript("importBackupBase64('" + base64Json + "')", null);

            } catch (Exception e) {
                showToastByKey("toastErrReadFile");
            }
        }

        if (requestCode == REQUEST_CAMERA
                && resultCode == Activity.RESULT_OK
                && pendingPhotoUri != null) {

            try {
                InputStream is = context.getContentResolver().openInputStream(pendingPhotoUri);
                if (is == null) { showToastByKey("toastErrReadPhoto"); return; }

                // Decode and scale down to ~1200px max dimension
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
                is.close();

                if (bitmap == null) { showToastByKey("toastErrProcessPhoto"); return; }

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
                showToastByKey("toastErrSavePhoto");
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
                    showToastByKey("toastBackupSaved");
                } else {
                    showToastByKey("toastBackupFailed");
                }

            } else {

                File downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();

                File file = new File(downloadsDir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                showToastByKey("toastBackupSaved");
            }

        } catch (Exception e) {
            showToastByKey("toastBackupError");
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
                                        showToastByKey("toastErrPrint");
                                    }
                                }
                            }, 400);
                        }
                    });

                    printWebView.loadDataWithBaseURL(
                            "file:///android_asset/", htmlContent, "text/html", "UTF-8", null);

                } catch (Exception e) {
                    showToastByKey("toastErrPrepPrint");
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
                final int imgW = 1080;
                final float density = activity.getResources().getDisplayMetrics().density;
                final int physW = (int)(imgW * density);
                final WebView iv = new WebView(activity);
                iv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                iv.getSettings().setJavaScriptEnabled(true);
                activity.addContentView(iv, new ViewGroup.LayoutParams(physW, 10000));
                iv.loadDataWithBaseURL("file:///android_asset/",
                        injectViewport(html, imgW), "text/html", "UTF-8", null);
                iv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        view.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    int h = (int)(Math.min(Math.max(view.getContentHeight(), 400), (int)(10000 / density)) * density);
                                    view.layout(0, 0, physW, h);
                                    final Bitmap bmp = Bitmap.createBitmap(physW, h, Bitmap.Config.ARGB_8888);
                                    Canvas c = new Canvas(bmp);
                                    c.drawColor(Color.WHITE);
                                    view.draw(c);
                                    if (iv.getParent() instanceof ViewGroup)
                                        ((ViewGroup) iv.getParent()).removeView(iv);
                                    iv.destroy();
                                    new Thread(new Runnable() {
                                        @Override public void run() {
                                            try {
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
                                                if (imgUri == null) { showToastByKey("toastErrSaveImage"); return; }
                                                final Intent intent = new Intent(Intent.ACTION_SEND);
                                                intent.setType("image/png");
                                                intent.putExtra(Intent.EXTRA_STREAM, imgUri);
                                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                                if ("whatsapp".equals(channel)) intent.setPackage("com.whatsapp");
                                                activity.runOnUiThread(new Runnable() {
                                                    @Override public void run() {
                                                        try { activity.startActivity(intent); }
                                                        catch (Exception e) {
                                                            activity.startActivity(Intent.createChooser(intent, "Compartir"));
                                                        }
                                                    }
                                                });
                                            } catch (Exception e) {
                                                showToastByKey("toastErrSaveImage");
                                            } finally {
                                                if (!bmp.isRecycled()) bmp.recycle();
                                            }
                                        }
                                    }).start();
                                } catch (OutOfMemoryError oom) {
                                    if (iv.getParent() instanceof ViewGroup)
                                        ((ViewGroup) iv.getParent()).removeView(iv);
                                    iv.destroy();
                                    showToastByKey("toastErrGenImage");
                                } catch (Exception e) {
                                    if (iv.getParent() instanceof ViewGroup)
                                        ((ViewGroup) iv.getParent()).removeView(iv);
                                    iv.destroy();
                                    showToastByKey("toastErrGenImage");
                                }
                            }
                        }, 400);
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
                final int pageW = 794;
                final int pageH = 1123;
                final int maxH  = 15000;
                final float density = activity.getResources().getDisplayMetrics().density;
                final int physW = (int)(pageW * density);
                final int physPageH = (int)(pageH * density);
                final WebView pv = new WebView(activity);
                pv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                pv.getSettings().setJavaScriptEnabled(true);
                activity.addContentView(pv, new ViewGroup.LayoutParams(physW, maxH));
                pv.loadDataWithBaseURL("file:///android_asset/",
                        injectViewport(html, pageW), "text/html", "UTF-8", null);
                pv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        view.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    final int totalH = Math.min((int)(Math.max(pv.getContentHeight(), 200) * density), maxH);
                                    pv.layout(0, 0, physW, totalH);
                                    // Draw WebView ONCE to bitmap — single render pass
                                    final Bitmap bmp = Bitmap.createBitmap(physW, totalH, Bitmap.Config.ARGB_8888);
                                    Canvas bmpCanvas = new Canvas(bmp);
                                    bmpCanvas.drawColor(Color.WHITE);
                                    pv.draw(bmpCanvas);
                                    if (pv.getParent() instanceof ViewGroup)
                                        ((ViewGroup) pv.getParent()).removeView(pv);
                                    pv.destroy();
                                    // Slice bitmap into PDF pages + file write on background thread
                                    new Thread(new Runnable() {
                                        @Override public void run() {
                                            try {
                                                android.graphics.pdf.PdfDocument document =
                                                        new android.graphics.pdf.PdfDocument();
                                                int pageNum = 1;
                                                for (int yOff = 0; yOff < totalH; yOff += physPageH) {
                                                    int srcH = Math.min(physPageH, totalH - yOff);
                                                    int dstH = Math.max(1, (int) Math.round((float) srcH / density));
                                                    android.graphics.pdf.PdfDocument.PageInfo info =
                                                        new android.graphics.pdf.PdfDocument.PageInfo
                                                            .Builder(pageW, dstH, pageNum++).create();
                                                    android.graphics.pdf.PdfDocument.Page page =
                                                        document.startPage(info);
                                                    Canvas canvas = page.getCanvas();
                                                    canvas.drawColor(Color.WHITE);
                                                    android.graphics.Rect src = new android.graphics.Rect(
                                                            0, yOff, physW, Math.min(yOff + srcH, bmp.getHeight()));
                                                    canvas.drawBitmap(bmp, src,
                                                            new android.graphics.RectF(0, 0, pageW, dstH), null);
                                                    document.finishPage(page);
                                                }
                                                String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                                                final Uri shareUri;
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                    // API 29+: save to MediaStore Downloads.
                                                    // This gives a content://media URI that Gmail,
                                                    // WhatsApp and every other app can read without
                                                    // custom permission grants.
                                                    ContentValues cv = new ContentValues();
                                                    cv.put(MediaStore.Downloads.DISPLAY_NAME, safeName + ".pdf");
                                                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                                                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                                                    Uri dlUri = context.getContentResolver().insert(
                                                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                                                    if (dlUri == null) throw new Exception("MediaStore insert failed");
                                                    OutputStream dlOs = context.getContentResolver().openOutputStream(dlUri);
                                                    document.writeTo(dlOs);
                                                    dlOs.close();
                                                    shareUri = dlUri;
                                                } else {
                                                    // API < 29: write to cache and use custom provider
                                                    File pdfFile = new File(activity.getCacheDir(), safeName + ".pdf");
                                                    FileOutputStream fos = new FileOutputStream(pdfFile);
                                                    document.writeTo(fos);
                                                    fos.close();
                                                    shareUri = Uri.parse(
                                                            "content://com.blackracoon.estimator.rsprovider"
                                                            + pdfFile.getAbsolutePath());
                                                }
                                                document.close();
                                                final Intent intent = new Intent(Intent.ACTION_SEND);
                                                intent.setType("application/pdf");
                                                intent.putExtra(Intent.EXTRA_STREAM, shareUri);
                                                intent.putExtra(Intent.EXTRA_SUBJECT, name);
                                                intent.setClipData(ClipData.newRawUri("", shareUri));
                                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                                if ("whatsapp".equals(channel)) {
                                                    intent.setPackage("com.whatsapp");
                                                } else if ("email".equals(channel)) {
                                                    intent.setPackage("com.google.android.gm");
                                                }
                                                activity.runOnUiThread(new Runnable() {
                                                    @Override public void run() {
                                                        try { activity.startActivity(intent); }
                                                        catch (Exception e) {
                                                            intent.setPackage(null);
                                                            activity.startActivity(
                                                                    Intent.createChooser(intent, "Compartir PDF"));
                                                        }
                                                    }
                                                });
                                            } catch (Exception e) {
                                                showToastByKey("toastErrSavePdf");
                                            } finally {
                                                if (!bmp.isRecycled()) bmp.recycle();
                                            }
                                        }
                                    }).start();
                                } catch (OutOfMemoryError oom) {
                                    if (pv.getParent() instanceof ViewGroup)
                                        ((ViewGroup) pv.getParent()).removeView(pv);
                                    pv.destroy();
                                    showToastByKey("toastErrGenPdf");
                                } catch (Exception e) {
                                    if (pv.getParent() instanceof ViewGroup)
                                        ((ViewGroup) pv.getParent()).removeView(pv);
                                    pv.destroy();
                                    showToastByKey("toastErrGenPdf");
                                }
                            }
                        }, 400);
                    }
                });
            }
        });
    }

    private String injectViewport(String html, int width) {
        String tag = "<meta name='viewport' content='width=" + width + ",initial-scale=1'>";
        int idx = html.toLowerCase().indexOf("<head>");
        if (idx >= 0) return html.substring(0, idx + 6) + tag + html.substring(idx + 6);
        return tag + html;
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
                    showToastParam("toastSavedToDownloads", fileName);
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                showToastParam("toastSavedToDownloads", fileName);
            }
        } catch (Exception e) {
            showToastByKey("toastErrSaveFile");
        }
    }

    // ── SHARE TEXT ────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void shareText(final String text, final String channel) {
        final Activity activity = (Activity) context;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, text);
                    if ("whatsapp".equals(channel)) {
                        intent.setPackage("com.whatsapp");
                    } else if ("email".equals(channel)) {
                        intent.putExtra(Intent.EXTRA_SUBJECT, "Estimado");
                        intent.setType("message/rfc822");
                    }
                    try {
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        activity.startActivity(Intent.createChooser(intent, "Compartir"));
                    }
                } catch (Exception e) {
                    showToastByKey("toastErrShare");
                }
            }
        });
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

    private void showToastByKey(final String key) {
        webView.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript(
                    "typeof showToast==='function'&&showToast(typeof t==='function'?t('" + key + "'):'" + key + "')", null);
            }
        });
    }

    private void showToastParam(final String key, final String param) {
        webView.post(new Runnable() {
            @Override public void run() {
                String safeParam = param.replace("'", "\\'");
                webView.evaluateJavascript(
                    "typeof showToast==='function'&&showToast((typeof t==='function'?t('" + key + "'):'" + key + "')+'"+safeParam+"')", null);
            }
        });
    }
}
