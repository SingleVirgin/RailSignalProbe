package com.railsignal.probe

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃/异常日志落盘：写入应用外部私有目录 crash_log.txt，
 * 无需连电脑 logcat 即可取回崩溃原因（文件管理器或 adb pull 均可）。
 */
object CrashLogger {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun write(context: Context, error: Throwable) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, "crash_log.txt").appendText(
                "\n==== ${fmt.format(Date())} ====\n" +
                    error.stackTraceToString() + "\n"
            )
        }
    }

    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write(context, e)
            prev?.uncaughtException(t, e)
        }
    }
}
