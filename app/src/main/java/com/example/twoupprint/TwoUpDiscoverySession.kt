package com.example.twoupprint

import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.CustomPrinterIconCallback
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession

/**
 * Registers virtual printers for all enabled [PrintLayout] configurations,
 * dynamically rendering preview icons and forcing the configured subpages default orientation
 * (single media size capability, similar to A4-only restriction).
 */
class TwoUpDiscoverySession(private val service: PrintService) : PrinterDiscoverySession() {

    private fun buildAllPrinters(): List<PrinterInfo> =
        LayoutRegistry.getEnabledLayouts(service).map { mode -> buildPrinterInfo(mode) }

    private fun buildPrinterInfo(mode: PrintLayout): PrinterInfo {
        val id: PrinterId = service.generatePrinterId(mode.printerId)

        // 1. "Smart" paper size (Default):
        // Automatically optimizes presentation slides, margins, and placement onto an A4 sheet.
        // Orientation matches the in-app layout configuration (Landscape for 2x1/2x2, Portrait for 1x2/2x3/2x4/notewise).
        val sizeSmart = if (mode.landscape) {
            PrintAttributes.MediaSize(
                "MEDIA_SMART",
                "Smart",
                11693,
                8268
            )
        } else {
            PrintAttributes.MediaSize(
                "MEDIA_SMART",
                "Smart",
                8268,
                11693
            )
        }

        val capabilities = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(sizeSmart, true)               // Default: "Smart" (honors layout orientation)
            .addResolution(
                PrintAttributes.Resolution("nup_res", "300dpi", 300, 300),
                true
            )
            .setColorModes(
                PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_COLOR
            )
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .build()

        val builder = PrinterInfo.Builder(
            id,
            mode.displayName,
            PrinterInfo.STATUS_IDLE
        )
            .setIconResourceId(mode.iconResId)
            .setHasCustomPrinterIcon(true)
            .setCapabilities(capabilities)

        return builder.build()
    }

    override fun onRequestCustomPrinterIcon(
        printerId: PrinterId,
        cancellationSignal: CancellationSignal,
        callback: CustomPrinterIconCallback
    ) {
        val localId = printerId.localId
        val layout = LayoutRegistry.findLayoutById(service, localId)
        val bitmap = LayoutIconGenerator.generateIconBitmap(layout)
        val icon = Icon.createWithBitmap(bitmap)
        callback.onCustomPrinterIconLoaded(icon)
    }

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        addPrinters(buildAllPrinters())
    }

    override fun onStopPrinterDiscovery() { }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        val knownIds = LayoutRegistry.getEnabledLayouts(service).map { service.generatePrinterId(it.printerId) }.toSet()
        if (printerIds.any { it in knownIds }) {
            addPrinters(buildAllPrinters())
        }
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        val knownIds = LayoutRegistry.getEnabledLayouts(service).map { service.generatePrinterId(it.printerId) }.toSet()
        if (printerId in knownIds) {
            addPrinters(buildAllPrinters())
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) { }

    override fun onDestroy() { }
}
