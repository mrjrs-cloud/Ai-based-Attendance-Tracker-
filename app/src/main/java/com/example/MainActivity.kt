package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val results: Array<Uri>? = when {
                intent?.clipData != null -> {
                    val count = intent.clipData!!.itemCount
                    Array(count) { i -> intent.clipData!!.getItemAt(i).uri }
                }
                intent?.data != null -> arrayOf(intent.data!!)
                else -> null
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .systemBarsPadding()
                ) {
                    AttendanceWebView(
                        onOpenFileChooser = { callback, params ->
                            filePathCallback = callback
                            val intent = params.createIntent().apply {
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                            try {
                                filePickerLauncher.launch(intent)
                            } catch (e: Exception) {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = null
                            }
                        },
                        bridge = AndroidBridge()
                    )
                }
            }
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun getApiKey(): String {
            return BuildConfig.GEMINI_API_KEY
        }

        @JavascriptInterface
        fun isNative(): Boolean {
            return true
        }

        @JavascriptInterface
        fun callGeminiVision(base64Image: String, mimeType: String, mode: String): String {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                return ""
            }
            return try {
                val prompt = if (mode == "meet") {
                    """You are an expert OCR & AI Vision assistant for Google Meet attendance.
Analyze this Google Meet participant screenshot carefully.
Extract every visible student/participant.
Extract their full Name, Roll number (if present), and Student ID (if present).
IGNORE all Google Meet UI controls, timestamps, chat icons, mic/video toggles, "You", "Meeting details", "Host", "pin", "Search for people", or status labels.

Return ONLY a valid JSON object strictly matching this schema:
{
  "participants": [
    {
      "name": "Full Name as visible",
      "roll": "Roll number or empty string if not visible",
      "student_id": "Student ID or empty string if not visible"
    }
  ]
}
Do not include markdown code block markers or any explanations, output pure JSON."""
                } else {
                    """You are an expert OCR & AI Vision assistant for Student Rosters and Class Attendance Sheets.
Extract all students listed in this document image.
Extract each student's Roll number, Full Name, and Student ID.

Return ONLY a valid JSON object strictly matching this schema:
{
  "participants": [
    {
      "name": "Full Name",
      "roll": "Roll number or empty string",
      "student_id": "Student ID or empty string"
    }
  ]
}
Do not include markdown code block markers, output pure JSON."""
                }

                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                                put(JSONObject().apply {
                                    put("inlineData", JSONObject().apply {
                                        put("mimeType", mimeType)
                                        put("data", base64Image)
                                    })
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                        put("responseMimeType", "application/json")
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return ""
                }
                val respString = response.body?.string() ?: ""
                val respJson = JSONObject(respString)
                val text = respJson.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text") ?: ""
                text
            } catch (e: Exception) {
                ""
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AttendanceWebView(
    onOpenFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    bridge: MainActivity.AndroidBridge
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportZoom(false)
                    mediaPlaybackRequiresUserGesture = false
                }
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                addJavascriptInterface(bridge, "AndroidBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        if (filePathCallback != null && fileChooserParams != null) {
                            onOpenFileChooser(filePathCallback, fileChooserParams)
                            return true
                        }
                        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("https://wa.me") || url.startsWith("whatsapp://")) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val key = bridge.getApiKey()
                        if (key.isNotEmpty() && key != "MY_GEMINI_API_KEY") {
                            view?.evaluateJavascript("window.GEMINI_API_KEY = '$key';", null)
                        }
                    }
                }

                loadUrl("file:///android_asset/index.html")
            }
        }
    )
}
