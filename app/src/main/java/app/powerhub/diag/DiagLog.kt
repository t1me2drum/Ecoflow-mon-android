package app.powerhub.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device diagnostic log the user can share when something misbehaves.
 * Records connection changes, decode failures, failed commands and crashes.
 * Never pass passwords, tokens, keys or MQTT credentials to [log].
 */
object DiagLog {
    private const val MAX_BYTES = 256 * 1024
    private const val TAG = "PowerHub"

    private var file: File? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "diag.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log("CRASH", "uncaught in ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun log(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, "[$tag] $message", error) else Log.i(TAG, "[$tag] $message")
        val f = file ?: return
        val entry = buildString {
            append(time.format(Date())).append(" [").append(tag).append("] ").append(message).append('\n')
            if (error != null) append(stackTrace(error))
        }
        runCatching {
            if (f.length() > MAX_BYTES) {
                // Keep the newest half so the log never grows without bound.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            f.appendText(entry)
        }
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString().lineSequence().take(40).joinToString("\n", postfix = "\n")
    }

    @Synchronized
    fun read(): String = runCatching { file?.readText() }.getOrNull().orEmpty()

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }

    fun lineCount(): Int = read().count { it == '\n' }

    /** Copies the log with a device header into cache and returns a share intent for it. */
    fun shareIntent(context: Context): Intent {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val header = "PowerHub $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n"
        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        val out = File(dir, "PowerHub-log.txt").apply { writeText(header + read()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diag", out)
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Журнал PowerHub $version")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
