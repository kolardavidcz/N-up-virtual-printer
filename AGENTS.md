# AGENTS.md - Developer & AI Agent Guidelines for N-Up Print

This guide is written 100% for AI coding agents and developers working on the **N-Up Print** codebase.

---

## 1. Versioning & Release Rules (MANDATORY)

1. **Strict Non-Overwriting Policy**:
   - **NEVER** re-release an APK or overwrite an existing version number.
   - Every single build/release must increment `versionCode` by at least 1 and declare a new, unique `versionName`.
   - All release APKs are preserved in the `releases/` folder (e.g., `releases/nup-print-v2.0.0.apk`). Never delete or overwrite previous version APKs.

2. **Version Number Format**:
   - Format: `MARKETING_NUMBER.FEATURE_ADDED.BUG_FIX` (e.g. `2.0.0`).
   - `MARKETING_NUMBER` (Major): Large milestones, major redesigns, paradigm shifts (e.g. `2.x.x`).
   - `FEATURE_ADDED` (Minor): New feature, new layout option, new toggle, new capability (e.g. `2.1.0`).
   - `BUG_FIX` (Patch): Bug fixes, edge case corrections, minor layout adjustments (e.g. `2.1.1`).

3. **Workspace Path & Desktop Cleanliness**:
   - The authoritative project location is: `C:\Users\kolar\Desktop\windows_native_projects\TwoUpPrintv_v2`.
   - **NEVER** create junctions, clones, scratch directories, or files directly on the Windows Desktop (`C:\Users\kolar\Desktop\`). All windows-native projects must stay cleanly contained in `windows_native_projects\`.

---

## 2. Architecture & Code Map

### Core Components
- **`app/src/main/java/com/example/twoupprint/`**:
  - `TwoUpPrintService.kt`: Extends Android `PrintService`. Handles incoming print jobs (`onPrintJobQueued`), launches background `PrintJobHandler` processing, and manages status notifications.
  - `TwoUpDiscoverySession.kt`: Extends `PrinterDiscoverySession`. Advertises virtual printer presets (2x1, 1x2, 2x2, 2x3, 2x4, notewise) to the Android/Samsung Print Spooler. Sets media sizes (`sizeSmart` in Portrait $8268 \times 11693$ mils so default spooler orientation initializes in Portrait).
  - `PdfMerger.kt`: Pure vector-level PDF transformation engine using Apache PDFBox. Computes scale, translation, rotation, and slot placement for N-up layouts.
  - `PdfContentTrimmer.kt`: Scans PDF page content streams to detect true content bounding boxes, trimming empty letterboxes (e.g. 16:9 slides centered on A4 sheets).
  - `PdfLinkEngine.kt`: Extracts and recalculates bounding boxes for annotations / hyperlinks and regex URL patterns to keep links clickable after N-up scaling.
  - `PrintJobHandler.kt`: Background processing thread handling N-up PDF rendering, direct .notewise virtual printer export, and companion .notewise notebook generation.
  - `MainActivity.kt`: Material 3 Settings UI with dynamic layout cards, orientation toggles, per-side margins, high contrast switches, Notewise companion export toggle, phantom printer cleanup, and custom grid dialogs.
  - `LayoutRegistry.kt`: Central registry managing built-in presets, custom layouts, and persistence preferences.
- **`app/src/main/java/com/example/twoupprint/notewise/`**:
  - `NotewiseProtobuf.kt`: Zero-dependency Protobuf wire encoder and decoder supporting Wire 0 (Varint), Wire 1 (64-bit Fixed/Double), Wire 2 (Length-delimited/Bytes/Strings/Submessages), and Wire 5 (32-bit Fixed/Float).
  - `NotewiseNotebookBuilder.kt`: Assembles Notewise ZIP archives (`note`, `page/<id>`, `image/<id>`), enforces strict 76-character Base64 MIME line wrapping with trailing newline, sequential zero-based indexing on `field 10`, 24-character alphanumeric random IDs, and SHA-256 WebP image asset deduplication.
  - `NotewisePdfConverter.kt`: High-performance PDF to Notewise converter extracting text blocks, paragraph grouping, bullet points, typographic font sizing, and 300 DPI coordinate mapping.

### Key Layout & UI Files
- `app/src/main/res/layout/activity_main.xml`: Main settings screen. Standardized compact layout designed to fit on a single portrait tablet screen without bloated padding.
- `app/src/main/res/values/colors.xml` & `strings.xml`: Dark theme Material 3 palette and strings.
- `app/src/main/res/drawable/ic_layout_notewise.xml`: Vector icon for the Notewise Notebook virtual printer.

---

## 3. Development, Testing & Verification Workflows

### Environment Prerequisites (Windows)
- **JDK 17**: `C:\Users\kolar\.jdks\jbr-17.0.14`
- **Gradle 8.13**: `F:\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat`
- **ADB**: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`
- **Connected Physical Device**: Samsung Galaxy Tab (`R52Y603KQAB`) or any USB-connected Android device.

### Build & Test Commands
Always set `JAVA_HOME` before invoking Gradle:
```powershell
$env:JAVA_HOME="C:\Users\kolar\.jdks\jbr-17.0.14"
& "F:\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" testDebugUnitTest assembleDebug
```

### Deploying & Verifying on Device
```powershell
# 1. Copy to releases
Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "releases\nup-print-v<VERSION>.apk"

# 2. Install on device
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s R52Y603KQAB install -r "releases\nup-print-v<VERSION>.apk"

# 3. Launch App
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s R52Y603KQAB shell am start -n com.example.twoupprint/.MainActivity

# 4. Pull screenshot for verification
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s R52Y603KQAB shell screencap -p /sdcard/screen.png
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s R52Y603KQAB pull /sdcard/screen.png "screen_verify.png"
```

### Git Policy
- All commits must be local (`git commit`). Never run `git push`.
- Ensure working tree is clean before finishing a task.
