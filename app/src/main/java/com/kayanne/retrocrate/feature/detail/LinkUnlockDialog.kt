package com.kayanne.retrocrate.feature.detail

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.data.source.ddl.HosterLinks

// One-tap ad-gate for the DDL path. The real file-host link sits behind an ad-shortener (ouo.io) that
// a bot can't clear (reCAPTCHA), so we show it in a WebView: the user taps through the ad once, and the
// moment the page redirects to an actual file host we capture that URL and hand it to debrid. The human
// clears the gate — the app never tries to bypass it — so the no-phone-home rule holds.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinkUnlockDialog(
    shortenerUrl: String,
    onCaptured: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.m, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tap through the ad once",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "We'll grab the download link and unlock it automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Ad-shortener pages (ouo.io → Adscore/Cloudflare) fingerprint the browser via
                            // the Shape Detection API; on this WebView, binding the barcode-detection
                            // provider aborts the whole browser process (native FATAL) and kills the app.
                            // Neuter those APIs before any page script runs so that path is never taken.
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                runCatching {
                                    WebViewCompat.addDocumentStartJavaScript(this, NEUTER_FINGERPRINT_JS, setOf("*"))
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                private var captured = false

                                private fun maybeCapture(url: String?) {
                                    if (captured || url == null) return
                                    if (HosterLinks.isHosterUrl(url)) {
                                        captured = true
                                        currentOnCaptured(url)
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url?.toString()
                                    if (url != null && HosterLinks.isHosterUrl(url)) {
                                        maybeCapture(url)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    Log.i("LinkUnlock", "nav -> $url")
                                    maybeCapture(url)
                                }

                                // Log the exact host/URL when the *main frame* fails to load (a
                                // DNS-blocked ad domain in the ouo.io chain shows as ERR_NAME_NOT_RESOLVED
                                // and a "webpage not available" page). Subresource failures are ignored.
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        Log.w(
                                            "LinkUnlock",
                                            "main-frame load failed: ${request.url} -> code ${error?.errorCode} (${error?.description})",
                                        )
                                    }
                                }

                                // If the WebView's renderer dies anyway (crash or OOM on a hostile ad
                                // page), returning true tells the framework we've handled it — without
                                // this the framework kills the whole app process.
                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    Log.w("LinkUnlock", "WebView renderer gone (didCrash=${detail?.didCrash()}) — closing gate")
                                    (view?.parent as? ViewGroup)?.removeView(view)
                                    view?.destroy()
                                    currentOnDismiss()
                                    return true
                                }
                            }
                            loadUrl(shortenerUrl)
                        }
                    },
                    onRelease = { runCatching { it.destroy() } },
                )
            }
        }
    }
}

// Runs before any page script. Removes the Shape Detection APIs the ad-shortener's fingerprinting
// probes — binding the barcode-detection provider crashes this WebView's browser process natively,
// which was taking the whole app down. Feature-detection just sees them as unsupported.
private const val NEUTER_FINGERPRINT_JS =
    "(function(){try{['BarcodeDetector','FaceDetector','TextDetector'].forEach(function(k){" +
        "try{Object.defineProperty(window,k,{value:undefined,configurable:true});}catch(e){try{window[k]=undefined;}catch(e2){}}" +
        "});}catch(e){}})();"
