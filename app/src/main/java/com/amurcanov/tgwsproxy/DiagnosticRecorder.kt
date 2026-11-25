package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in bounded private logcat capture; survives Activity being closed.
 * Never uploads data: explicit ACTION_CREATE_DOCUMENT is used for export.
 */
object DiagnosticRecorder {
    private const val TAG = "TgWsProxyDiagnostics"
    private const val MAX_BYTES = 512 * 1024
    private const val TRIM_TO = 256 * 1024
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var worker: Thread? = null
    private var logFile: File? = null
    private val secretPattern = Regex("(?i)\\b(?:secret|token|password|api[_-]?key|key)\\s*[:=]\\s*\\S+")
    private val hexSecretPattern = Regex("(?i)(?<![a-f0-9])[a-f0-9]{32,}(?![a-f0-9])")

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        synchronized(lock) { logFile = File(appContext.filesDir, "proxy-diagnostics.txt") }
        event(appContext, "recording_start version=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT}")
        worker = Thread({
            try {
                val pid = android.os.Process.myPid().toString()
                val proc = ProcessBuilder("logcat", "-v", "time", "--pid", pid)
                    .redirectErrorStream(true).start()
                synchronized(lock) { process = proc }
                proc.inputStream.bufferedReader().use { reader ->
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        if (line.contains("TgWsProxy", ignoreCase = true)
                            || line.contains("ProxyService")
                            || line.contains("tgwsproxy", ignoreCase = true)) {
                            append(line)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Log capture stopped: ${e.javaClass.simpleName}")
            } finally {
                synchronized(lock) { process?.destroy(); process = null }
            }
        }, "PixelEcoDiagnostics").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        synchronized(lock) { process?.destroy(); process = null }
        worker?.interrupt()
        worker = null
    }

    fun event(context: Context, text: String) {
        // A limited start/stop/metrics record is useful even if verbose logcat is disabled.
        synchronized(lock) {
            if (logFile == null) logFile = File(context.applicationContext.filesDir, "proxy-diagnostics.txt")
        }
        append("[event] $text")
    }

    private fun sanitize(text: String): String {
        return text.replace(secretPattern, "[REDACTED]")
            .replace(hexSecretPattern, "[HEX_REDACTED]")
            .take(1500)
    }

    private fun append(line: String) {
        synchronized(lock) {
            val file = logFile ?: return
            try {
                if (file.length() >= MAX_BYTES) {
                    val tail = file.readText().takeLast(TRIM_TO)
                    file.writeText("[older diagnostic entries truncated]\\n$tail")
                }
                val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                file.appendText("$time ${sanitize(line)}\\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write diagnostics: ${e.javaClass.simpleName}")
            }
        }
    }

    fun exportText(context: Context): String {
        val body = synchronized(lock) {
            val file = File(context.applicationContext.filesDir, "proxy-diagnostics.txt")
            if (file.exists()) file.readText() else "No recorded diagnostics. Enable recording before starting proxy."
        }
        val stats = try { NativeProxy.getStats().orEmpty() } catch (_: Throwable) { "not available" }
        val network = try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } catch (_: Exception) { "unknown" }
        return buildString {
            appendLine("TG WS Proxy Pixel Eco diagnostics")
            appendLine("version=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            appendLine("timestamp=${Date()}")
            appendLine("network=$network")
            appendLine("current_stats=${sanitize(stats)}")
            appendLine("NOTE: Transport IPs/hosts and network metadata may be present. Review before sharing.")
            appendLine("--- log ---")
            append(body)
        }
    }
}
