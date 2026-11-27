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
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;
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
                ttsReady = true;
                if (pendingSpeech != null && !pendingSpeech.trim().isEmpty()) {
                    tts.speak(pendingSpeech, TextToSpeech.QUEUE_FLUSH, null, "exploradores-voice");
                    pendingSpeech = null;
                }
                // Voz elegida por el usuario: Google español de Estados Unidos, familia ESD.
                if (tts.getVoices() != null) {
                    for (Voice voice : tts.getVoices()) {
                        if (voice.getLocale() != null
                            && "es".equalsIgnoreCase(voice.getLocale().getLanguage())
                            && voice.getName() != null
                            && voice.getName().toLowerCase(Locale.ROOT).startsWith("es-us-x-esd")) {
                            tts.setVoice(voice);
                            break;
                        }
                    }
                }
            }
        });

        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        activeDownloadId = getPreferences(MODE_PRIVATE).getLong("active_download_id", -1);
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                long savedId = getPreferences(MODE_PRIVATE).getLong("active_download_id", -1);
                if (id != activeDownloadId && id != savedId) return;
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (android.database.Cursor cursor = downloadManager.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            File apk = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Exploradores-update.apk");
                            getPreferences(MODE_PRIVATE).edit()
                                .remove("active_download_id")
                                .putString("pending_update_apk", apk.getAbsolutePath()).apply();
                            activeDownloadId = -1;
                            notifyPage("Descarga completa. Abriendo el instalador…");
                            Toast.makeText(MainActivity.this, "Actualización descargada. Preparando instalación…", Toast.LENGTH_LONG).show();
                            installPendingUpdate();
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                            getPreferences(MODE_PRIVATE).edit().remove("active_download_id").apply();
                            activeDownloadId = -1;
                            notifyPage("La descarga falló (código " + reason + "). Tocá Actualizar para reintentar.");
                            Toast.makeText(MainActivity.this, "No se pudo descargar la actualización. Probá de nuevo.", Toast.LENGTH_LONG).show();
                        }
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
            getPreferences(MODE_PRIVATE).edit().putLong("active_download_id", activeDownloadId).apply();
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
                if (tts == null || phrase == null || phrase.trim().isEmpty()) return;
                if (!ttsReady) { pendingSpeech = phrase; return; }
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
        @JavascriptInterface public void fetchReleases(String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,80}")) return;
            new Thread(() -> {
                HttpURLConnection connection = null;
                JSONObject result = new JSONObject();
                try {
                    URL url = new URL("https://api.github.com/repos/guillewestdorp50-boop/pda-zona./releases?per_page=100");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(15000);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Accept", "application/vnd.github+json");
                    connection.setRequestProperty("User-Agent", "Exploradores-Android-Updater");
                    int code = connection.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw new Exception("GitHub respondió HTTP " + code + ".");
                    }
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                    JSONArray releases = new JSONArray(body.toString());
                    result.put("ok", true);
                    result.put("releases", releases);
                } catch (Exception e) {
                    try {
                        result.put("ok", false);
                        result.put("error", e.getMessage() == null ? "No se pudo conectar con GitHub." : e.getMessage());
                    } catch (Exception ignored) {}
                } finally {
                    if (connection != null) connection.disconnect();
                }
                String payload = result.toString();
                String script = "window." + callbackName + "(" + JSONObject.quote(payload) + ")";
                // Parsear el JSON en JavaScript para entregar un objeto, no una cadena.
                script = "window." + callbackName + "(" + payload + ")";
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(script, null);
                });
            }, "exploradores-release-check").start();
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
