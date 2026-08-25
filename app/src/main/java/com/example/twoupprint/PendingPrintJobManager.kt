package com.example.twoupprint

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.printservice.PrintJob
import androidx.core.app.NotificationCompat

/**
 * Thread-safe manager handling queued print jobs when prompt-per-print setting is active.
 * Displays a high-priority heads-up notification popup instantly upon printing.
 */
object PendingPrintJobManager {

    private const val CHANNEL_ID = "nup_prompt_popup_channel"
    private const val NOTIF_ID = 8801
    private const val TIMEOUT_MS = 120_000L // 2 minute timeout

    private var activeJob: PrintJob? = null
    private var activeFd: ParcelFileDescriptor? = null
    private var activeLayout: PrintLayout = LayoutRegistry.builtInLayouts.first()
    private var activeFileName: String = "nup_output.pdf"
    private var activeColorMode: ColorProcessingMode = ColorProcessingMode.COLOR
    private var activeBwAlgorithm: BwBinarizer.BwAlgorithm = BwBinarizer.BwAlgorithm.TEXT_BOOSTER

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        activeJob?.let { job ->
            if (job.isStarted) job.fail("Save location selection timed out")
        }
        clearState()
    }

    @Synchronized
    fun enqueuePromptJob(
        context: Context,
        printJob: PrintJob,
        documentFd: ParcelFileDescriptor,
        layout: PrintLayout,
        fileName: String,
        colorMode: ColorProcessingMode = ColorProcessingMode.COLOR,
        bwAlgorithm: BwBinarizer.BwAlgorithm = BwBinarizer.BwAlgorithm.TEXT_BOOSTER
    ) {
        // Cancel previous pending job if any
        cancelPendingJob(context)

        activeJob = printJob
        activeFd = documentFd
        activeLayout = layout
        activeFileName = fileName
        activeColorMode = colorMode
        activeBwAlgorithm = bwAlgorithm

        // Set 2 minute timeout
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        // Show instant high-priority popup notification
        showPromptNotification(context)
    }

    @Synchronized
    fun processDestination(context: Context, uri: Uri?) {
        timeoutHandler.removeCallbacks(timeoutRunnable)

        val job = activeJob
        val fd = activeFd
        val layout = activeLayout
        val fileName = activeFileName
        val colorMode = activeColorMode
        val bwAlgorithm = activeBwAlgorithm

        activeJob = null
        activeFd = null

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)

        if (job == null || fd == null) return

        if (uri != null) {
            PrintJobHandler(
                context.applicationContext, job, fd, uri,
                layout, fileName, colorMode, bwAlgorithm
            ).start()
        } else {
            if (job.isStarted) {
                job.fail("Save location cancelled by user")
            }
        }
    }

    @Synchronized
    fun cancelPendingJob(context: Context) {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        activeJob?.let { job ->
            if (job.isStarted) job.fail("Save location selection cancelled")
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        clearState()
    }

    fun getPendingFileName(): String = activeFileName

    private fun clearState() {
        activeJob = null
        activeFd = null
    }

    private fun showPromptNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Print Save Location Prompt",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority popup asking where to save N-Up PDF"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val promptIntent = Intent(context, SaveDestinationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            promptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Choose Save Location")
            .setContentText("Tap to select folder for $activeFileName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)

        nm.notify(NOTIF_ID, builder.build())
    }
}
