package nl.janick.cyclus

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val REQ_FILE = 10
        private const val REQ_NOTIF = 11
        private const val REQ_AUTO_PDF = 12
    }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#5B3A4B")
        Reminder.ensureChannel(this)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#FBF6F1"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                // Externe links openen in de browser.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    // Geen app om de link te openen.
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                return try {
                    startActivityForResult(Intent.createChooser(pick, "Kies je back-upbestand"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Annuleren") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("https://$HOST/assets/www/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUTO_PDF) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                // Toegang onthouden, zodat de app het bestand later steeds kan bijwerken.
                try {
                    contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) { }
                autoPrefs().edit().putString("auto_pdf", uri.toString()).putString("auto_pdf_name", displayName(uri)).apply()
                web.evaluateJavascript("window.__autoPdfPicked && window.__autoPdfPicked(true)", null)
            } else {
                web.evaluateJavascript("window.__autoPdfPicked && window.__autoPdfPicked(false)", null)
            }
            return
        }
        if (requestCode == REQ_FILE) {
            val uri = data?.data
            fileCallback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
            fileCallback = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            val ok = Reminder.allowed(this)
            web.evaluateJavascript("window.__notifResult && window.__notifResult($ok)", null)
        }
    }

    private fun share(name: String, bytes: ByteArray, mime: String) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(this, "nl.janick.cyclus.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        runOnUiThread { startActivity(Intent.createChooser(send, "Opslaan of delen")) }
    }

    private fun autoPrefs() = getSharedPreferences("cyclus", MODE_PRIVATE)

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "cycluskaart.pdf"
    } catch (e: Exception) { "cycluskaart.pdf" }

    /** Functies die de webpagina kan aanroepen via window.Android. */
    inner class Bridge {
        @JavascriptInterface
        fun getData(): String? = Storage.read(this@MainActivity)

        @JavascriptInterface
        fun setData(json: String) {
            Storage.write(this@MainActivity, json)
        }

        @JavascriptInterface
        fun shareFile(name: String, content: String, mime: String) {
            share(name, content.toByteArray(Charsets.UTF_8), mime)
        }

        @JavascriptInterface
        fun shareBase64(name: String, base64: String, mime: String) {
            share(name, Base64.decode(base64, Base64.DEFAULT), mime)
        }

        // ---- Cycluskaart automatisch bijhouden in één vast PDF-bestand ----

        /** Laat kiezen waar de kaart komt (bijv. een map op Google Drive). */
        @JavascriptInterface
        fun pickAutoPdf() {
            runOnUiThread {
                val pick = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_TITLE, "Cycluskaart.pdf")
                try { startActivityForResult(pick, REQ_AUTO_PDF) } catch (e: Exception) { }
            }
        }

        /** Naam van het bestand dat bijgehouden wordt, of leeg als er niets gekozen is. */
        @JavascriptInterface
        fun autoPdfName(): String =
            if (autoPrefs().getString("auto_pdf", null) == null) "" else autoPrefs().getString("auto_pdf_name", "Cycluskaart.pdf") ?: ""

        @JavascriptInterface
        fun autoPdfLast(): Long = autoPrefs().getLong("auto_pdf_last", 0L)

        /** Het gekozen bestand overschrijven met de nieuwe kaart. Geeft false als dat niet lukt. */
        @JavascriptInterface
        fun writeAutoPdf(base64: String): Boolean {
            val raw = autoPrefs().getString("auto_pdf", null) ?: return false
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                contentResolver.openOutputStream(Uri.parse(raw), "wt")?.use { it.write(bytes) } ?: return false
                autoPrefs().edit().putLong("auto_pdf_last", System.currentTimeMillis()).apply()
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun clearAutoPdf() {
            val raw = autoPrefs().getString("auto_pdf", null)
            if (raw != null) try {
                contentResolver.releasePersistableUriPermission(Uri.parse(raw),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { }
            autoPrefs().edit().remove("auto_pdf").remove("auto_pdf_name").remove("auto_pdf_last").apply()
        }

        @JavascriptInterface
        fun notificationsAllowed(): Boolean = Reminder.allowed(this@MainActivity)

        @JavascriptInterface
        fun requestNotifications() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                } else {
                    val ok = Reminder.allowed(this@MainActivity)
                    web.evaluateJavascript("window.__notifResult && window.__notifResult($ok)", null)
                }
            }
        }

        @JavascriptInterface
        fun setReminder(on: Boolean, time: String) {
            Reminder.save(this@MainActivity, on, time)
        }

        @JavascriptInterface
        fun testNotification() {
            Reminder.notify(this@MainActivity, "🌷 Cyclus — test", "Gelukt! Je meldingen werken.")
        }
    }
}
