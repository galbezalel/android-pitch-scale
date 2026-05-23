package com.example.pitchscale

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Thread.UncaughtExceptionHandler

class CrashHandler(private val context: Context) : UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private var isInitialized = false

        fun init(context: Context) {
            if (!isInitialized) {
                CrashHandler(context.applicationContext)
                isInitialized = true
            }
        }
    }

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            val stackTrace = writer.toString()
            Log.e("CrashHandler", "CRASH DETECTED: $stackTrace")

            val logDir = context.getExternalFilesDir(null)
            if (logDir != null) {
                val file = File(logDir, "crash_log.txt")
                file.writeText("Time: ${System.currentTimeMillis()}\nThread: ${thread.name}\n\n$stackTrace")
            }
        } catch (e: Exception) {
            Log.e("CrashHandler", "Failed to write crash log: ", e)
        }

        defaultHandler?.uncaughtException(thread, throwable)
    }
}
