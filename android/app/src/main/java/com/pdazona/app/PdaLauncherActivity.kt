package com.pdazona.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** APK-only adapter: the PWA remains the source of Lebedev's texts and behavior. */
class PdaLauncherActivity : Activity() {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pageReady = false
    private var pickerShown = false
    private var availableVoices: List<android.speech.tts.Voice> = emptyList()

    companion object {
        private const val START_URL = "https://guillewestdorp50-boop.github.io/pda-zona./"
        private const val TRUSTED_HOST = "guillewestdorp50-boop.github.io"
        private const val PREFS = "pda_voice_settings"
        private const val VOICE_KEY = "selected_voice"
        private const val VOICE_CONFIGURED_KEY = "voice_configured"
        private const val VOICE_PREFIX = "android:"
        private const val DOCUMENT_START_SCRIPT = """
(function(){
  let voices=[];
  let nextId=0;
  const utterances=new Map();
  class PdaUtterance {
    constructor(text){this.text=String(text||'');this.lang='';this.voice=null;this.rate=1;this.pitch=1;this.volume=1;this.onstart=null;this.onend=null;this.onerror=null;this.onpause=null;this.onresume=null;this.onboundary=null;}
  }
  const synth={
    pending:false,speaking:false,paused:false,onvoiceschanged:null,
    getVoices(){return voices.slice();},
    cancel(){this.pending=false;this.speaking=false;try{PdaAndroid.stop();}catch(e){}},
    pause(){},resume(){},
    speak(u){
      if(!u)return;
      const id=String(++nextId);
      utterances.set(id,u);this.pending=true;this.speaking=true;
      try{PdaAndroid.speak(String(u.text||''),u.voice&&u.voice.voiceURI||'',u.lang||(u.voice&&u.voice.lang)||'es-AR',Number(u.rate)||1,Number(u.pitch)||1,Number(u.volume)||1,id);}
      catch(e){this.speaking=false;this.pending=false;if(typeof u.onerror==='function')u.onerror({error:'native-unavailable'});}
    }
  };
  window.SpeechSynthesisUtterance=PdaUtterance;
  window.speechSynthesis=synth;
  window.__pdaNativeTtsReady=function(json){
    try{voices=JSON.parse(json||'[]');}catch(e){voices=[];}
    synth.pending=false;
    if(typeof synth.onvoiceschanged==='function')synth.onvoiceschanged(new Event('voiceschanged'));
    window.dispatchEvent(new Event('voiceschanged'));
  };
  window.__pdaNativeTtsEvent=function(type,id){
    const u=utterances.get(String(id));
    if(type==='start'){synth.pending=false;synth.speaking=true;}
    if(type==='end'||type==='error'){synth.pending=false;synth.speaking=false;utterances.delete(String(id));}
    if(u&&typeof u['on'+type]==='function')u['on'+type]({type,target:u});
  };
})();
"""
    }

    private fun hideSystemBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private inner class PdaNativeBridge {
        @JavascriptInterface
        fun setOrientation(value: String) {
            runOnUiThread {
                requestedOrientation = when (value) {
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> return@runOnUiThread
                }
                hideSystemBars()
            }
        }

        @JavascriptInterface
        fun speak(text: String, voiceUri: String, lang: String, rate: Double,
                  pitch: Double, volume: Double, utteranceId: String) {
            runOnUiThread {
                val engine = tts ?: return@runOnUiThread
                if (!ttsReady) return@runOnUiThread
                val voiceName = voiceUri.removePrefix(VOICE_PREFIX)
                val selected = availableVoices.firstOrNull { it.name == voiceName }
                    ?: selectedNativeVoice()
                if (selected != null) engine.voice = selected
                engine.setSpeechRate(rate.toFloat().coerceIn(0.5f, 2f))
                engine.setPitch(pitch.toFloat().coerceIn(0.5f, 2f))
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.toFloat().coerceIn(0f, 1f))
                }
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread { tts?.stop() }
        }
    }

    private fun selectedNativeVoice(): android.speech.tts.Voice? {
        val name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(VOICE_KEY, null)
        return availableVoices.firstOrNull { it.name == name }
            ?: availableVoices.firstOrNull { it.locale.language == "es" }
            ?: availableVoices.firstOrNull()
    }

    private fun voiceListJson(): String {
        val selectedName = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(VOICE_KEY, null)
        val array = JSONArray()
        availableVoices.forEach { voice ->
            array.put(JSONObject().apply {
                put("voiceURI", VOICE_PREFIX + voice.name)
                put("name", voice.name)
                put("lang", voice.locale.toLanguageTag())
                put("default", voice.name == selectedName)
                put("localService", true)
            })
        }
        return array.toString()
    }

    private fun publishVoicesToPage() {
        if (!pageReady || !ttsReady || !pickerShown) return
        val json = voiceListJson()
        webView.evaluateJavascript(
            "window.__pdaNativeTtsReady && window.__pdaNativeTtsReady(" +
                JSONObject.quote(json) + ");", null
        )
    }

    private fun maybeShowVoicePicker() {
        if (!pageReady || !ttsReady || pickerShown) return
        pickerShown = true
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(VOICE_CONFIGURED_KEY, false)) {
            publishVoicesToPage()
            return
        }
        if (availableVoices.isEmpty()) {
            publishVoicesToPage()
            return
        }
        val ordered = availableVoices.sortedWith(
            compareByDescending<android.speech.tts.Voice> { it.locale.language == "es" }
                .thenBy { it.locale.displayName }
                .thenBy { it.name }
        )
        var selectedIndex = ordered.indexOfFirst { it.name == selectedNativeVoice()?.name }
            .coerceAtLeast(0)
        val labels = ordered.map {
            it.locale.getDisplayName(Locale("es", "ES")) + " · " + it.name +
                if (it.isNetworkConnectionRequired) " · requiere conexión" else ""
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("ELEGIR VOZ DE LEBEDEV")
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
                tts?.let { engine ->
                    engine.voice = ordered[selectedIndex]
                    engine.setSpeechRate(0.92f)
                    engine.setPitch(1.0f)
                    engine.speak(
                        "Prueba de voz. Bienvenido a la Zona, stalker. Buena suerte. La vas a necesitar.",
                        TextToSpeech.QUEUE_FLUSH, null, "voice-preview"
                    )
                }
            }
            .setPositiveButton("GUARDAR VOZ") { _, _ ->
                val voice = ordered[selectedIndex]
                prefs.edit().putString(VOICE_KEY, voice.name)
                    .putBoolean(VOICE_CONFIGURED_KEY, true).apply()
                tts?.voice = voice
                publishVoicesToPage()
            }
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            if (!prefs.getBoolean(VOICE_CONFIGURED_KEY, false)) {
                val voice = ordered[selectedIndex]
                prefs.edit().putString(VOICE_KEY, voice.name)
                    .putBoolean(VOICE_CONFIGURED_KEY, true).apply()
                tts?.voice = voice
                publishVoicesToPage()
            }
        }
        dialog.show()
    }

    private fun installSpeechAdapter() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, DOCUMENT_START_SCRIPT, setOf(TRUSTED_HOST)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            clearCache(false)
            addJavascriptInterface(PdaNativeBridge(), "PdaAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (url.startsWith("https://$TRUSTED_HOST/")) {
                        pageReady = true
                        maybeShowVoicePicker()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "https" && uri.host == TRUSTED_HOST) return false
                    if (scheme == "http" || scheme == "https") {
                        return try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                            true
                        } catch (_: Exception) { true }
                    }
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) { true }
                }
            }
            webChromeClient = WebChromeClient()
        }

        installSpeechAdapter()
        setContentView(webView)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                availableVoices = tts?.voices?.filter { it.locale != null } ?: emptyList()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { dispatchTtsEvent("start", it) }
                    }
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { dispatchTtsEvent("end", it) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { dispatchTtsEvent("error", it) }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { dispatchTtsEvent("error", it) }
                    }
                })
                tts?.voice = selectedNativeVoice()
                runOnUiThread { maybeShowVoicePicker() }
            }
        }
        webView.loadUrl(START_URL)
    }

    private fun dispatchTtsEvent(type: String, id: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__pdaNativeTtsEvent && window.__pdaNativeTtsEvent(" +
                    JSONObject.quote(type) + "," + JSONObject.quote(id) + ");", null
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
