package com.pdazona.installer

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest

class InstallerActivity : Activity() {
    private lateinit var report: TextView
    private var selectedApk: Uri? = null
    private var selectedReport = "Todavía no se seleccionó ningún APK."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 28)
            setBackgroundColor(0xFF101713.toInt())
        }
        root.addView(TextView(this).apply {
            text = "PDA // ZONA\nDIAGNÓSTICO DE INSTALACIÓN"
            textSize = 22f
            setTextColor(0xFFFFDC5A.toInt())
        })
        report = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTextIsSelectable(true)
            text = selectedReport
        }
        root.addView(ScrollView(this).apply { addView(report) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 24 })
        root.addView(Button(this).apply {
            text = "1 · ELEGIR APK Y ANALIZAR"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.android.package-archive"
                }
                startActivityForResult(pick, PICK_APK)
            }
        })
        root.addView(Button(this).apply {
            text = "2 · INSTALAR CON DIAGNÓSTICO"
            setOnClickListener { installSelected() }
        })
        root.addView(Button(this).apply {
            text = "COPIAR INFORME"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico PDA", report.text))
                text = "INFORME COPIADO"
            }
        })
        setContentView(root)
        refreshLastResult()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_APK && resultCode == RESULT_OK) {
            selectedApk = data?.data
            selectedApk?.let { uri ->
                selectedReport = try { analyzeApk(uri) } catch (e: Throwable) {
                    "NO SE PUDO ANALIZAR EL APK\n${e.javaClass.name}\n${e.message}\n${android.util.Log.getStackTraceString(e)}"
                }
                report.text = selectedReport
            }
        }
    }

    private fun copyApk(uri: Uri, name: String): java.io.File {
        val file = java.io.File(cacheDir, name)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo abrir el archivo seleccionado." }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun analyzeApk(uri: Uri): String {
        val temp = copyApk(uri, "candidate.apk")
        val apkInfo = packageManager.getPackageArchiveInfo(temp.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IllegalArgumentException("Android no reconoce el archivo como APK válido.")
        val packageName = apkInfo.packageName
        val versionCode = if (Build.VERSION.SDK_INT >= 28) apkInfo.longVersionCode else apkInfo.versionCode.toLong()
        val signer = signerFingerprint(apkInfo)
        val installed = try { getInstalledPackage(packageName) } catch (_: Exception) { null }
        val installedVersion = installed?.let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }
        val installedSigner = installed?.let { signerFingerprint(it) }
        return buildString {
            appendLine("ANÁLISIS PREVIO DEL APK")
            appendLine("Archivo: ${uri.lastPathSegment ?: "APK seleccionada"}")
            appendLine("Paquete: $packageName")
            appendLine("Versión: ${apkInfo.versionName ?: "sin nombre"} (código $versionCode)")
            appendLine("Firma SHA-256: ${signer ?: "no disponible"}")
            appendLine()
            if (installed == null) appendLine("Instalación previa: no se encontró una app con ese paquete.")
            else {
                appendLine("Instalada: versión código $installedVersion")
                appendLine("Firma instalada SHA-256: ${installedSigner ?: "no disponible"}")
                appendLine("Coincidencia de firma: ${if (signer != null && signer == installedSigner) "SÍ" else "NO / NO SE PUDO VERIFICAR"}")
                if (signer != null && installedSigner != null && signer != installedSigner)
                    appendLine("ALERTA: Android rechazará la actualización por firma diferente.")
                if (versionCode < (installedVersion ?: 0L))
                    appendLine("ALERTA: el APK tiene una versión anterior a la instalada.")
            }
            appendLine()
            appendLine("Android: ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Resultado: análisis previo; todavía no se intentó instalar.")
        }
    }

    private fun installSelected() {
        val uri = selectedApk ?: run { report.text = "Primero elegí un APK y analizalo."; return }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            report.text = selectedReport + "\n\nFALTA AUTORIZACIÓN: habilitá 'Permitir instalar apps desconocidas' para PDA // ZONA · INSTALADOR y volvé."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val temp = copyApk(uri, "candidate-install.apk")
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                temp.inputStream().use { input ->
                    session.openWrite("base.apk", 0, temp.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(this, InstallStatusReceiver::class.java).setAction(ACTION_STATUS)
                val pending = PendingIntent.getBroadcast(this, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
            report.text = selectedReport + "\n\nSOLICITUD ENVIADA A ANDROID. Esperando confirmación y resultado..."
        } catch (error: Throwable) {
            report.text = selectedReport + "\n\nERROR AL PREPARAR INSTALACIÓN\nTipo: ${error.javaClass.name}\nDetalle: ${error.message}\n\n${android.util.Log.getStackTraceString(error)}"
        }
    }

    private fun refreshLastResult() {
        val last = getSharedPreferences("install_diag", MODE_PRIVATE).getString("last_result", null)
        if (last != null) report.text = selectedReport + "\n\nÚLTIMO RESULTADO DE ANDROID\n$last"
    }

    override fun onResume() { super.onResume(); if (::report.isInitialized) refreshLastResult() }

    @Suppress("DEPRECATION")
    private fun getInstalledPackage(name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33)
            packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        else packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)

    @Suppress("DEPRECATION")
    private fun signerFingerprint(info: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
    }

    companion object {
        private const val PICK_APK = 7701
        private const val ACTION_STATUS = "com.pdazona.installer.INSTALL_STATUS"
    }
}
