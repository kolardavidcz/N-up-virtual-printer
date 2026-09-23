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

        // 1. Default A4 paper size, strictly Portrait (8268 x 11693 mils)
        val sizeA4Portrait = PrintAttributes.MediaSize.ISO_A4.asPortrait()

        // 2. "Presentation Smart" option in Samsung print dialog:
        // When selected, the print engine automatically processes the document in Landscape
        // on a standard A4 sheet regardless of the spooler's orientation toggle setting.
        val sizePresentationSmart = PrintAttributes.MediaSize(
            "MEDIA_PRESENTATION_SMART",
            "Presentation Smart",
            8268,
            11693
        )

        val capabilities = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(sizeA4Portrait, true)          // Default: ISO A4 (Portrait)
            .addMediaSize(sizePresentationSmart, false)  // Smart mode for presentations
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
