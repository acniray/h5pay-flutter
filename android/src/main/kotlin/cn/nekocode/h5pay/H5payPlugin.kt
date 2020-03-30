package cn.nekocode.h5pay

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import android.R.id.message
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient



object ReturnCode {
    const val success = 1
    const val fail = 0
    const val failCantJump = -1
}

class H5payPlugin(private val registrar: Registrar) : MethodCallHandler {
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "h5pay")
            channel.setMethodCallHandler(H5payPlugin(registrar))
        }
    }

    private var paymentSchemes: Iterable<String> = emptyList()
    private var webView: WebView? = null
    private var result: Result? = null

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.i("onMethodCall",call.method)
        when (call.method) {
            "launchPaymentUrl" -> {
                launchPaymentUrl(call, result)
            }
            "launchUrl" -> {
                launchUrl(call, result)
            }
            "canLaunch" -> {
                canLaunch(call, result)
            }
            else -> {
                result.notImplemented()
            }
        }
        Log.i("onMethodCallResult",result.toString())
    }

    private fun launchPaymentUrl(call: MethodCall, result: Result) {
        val arguments = call.arguments as? HashMap<*, *>
        val url = arguments?.get("url") as? String
        if (url == null) {
            result.success(ReturnCode.fail)
            return
        }
        paymentSchemes = (arguments["paymentSchemes"] as? Iterable<*>)
            ?.filterIsInstance<String>()
            ?: emptyList()

        // Try run url directly
        if (Utils.isPaymentAppUrl(url, paymentSchemes)) {
            val success = Utils.launchUrl(registrar.activity(), url)
            result.success(if (success) ReturnCode.success else ReturnCode.failCantJump)
            return
        }

        initWebView()

        this.result = result
        webView!!.run {
            stopLoading()
            loadUrl(url)
        }
    }

    private fun launchUrl(call: MethodCall, result: Result) {
        val arguments = call.arguments as? HashMap<*, *>
        val url = arguments?.get("url") as? String
        if (url == null) {
            result.success(false)
            return
        }
        result.success(Utils.launchUrl(registrar.activity(), url))
    }

    private fun canLaunch(call: MethodCall, result: Result) {
        val arguments = call.arguments as? HashMap<*, *>
        val url = arguments?.get("url") as? String
        if (url == null) {
            result.success(false)
            return
        }
        result.success(Utils.canLaunch(registrar.activity(), url))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        if (webView != null) {
            return
        }

        val activity = registrar.activity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (0 != (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }

        val webView = WebView(activity)
        webView.visibility = View.GONE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.settings.loadsImagesAutomatically = false
        webView.settings.blockNetworkImage = false
        webView.webViewClient = Client()
        webView.webChromeClient = MyWebChromeClient()
        this.webView = webView
    }

    internal inner class MyWebChromeClient : WebChromeClient() {
        override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
            Log.d("CONTENT", String.format("%s @ %d: %s",
                    cm.message(), cm.lineNumber(), cm.sourceId()))
            return true
        }
    }

    inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            return shouldOverrideUrlLoading(url)
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString()
            return shouldOverrideUrlLoading(url)
        }

        private fun shouldOverrideUrlLoading(url: String?): Boolean {
            url ?: return false
            Log.i("OverrideUrlLoading",url)
            if (Utils.isPaymentAppUrl(url, paymentSchemes)) {
                val success = Utils.launchUrl(registrar.activity(), url)
                result?.success(if (success) ReturnCode.success else ReturnCode.failCantJump)
                return true
            }
            return false
        }
    }
}

object Utils {
    private const val FALLBACK_COMPONENT_NAME =
        "{com.android.fallback/com.android.fallback.Fallback}"

    fun launchUrl(activity: Activity, url: String): Boolean {
        return if (!canLaunch(activity, url)) {
            false
        } else {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun canLaunch(context: Context, url: String): Boolean {
        val launchIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
        }
        val componentName = launchIntent.resolveActivity(context.packageManager)

        return componentName != null &&
                FALLBACK_COMPONENT_NAME != componentName.toShortString()
    }

    fun isPaymentAppUrl(url: String, paymentSchemes: Iterable<String>): Boolean {
        for (scheme in paymentSchemes) {
            if (!url.startsWith("$scheme:")) {
                continue
            }
            return true
        }
        return false
    }
}
