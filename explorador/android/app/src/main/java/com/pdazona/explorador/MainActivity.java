package com.pdazona.explorador;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private DownloadManager downloadManager;
    private long activeDownloadId = -1;
    private BroadcastReceiver downloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("es", "AR"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(new Locale("es", "ES"));
                }
                tts.setSpeechRate(0.88f);
                tts.setPitch(1.0f);
            }
        });

        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != activeDownloadId) return;
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (android.database.Cursor cursor = downloadManager.query(query)) {
                    if (cursor != null && cursor.moveToFirst()
                        && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                        File apk = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Exploradores-update.apk");
                        getPreferences(MODE_PRIVATE).edit().putString("pending_update_apk", apk.getAbsolutePath()).apply();
                        Toast.makeText(MainActivity.this, "Actualización descargada. Preparando instalación…", Toast.LENGTH_LONG).show();
                        installPendingUpdate();
                    } else {
                        Toast.makeText(MainActivity.this, "No se pudo descargar la actualización. Probá de nuevo con conexión.", Toast.LENGTH_LONG).show();
                        notifyPage("No se pudo descargar. Revisá la conexión e intentá otra vez.");
                    }
                }
            }
        };
        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, downloadFilter);

        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        webView.addJavascriptInterface(new SpeechBridge(), "AndroidSpeech");
        webView.addJavascriptInterface(new UpdateBridge(), "AndroidUpdater");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void notifyPage(String message) {
        if (webView == null) return;
        String safe = message.replace("\\", "\\\\").replace("'", "\\'");
        runOnUiThread(() -> webView.evaluateJavascript(
            "if(window.onNativeUpdateMessage)window.onNativeUpdateMessage('" + safe + "')", null));
    }

    private void startUpdateDownload(String url) {
        Uri uri;
        try { uri = Uri.parse(url); } catch (Exception e) {
            notifyPage("El enlace de actualización no es válido."); return;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || !"github.com".equalsIgnoreCase(uri.getHost())
            || !path.startsWith("/guillewestdorp50-boop/pda-zona./releases/download/")
            || !path.endsWith(".apk")) {
            notifyPage("Por seguridad, solo se permiten actualizaciones oficiales de Exploradores."); return;
        }
        try {
            File folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (folder == null) { notifyPage("No se encuentra el almacenamiento para descargar."); return; }
            File old = new File(folder, "Exploradores-update.apk");
            if (old.exists()) old.delete();
            DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle("Actualización de Exploradores")
                .setDescription("Descargando la nueva versión")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "Exploradores-update.apk");
            activeDownloadId = downloadManager.enqueue(request);
            notifyPage("Descargando la actualización. Te avisaremos cuando esté lista.");
            Toast.makeText(this, "Descargando actualización de Exploradores…", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            notifyPage("No se pudo iniciar la descarga. Intentá nuevamente.");
        }
    }

    private void installPendingUpdate() {
        String path = getPreferences(MODE_PRIVATE).getString("pending_update_apk", null);
        if (path == null) return;
        File apk = new File(path);
        if (!apk.isFile() || apk.length() == 0) {
            getPreferences(MODE_PRIVATE).edit().remove("pending_update_apk").apply();
            notifyPage("El archivo descargado no está disponible. Volvé a descargarlo.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settingsIntent);
            Toast.makeText(this, "Permití instalar actualizaciones para Exploradores y volvé atrás.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
            getPreferences(MODE_PRIVATE).edit().remove("pending_update_apk").apply();
        } catch (Exception e) {
            notifyPage("Android no pudo abrir el instalador. Podés volver a intentar desde Actualizar.");
            Toast.makeText(this, "No se pudo abrir el instalador.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (getPreferences(MODE_PRIVATE).contains("pending_update_apk")) installPendingUpdate();
    }

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "1.0.0" : info.versionName;
        } catch (Exception e) { return "1.0.0"; }
    }

    private final class SpeechBridge {
        @JavascriptInterface public void speak(String phrase) {
            runOnUiThread(() -> {
                if (tts != null && phrase != null && !phrase.trim().isEmpty())
                    tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "exploradores-voice");
            });
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }
    }

    private final class UpdateBridge {
        @JavascriptInterface public String getVersionName() { return appVersionName(); }
        @JavascriptInterface public void installUpdate(String url) {
            runOnUiThread(() -> startUpdateDownload(url));
        }
    }

    @Override public void onBackPressed() {
        if (tts != null) tts.stop();
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidSpeech");
            webView.removeJavascriptInterface("AndroidUpdater");
            webView.destroy();
            webView = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
