package com.example.twoupprint

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.printservice.PrintJob
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs off the main thread to process the PDF document.
 * Merges pages into a true vector or pure 1-bit B&W PDF and streams
 * the result to Downloads/TwoUpPrint/ via MediaStore (Android 10+) or direct file I/O.
 */
class PrintJobHandler(
    private val context: Context,
    private val job: PrintJob,
    private val documentFd: ParcelFileDescriptor,
    private val destinationUri: Uri?,
    private val layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
    private val fileName: String = "nup_output.pdf",
    private val colorMode: ColorProcessingMode = ColorProcessingMode.COLOR,
    private val bwAlgorithm: BwBinarizer.BwAlgorithm = BwBinarizer.BwAlgorithm.TEXT_BOOSTER
) : Thread("NUpPrintJob") {

    companion object {
        private const val CHANNEL_ID = "2up_print_channel"
        private const val NOTIF_ID = 2001
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun run() {
        createNotificationChannel()

        try {
            updateProgressNotification(0, 100, true)

            // Initialize PDFBox resource loader
            PdfMerger.init(context)

            val tempSource = File.createTempFile("twoup_src", ".pdf", context.cacheDir)
            documentFd.fileDescriptor.let { descriptor ->
                java.io.FileInputStream(descriptor).use { input ->
                    tempSource.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val outStream: OutputStream
            val resultUri: Uri
            val displayLocation: String

            if (destinationUri != null) {
                // User provided a specific destination via SAF
                outStream = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw IllegalStateException("Could not open destination stream")
                resultUri = destinationUri
                displayLocation = "Selected Location"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: Use MediaStore to write to Downloads (no permissions needed)
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TwoUpPrint")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                ) ?: throw IllegalStateException("Could not create MediaStore entry")
                outStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open MediaStore output stream")
                resultUri = uri

                // Mark as complete after writing
                outStream.use { output ->
                    tempSource.inputStream().use { input ->
                        PdfMerger.mergeNUp(input, output, layout, colorMode, bwAlgorithm) { current, total ->
                            updateProgressNotification(current, total, false)
                        }
                    }
                }
                tempSource.delete()

                val updateValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
                displayLocation = "Downloads/TwoUpPrint/$fileName"

                mainHandler.post {
                    if (job.isStarted) {
                        job.complete()
                    }
                }
                showCompleteNotification(displayLocation, resultUri)
                Log.i("TwoUpPrint", "N-up PDF written to $displayLocation (colorMode=$colorMode)")
                return
            } else {
                // Legacy: direct file I/O to Downloads
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "TwoUpPrint"
                )
                if (!dir.exists()) dir.mkdirs()
                val outputFile = File(dir, fileName)
                outStream = outputFile.outputStream()
                resultUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outputFile
                )
                displayLocation = "Downloads/TwoUpPrint/$fileName"
            }

            outStream.use { output ->
                tempSource.inputStream().use { input ->
                    PdfMerger.mergeNUp(input, output, layout, colorMode, bwAlgorithm) { current, total ->
                        updateProgressNotification(current, total, false)
                    }
                }
            }
            tempSource.delete()

            mainHandler.post {
                if (job.isStarted) {
                    job.complete()
                }
            }

            showCompleteNotification(displayLocation, resultUri)
            Log.i("TwoUpPrint", "N-up PDF written to $displayLocation (colorMode=$colorMode)")
        } catch (e: Exception) {
            Log.e("TwoUpPrint", "Failed to produce N-up PDF", e)
            val errorMessage = e.message ?: "N-up merge failed"
            notificationManager.cancel(NOTIF_ID)
            mainHandler.post {
                if (job.isStarted) {
                    job.fail(errorMessage)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "N-Up Print Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while generating N-up PDF files"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateProgressNotification(current: Int, total: Int, indeterminate: Boolean) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Generating ${layout.cols}×${layout.rows} PDF...")
            .setContentText(if (indeterminate) "Preparing document..." else "Processing page $current of $total")
            .setProgress(total, current, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        notificationManager.notify(NOTIF_ID, builder.build())
    }

    private fun showCompleteNotification(location: String, fileUri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("${layout.cols}×${layout.rows} PDF Saved: $fileName")
            .setContentText("Saved to $location — Tap to open")
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_directions,
                "Open PDF",
                pendingIntent
            )
            .setOngoing(false)
            .setAutoCancel(true)

        notificationManager.notify(NOTIF_ID, builder.build())
    }
}
