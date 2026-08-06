# N-Up Print for Android

[![Release](https://img.shields.io/badge/release-v1.0.0-blue.svg)](https://github.com/kolardavidcz/N-up-virtual-printer/releases/tag/v1.0.0)
[![API](https://img.shields.io/badge/API-26%2B-green.svg)](https://android-arsenal.com/api?level=26)
[![License](https://img.shields.io/badge/License-Apache_2.0-orange.svg)](LICENSE)

**N-Up Print** is an Android Print Service plugin that converts web pages, documents, and photos into vector-accurate N-up grid layouts (2×1, 1×2, 2×2, 2×3, 2×4, and custom X×Y configurations) directly from any app's system print dialog.

---

## 📥 Download

- **Latest Release (v1.0.0)**: [Download N-Up Print v1.0.0 APK](https://github.com/kolardavidcz/N-up-virtual-printer/releases/download/v1.0.0/nup-print-v1.0.0.apk)
- **Local Mirror**: [releases/nup-print-v1.0.0.apk](releases/nup-print-v1.0.0.apk)

---

## 🖼️ Screenshots

| Application Settings | System Print Spooler Integration |
|:---:|:---:|
| ![N-Up Print App UI](docs/screenshots/app_screanshoot.jpg) | ![System Print Spooler Virtual Printers](docs/screenshots/virtual_printers.jpg) |

---

## ✨ Features

- 📄 **True Vector PDF Output**: Merges pages into multi-up layouts while preserving 100% selectable text, crisp fonts, and resolution-independent vector shapes (no bitmap rasterization).
- 🧩 **Custom X:Y Grid Creator**: Add your own grid configurations (e.g. 3×3, 4×4, 1×3, 3×5) on the fly with smart automatic A4 orientation suggestions (Landscape vs. Portrait).
- 🎨 **Monocolor Print Icons**: Features clean white-on-transparent preview icons for all virtual printer layouts inside Android's system print dialog.
- 🎛️ **Independent Layout Toggles**: Enable or disable specific printer layouts in app settings so disabled printers don't clutter your system print menu.
- 🏷️ **Automatic Title Extraction**: Automatically names output files based on web page titles or document labels (e.g., `Article Title_2x1.pdf`).
- 🔔 **Instant Save Location Popup**: Save directly to a preselected folder or enable high-priority notification popups to choose a destination per print job.
- ⚡ **Samsung One UI Reliability**: Built-in battery optimization exemption helper ensures background print services stay alive and discoverable at all times.

---

## 🛠️ Tech Stack & Architecture

- **Language**: Kotlin 2.0
- **UI Framework**: Native Material Design 3 (Dark Theme)
- **PDF Engine**: Apache PDFBox Android (`com.tom-roush:pdfbox-android:2.0.27.0`)
- **Android Framework**: `PrintService`, `PrinterDiscoverySession`, Storage Access Framework (`ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE`)
- **Compatibility**: Android 8.0+ (API Level 26+)

---

## 🚀 Build & Installation

### 1-Click USB Debug Update (Windows)

To automatically compile, install over USB ADB, and launch on your connected Android device:

- **Double-click `update_app.bat`** from Windows File Explorer, OR
- **Run `update_app.ps1` in PowerShell**:
  ```powershell
  .\update_app.ps1
  ```

*The scripts auto-close after 3 seconds upon successful update.*

---

## 📜 License

Distributed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
