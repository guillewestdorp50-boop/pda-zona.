package com.pdazona.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            return
        }
        val human = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "INSTALACIÓN COMPLETADA"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "INSTALACIÓN CANCELADA"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOQUEADA POR EL SISTEMA"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICTO: PAQUETE O FIRMA DISTINTA"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK INCOMPATIBLE CON EL DISPOSITIVO"
            PackageInstaller.STATUS_FAILURE_INVALID -> "APK INVÁLIDA O DAÑADA"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "ESPACIO DE ALMACENAMIENTO INSUFICIENTE"
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "TIEMPO DE ESPERA AGOTADO"
            else -> "ERROR DE INSTALACIÓN (código $status)"
        }
        context.getSharedPreferences("install_diag", Context.MODE_PRIVATE).edit()
            .putString("last_result", "$human\nCódigo Android: $status\nDetalle: ${message.ifBlank { "Android no proporcionó más detalles." }}")
            .apply()
    }
}
