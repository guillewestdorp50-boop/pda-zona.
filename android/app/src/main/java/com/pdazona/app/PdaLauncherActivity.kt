package com.pdazona.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Independent launcher for PDA // ZONA.
 * The actual application runs in the user's installed Google Chrome,
 * so its cookies, local storage and browser session belong to Chrome.
 */
class PdaLauncherActivity : Activity() {
    companion object {
        private const val START_URL = "https://guillewestdorp50-boop.github.io/pda-zona./"
        private const val CHROME_PACKAGE = "com.android.chrome"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(START_URL)).apply {
            setPackage(CHROME_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(chromeIntent)
            finish()
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("GOOGLE CHROME NO ESTÁ DISPONIBLE")
                .setMessage("Instalá o habilitá Google Chrome para abrir PDA // ZONA.")
                .setPositiveButton("ABRIR GOOGLE PLAY") { _, _ ->
                    val playIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$CHROME_PACKAGE")
                    )
                    try {
                        startActivity(playIntent)
                    } catch (_: ActivityNotFoundException) {
                        // Leave the explanation visible if no store app is available.
                    }
                    finish()
                }
                .setNegativeButton("CERRAR") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }
}
