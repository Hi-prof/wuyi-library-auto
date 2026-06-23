package com.wuyi.libraryauto.ui.screen.session

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.session.WebSessionBootstrapper
import kotlinx.coroutines.delay

@Composable
fun SessionBootstrapHost(
    sessionRepository: SessionRepository,
    modifier: Modifier = Modifier,
) {
    val bootstrapper = remember(sessionRepository) { WebSessionBootstrapper(sessionRepository) }
    val session by sessionRepository.session.collectAsState()
    val pendingSession = session?.takeIf(bootstrapper::needsBootstrap)
    val sessionKey = pendingSession?.bootstrapKey()
    var loadAttempt by remember(sessionKey) { mutableIntStateOf(0) }
    var storageSeeded by remember(sessionKey, loadAttempt) { mutableStateOf(false) }
    var pageSignal by remember(sessionKey, loadAttempt) { mutableIntStateOf(0) }
    var webView by remember(sessionKey, loadAttempt) { mutableStateOf<WebView?>(null) }
    var stopped by remember(sessionKey) { mutableStateOf(false) }

    if (pendingSession == null || stopped) {
        return
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    LaunchedEffect(sessionKey, loadAttempt) {
        delay(BOOTSTRAP_OVERALL_TIMEOUT_MILLIS)
        val latestSession = sessionRepository.currentSession()
        if (latestSession == null || !bootstrapper.needsBootstrap(latestSession)) {
            return@LaunchedEffect
        }
        if (loadAttempt + 1 >= MAX_BOOTSTRAP_ATTEMPTS) {
            stopped = true
        } else {
            loadAttempt += 1
        }
    }

    LaunchedEffect(pageSignal, storageSeeded, sessionKey, loadAttempt) {
        if (!storageSeeded) {
            return@LaunchedEffect
        }

        repeat(MAX_COOKIE_POLL_COUNT) {
            val latestSession = sessionRepository.currentSession()
            if (latestSession == null) {
                return@LaunchedEffect
            }

            val cookieHeader = CookieManager.getInstance().getCookie(latestSession.origin).orEmpty()
            if (bootstrapper.hasRequiredCookies(cookieHeader)) {
                bootstrapper.saveWebSession(latestSession, cookieHeader)
                return@LaunchedEffect
            }
            delay(COOKIE_POLL_DELAY_MILLIS)
        }

        if (loadAttempt + 1 >= MAX_BOOTSTRAP_ATTEMPTS) {
            stopped = true
        } else {
            loadAttempt += 1
        }
    }

    key(sessionKey, loadAttempt) {
        AndroidView(
            factory = { viewContext ->
                buildBootstrapWebView(
                    context = viewContext,
                    session = pendingSession,
                    bootstrapper = bootstrapper,
                    onStorageSeeded = { storageSeeded = true },
                    onPageReady = { pageSignal += 1 },
                    onFailure = {
                        if (loadAttempt + 1 >= MAX_BOOTSTRAP_ATTEMPTS) {
                            stopped = true
                        } else {
                            loadAttempt += 1
                        }
                    },
                ).also { createdWebView ->
                    webView = createdWebView
                }
            },
            modifier = modifier.then(Modifier.size(1.dp)),
            update = { currentWebView ->
                webView = currentWebView
            },
        )
    }

    LaunchedEffect(webView, sessionKey, loadAttempt) {
        val targetView = webView ?: return@LaunchedEffect
        storageSeeded = false
        pageSignal = 0
        clearWebSession(targetView) {
            targetView.loadUrl(bootstrapper.bootstrapEntryUrl(pendingSession))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildBootstrapWebView(
    context: android.content.Context,
    session: AuthenticatedSession,
    bootstrapper: WebSessionBootstrapper,
    onStorageSeeded: () -> Unit,
    onPageReady: () -> Unit,
    onFailure: () -> Unit,
): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.userAgentString = DESKTOP_USER_AGENT
        webViewClient =
            BootstrapWebViewClient(
                session = session,
                bootstrapper = bootstrapper,
                onStorageSeeded = onStorageSeeded,
                onPageReady = onPageReady,
                onFailure = onFailure,
            )
    }

private fun clearWebSession(
    webView: WebView,
    onCleared: () -> Unit,
) {
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        removeAllCookies {
            flush()
            onCleared()
        }
    }
    webView.clearCache(true)
    webView.clearHistory()
}

private class BootstrapWebViewClient(
    private val session: AuthenticatedSession,
    private val bootstrapper: WebSessionBootstrapper,
    private val onStorageSeeded: () -> Unit,
    private val onPageReady: () -> Unit,
    private val onFailure: () -> Unit,
) : WebViewClient() {

    private var didSeedStorage = false

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
        val cookieHeader = CookieManager.getInstance().getCookie(session.origin).orEmpty()
        if (didSeedStorage && bootstrapper.hasRequiredCookies(cookieHeader)) {
            onPageReady()
        }
    }

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        if (didSeedStorage) {
            onPageReady()
            return
        }

        didSeedStorage = true
        view.evaluateJavascript(bootstrapper.buildStorageSeedScript(session)) {
            onStorageSeeded()
            view.loadUrl(bootstrapper.bootstrapTargetUrl(session))
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onFailure()
        }
    }
}

private fun AuthenticatedSession.bootstrapKey(): String = "${origin}|${installationId}|${session.cookieHeader}"

private const val COOKIE_POLL_DELAY_MILLIS = 500L
private const val MAX_COOKIE_POLL_COUNT = 12
private const val BOOTSTRAP_OVERALL_TIMEOUT_MILLIS = 15000L
private const val MAX_BOOTSTRAP_ATTEMPTS = 2
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
