package com.kayanne.retrocrate.feature.detail

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
                                    maybeCapture(url)
                                }
                            }
                            loadUrl(shortenerUrl)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}
