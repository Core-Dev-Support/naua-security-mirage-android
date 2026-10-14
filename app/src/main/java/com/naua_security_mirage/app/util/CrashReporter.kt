package com.naua_security_mirage.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {

    private const val DIR = "crashes"
    private const val MAX_FILES = 5
    private const val MAX_REPLAY_CHARS = 6000

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(context.applicationContext, thread, throwable)
            } catch (_: Throwable) {
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
            }
        }
    }

    fun replayPending(context: Context) {
        try {
            val dir = File(context.filesDir, DIR)
            val files = dir.listFiles()?.sortedBy { it.name } ?: return
            for (file in files) {
                try {
                    val text = file.readText().take(MAX_REPLAY_CHARS)
                    AppLogger.e("CrashReporter", "Падение из прошлого запуска:\n$text")
                } catch (_: Throwable) {
                } finally {
                    try {
                        file.delete()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR)
        dir.mkdirs()
        val stamp = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(Date())
        val report = buildString {
            append("time=").append(stamp).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" Android ").append(Build.VERSION.RELEASE)
                .append(" API ").append(Build.VERSION.SDK_INT).append('\n')
            append(android.util.Log.getStackTraceString(throwable)).append('\n')
        }
        File(dir, "crash_$stamp.log").writeText(report)
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        if (files.size > MAX_FILES) {
            files.take(files.size - MAX_FILES).forEach {
                try {
                    it.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
