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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Runs on a managed background executor to process print jobs:
 * - N-up PDF merging
 * - Streams output to Downloads/TwoUpPrint/ via MediaStore (Android 10+) or SAF / direct file I/O.
 */
class PrintJobHandler(
    private val context: Context,
    private val job: PrintJob,
    private val documentFd: ParcelFileDescriptor,
    private val destinationUri: Uri?,
    private val layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
    private val fileName: String = "nup_output.pdf",
    private val addTextContrast: Boolean = false,
    private val enableLinks: Boolean = false,
    private val bestFit: Boolean = false,
    private val marginTopMm: Int = 0,
    private val marginBottomMm: Int = 0,
    private val marginLeftMm: Int = 0,
    private val marginRightMm: Int = 0,
    private val autoTrimSlideBorders: Boolean = false,
    private val isPresentationSmart: Boolean = false,
    private val spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
    private val targetPaper: TargetPaperSize = TargetPaperSize.A4
) : Runnable {

    companion object {
        private const val CHANNEL_ID = "2up_print_channel"
        private const val NOTIF_ID = 2001

        private val backgroundExecutor: ExecutorService = Executors.newFixedThreadPool(
            2,
            ThreadFactory { runnable ->
                Thread(runnable, "NUpPrintJobWorker").apply {
                    isDaemon = true
                }
            }
        )
    }

    /**
     * Dispatches the print job to the managed background executor pool.
     */
    fun start() {
        backgroundExecutor.execute(this)
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

            val writeOutput: (OutputStream) -> Unit = { output ->
                tempSource.inputStream().use { input ->
                    if (layout.isSplit) {
                        PdfSplitter.splitPages(
                            input, output, layout, addTextContrast, enableLinks,
                            marginTopMm, marginBottomMm, marginLeftMm, marginRightMm,
                            targetPaper
                        ) { current, total ->
                            updateProgressNotification(current, total, false)
                        }
                    } else {
                        PdfMerger.mergeNUp(
                            input, output, layout, addTextContrast, enableLinks, bestFit,
                            marginTopMm, marginBottomMm, marginLeftMm, marginRightMm,
                            autoTrimSlideBorders, isPresentationSmart,
                            spaceDistributionMode,
                            targetPaper
                        ) { current, total ->
                            updateProgressNotification(current, total, false)
                        }
                    }
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

                outStream.use { writeOutput(it) }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: Use MediaStore to write to Downloads (no permissions needed)
                val mimeType = "application/pdf"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TwoUpPrint")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                ) ?: throw IllegalStateException("Could not create MediaStore entry")
                outStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open MediaStore output stream")
                resultUri = uri

                outStream.use { writeOutput(it) }

                val updateValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
                displayLocation = "Downloads/TwoUpPrint/$fileName"
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

                outStream.use { writeOutput(it) }
            }

            tempSource.delete()

            mainHandler.post {
                if (job.isStarted) {
                    job.complete()
                }
            }

            showCompleteNotification(displayLocation, resultUri)
            Log.i("TwoUpPrint", "Print job written to $displayLocation (addTextContrast=$addTextContrast)")
        } catch (e: Exception) {
            Log.e("TwoUpPrint", "Failed to process print job", e)
            val errorMessage = e.message ?: "Print processing failed"
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
        val titleText = if (layout.isSplit) {
            "Splitting pages (1→${layout.cols * layout.rows})..."
        } else {
            "Generating ${layout.cols}×${layout.rows} PDF..."
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(titleText)
            .setContentText(if (indeterminate) "Preparing document..." else "Processing page $current of $total")
            .setProgress(total, current, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        notificationManager.notify(NOTIF_ID, builder.build())
    }

    private fun showCompleteNotification(location: String, fileUri: Uri) {
        val mimeType = "application/pdf"
        val title = if (layout.isSplit) {
            "Split (${layout.cols}×${layout.rows}) PDF Saved: $fileName"
        } else {
            "${layout.cols}×${layout.rows} PDF Saved: $fileName"
        }
        val actionText = "Open PDF"

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
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
            .setContentTitle(title)
            .setContentText("Saved to $location — Tap to open")
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_directions,
                actionText,
                pendingIntent
            )
            .setOngoing(false)
            .setAutoCancel(true)

        notificationManager.notify(NOTIF_ID, builder.build())
    }
}
