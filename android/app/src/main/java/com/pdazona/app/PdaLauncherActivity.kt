package com.pdazona.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.ValueCallback
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
    private lateinit var rootLayout: FrameLayout
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pageReady = false
    private var pickerShown = false
    private var availableVoices: List<android.speech.tts.Voice> = emptyList()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var deviceLinkStatusHandled = false

    companion object {
        private const val START_URL = "https://guillewestdorp50-boop.github.io/pda-zona./"
        private const val TRUSTED_HOST = "guillewestdorp50-boop.github.io"
        private const val PREFS = "pda_voice_settings"
        private const val VOICE_KEY = "selected_voice"
        private const val VOICE_CONFIGURED_KEY = "voice_configured"
        private const val VOICE_PREFIX = "android:"
        private const val FILE_CHOOSER_REQUEST = 4101
        private const val WEB_PERMISSION_REQUEST = 4102
        private const val LOCATION_PERMISSION_REQUEST = 4103
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
  Object.defineProperty(window,'SpeechSynthesisUtterance',{configurable:true,writable:true,value:PdaUtterance});
  Object.defineProperty(window,'speechSynthesis',{configurable:true,writable:true,value:synth});
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
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private inner class PdaNativeBridge {
        @JavascriptInterface
        fun onDeviceLinkStatus(linked: Boolean) {
            runOnUiThread {
                if (deviceLinkStatusHandled) return@runOnUiThread
                deviceLinkStatusHandled = true
                if (linked) loadAndroidOwnerProfile() else showAndroidDeviceLinkDialog()
            }
        }

        @JavascriptInterface
        fun onDeviceLinkResult(success: Boolean, message: String) {
            runOnUiThread {
                if (success) {
                    AlertDialog.Builder(this@PdaLauncherActivity)
                        .setTitle("PDA VINCULADA")
                        .setMessage("La APK quedó autorizada por la PDA principal.")
                        .setPositiveButton("CONTINUAR", null)
                        .show()
                    loadAndroidOwnerProfile()
                } else {
                    AlertDialog.Builder(this@PdaLauncherActivity)
                        .setTitle("NO SE PUDO VINCULAR")
                        .setMessage(message.ifBlank { "Revisá el código y volvé a intentar." })
                        .setPositiveButton("REINTENTAR") { _, _ -> showAndroidDeviceLinkDialog() }
                        .setNegativeButton("MÁS TARDE", null)
                        .show()
                }
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

    private fun showAndroidDeviceLinkDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "CÓDIGO DE 8 CARACTERES"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }
        AlertDialog.Builder(this)
            .setTitle("VINCULAR CON LA PDA MADRE")
            .setMessage("En la PDA madre generá un código de vinculación nuevo y escribilo acá. Es de un solo uso y vence a los 10 minutos.")
            .setView(input)
            .setPositiveButton("VINCULAR") { _, _ ->
                val code = input.text.toString().trim().uppercase(Locale.ROOT)
                val quoted = JSONObject.quote(code)
                val script = """
                    (async function(){
                      try {
                        await window.pdaInvoke('redeemAndroidDevice',{code:$quoted});
                        window.PdaAndroid.onDeviceLinkResult(true,'');
                      } catch(e) {
                        window.PdaAndroid.onDeviceLinkResult(false,String(e && e.message || e));
                      }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(script, null)
            }
            .setNegativeButton("AHORA NO", null)
            .setCancelable(true)
            .show()
    }

    private fun checkAndroidDeviceLink() {
        val script = """
            (async function(){
              try {
                const result = await window.pdaInvoke('androidDeviceStatus');
                window.PdaAndroid.onDeviceLinkStatus(!!result.linked);
              } catch(e) {
                console.warn('PDA Android link check:',e);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun loadAndroidOwnerProfile() {
        val script = """
            (async function(){
              try {
                const result = await window.pdaInvoke('getAndroidOwnerProfile');
                const profile = result && result.profile;
                if (!profile) return;
                const avatar = document.getElementById('pdaProfileAvatar');
                const placeholder = document.getElementById('pdaProfilePlaceholder');
                const name = document.getElementById('pdaProfileName');
                if (avatar && profile.avatar_url) {
                  avatar.src = profile.avatar_url;
                  avatar.classList.remove('hidden');
                }
                if (placeholder && profile.avatar_url) placeholder.classList.add('hidden');
                if (name && profile.display_name) name.textContent = profile.display_name;
              } catch(e) {
                console.warn('PDA Android owner profile:',e);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
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

    private fun hasAndroidPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        return try {
            startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST)
            true
        } catch (_: Exception) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            false
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        if (request.origin.scheme != "https" || request.origin.host != TRUSTED_HOST) {
            request.deny()
            return
        }
        val resources = request.resources.toList()
        if (resources.isEmpty() || resources.any {
                it != PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                it != PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }) {
            request.deny()
            return
        }
        val needed = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources &&
            !hasAndroidPermission(Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources &&
            !hasAndroidPermission(Manifest.permission.CAMERA)) {
            needed += Manifest.permission.CAMERA
        }
        if (needed.isEmpty()) {
            request.grant(resources.toTypedArray())
        } else {
            pendingWebPermissionRequest?.deny()
            pendingWebPermissionRequest = request
            requestPermissions(needed.distinct().toTypedArray(), WEB_PERMISSION_REQUEST)
        }
    }

    private fun handleGeolocationPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        if (origin != "https://$TRUSTED_HOST") {
            callback.invoke(origin, false, false)
            return
        }
        if (hasAndroidPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasAndroidPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            callback.invoke(origin, true, false)
            return
        }
        pendingGeoCallback = callback
        pendingGeoOrigin = origin
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST
        )
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
                setGeolocationEnabled(true)
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(PdaNativeBridge(), "PdaAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (url.startsWith("https://$TRUSTED_HOST/")) {
                        pageReady = true
                        maybeShowVoicePicker()
                        checkAndroidDeviceLink()
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
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean = openFileChooser(callback, params)

                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { handleWebPermissionRequest(request) }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    runOnUiThread { handleGeolocationPrompt(origin, callback) }
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    webView.visibility = View.GONE
                    rootLayout.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    hideSystemBars()
                }

                override fun onHideCustomView() {
                    customView?.let { rootLayout.removeView(it) }
                    customView = null
                    webView.visibility = View.VISIBLE
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    hideSystemBars()
                }
            }
        }

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        installSpeechAdapter()
        setContentView(rootLayout)

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
        val restored = savedInstanceState != null &&
            webView.restoreState(savedInstanceState) != null
        if (!restored) webView.loadUrl(START_URL)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val result = if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
            filePathCallback?.onReceiveValue(result)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            WEB_PERMISSION_REQUEST -> {
                val request = pendingWebPermissionRequest
                pendingWebPermissionRequest = null
                if (request != null) {
                    val granted = request.resources.all { resource ->
                        when (resource) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                hasAndroidPermission(Manifest.permission.RECORD_AUDIO)
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                hasAndroidPermission(Manifest.permission.CAMERA)
                            else -> false
                        }
                    }
                    if (granted) request.grant(request.resources) else request.deny()
                }
            }
            LOCATION_PERMISSION_REQUEST -> {
                val origin = pendingGeoOrigin
                val callback = pendingGeoCallback
                pendingGeoOrigin = null
                pendingGeoCallback = null
                if (origin != null && callback != null) {
                    val granted =
                        hasAndroidPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                        hasAndroidPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    callback.invoke(origin, granted, false)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::webView.isInitialized) webView.requestLayout()
        hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
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
        if (::webView.isInitialized) webView.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
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
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
        pendingGeoCallback?.let { callback ->
            pendingGeoOrigin?.let { callback.invoke(it, false, false) }
        }
        pendingGeoCallback = null
        pendingGeoOrigin = null
        customView?.let { if (::rootLayout.isInitialized) rootLayout.removeView(it) }
        customView = null
        customViewCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            if (::rootLayout.isInitialized) rootLayout.removeView(webView)
            webView.destroy()
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
