package com.rsestimator;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class AndroidBridge {

    private Context context;
    private WebView webView;
    private WebView printWebView;

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
                    activity.startActivityForResult(intent, 1001);
                } catch (Exception e) {
                    mostrarMensaje("Error al abrir selector");
                }
            }
        });
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {

            try {

                Uri uri = data.getData();

                if (uri == null) {
                    mostrarMensaje("No se seleccionó archivo");
                    return;
                }

                InputStream is = context.getContentResolver().openInputStream(uri);

                if (is == null) {
                    mostrarMensaje("No se pudo leer el archivo");
                    return;
                }

                byte[] buffer = new byte[4096];
                int read;

                java.io.ByteArrayOutputStream baos =
                        new java.io.ByteArrayOutputStream();

                while ((read = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }

                is.close();

                String base64Json = Base64.encodeToString(
                        baos.toByteArray(),
                        Base64.NO_WRAP
                );

                webView.evaluateJavascript(
                        "importBackupBase64('" + base64Json + "')",
                        null
                );

            } catch (Exception e) {
                mostrarMensaje("Error al leer archivo");
            }
        }
    }

    @JavascriptInterface
    public void saveTextFile(String content, String filename, String mimeType) {

        try {

            byte[] bytes = content.getBytes("UTF-8");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                ContentValues values = new ContentValues();

                values.put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        filename
                );

                values.put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        mimeType
                );

                values.put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                );

                Uri uri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                );

                if (uri != null) {

                    OutputStream os =
                            context.getContentResolver().openOutputStream(uri);

                    os.write(bytes);
                    os.close();

                    mostrarMensaje("✅ Backup guardado");

                } else {

                    mostrarMensaje("❌ No se pudo crear backup");
                }

            } else {

                File downloadsDir =
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                        );

                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs();
                }

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

    @JavascriptInterface
    public void printHtml(final String htmlContent, final String filename) {

        final Activity activity = (Activity) context;

        activity.runOnUiThread(new Runnable() {

            @Override
            public void run() {

                try {

                    // Destroy previous printWebView before creating a new one
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

                    printWebView.setWebViewClient(
                            new android.webkit.WebViewClient() {

                        @Override
                        public void onPageFinished(
                                final WebView view,
                                String url
                        ) {

                            view.postDelayed(new Runnable() {

                                @Override
                                public void run() {

                                    try {

                                        PrintManager printManager =
                                                (PrintManager)
                                                        activity.getSystemService(
                                                                Context.PRINT_SERVICE
                                                        );

                                        PrintDocumentAdapter adapter =
                                                view.createPrintDocumentAdapter(
                                                        filename
                                                );

                                        PrintAttributes attributes =
                                                new PrintAttributes.Builder()
                                                        .setMediaSize(
                                                                PrintAttributes.MediaSize.NA_LETTER
                                                        )
                                                        .setColorMode(
                                                                PrintAttributes.COLOR_MODE_COLOR
                                                        )
                                                        .build();

                                        printManager.print(
                                                filename,
                                                adapter,
                                                attributes
                                        );

                                    } catch (Exception e) {

                                        mostrarMensaje("Error al imprimir");
                                    }
                                }

                            }, 800);
                        }
                    });

                    printWebView.loadDataWithBaseURL(
                            "file:///android_asset/",
                            htmlContent,
                            "text/html",
                            "UTF-8",
                            null
                    );

                } catch (Exception e) {

                    mostrarMensaje("Error preparando impresión");
                }
            }
        });
    }

    @JavascriptInterface
    public void saveHtmlAsPdf(
            final String htmlContent,
            final String filename
    ) {

        printHtml(htmlContent, filename);
    }

    @JavascriptInterface
    public void mostrarMensaje(final String mensaje) {

        android.os.Handler handler =
                new android.os.Handler(context.getMainLooper());

        handler.post(new Runnable() {

            @Override
            public void run() {

                Toast.makeText(
                        context,
                        mensaje,
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }
}
