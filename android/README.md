# MarkItDown Local (Android)

An offline Android port of this repo's PDF and image converters. It turns PDFs, photos
and screenshots into Markdown on the phone itself.

## Privacy

- The APK declares **no `INTERNET` permission**, so Android blocks every network socket
  the app or its libraries try to open. You can check this after building:
  `aapt dump permissions app-release.apk`.
- Backups are off (`allowBackup=false` plus `dataExtractionRules`), so nothing goes to
  Google Drive backup or device transfer.
- There are no Google ML Kit, Firebase or analytics libraries. OCR uses Tesseract
  (native, Apache-2.0) and PDF parsing uses PdfBox-Android (pure Java, Apache-2.0).
- Temporary files (the copied PDF, camera shots, share exports) live in the app's
  private cache and are cleared after use or on the next launch.

## What it does

| Input | How |
|---|---|
| PDF with a text layer | PdfBox extracts lines with font size and weight. Headings come from font size, bold lines become `**bold**`, bullet and numbered lists are detected, wrapped lines and hyphenation are reflowed, page numbers are dropped. markitdown's MasterFormat `.1` fix is ported. |
| Scanned PDF pages | Any page with no usable text (or a garbled one) is rendered at ~300 DPI and OCR'd with Tesseract. Mixed PDFs work page by page. |
| Images (JPG/PNG/WebP/HEIC…) | EXIF rotation is fixed, then Tesseract OCR runs. Metadata lines (ImageSize, DateTimeOriginal, GPSPosition…) are emitted like markitdown's `ImageConverter`. |
| Camera | Takes a photo with the system camera app and converts it. The photo is deleted afterwards. |
| Share / Open with | Share a PDF or images from Files, Gallery or mail straight into the app. |

Output can be saved as `.md` (Storage Access Framework), copied, shared, or previewed as
rendered Markdown.

### OCR languages

English (`eng`) is bundled. To add another language, e.g. Tamil, copy `tam.traineddata`
from <https://github.com/tesseract-ocr/tessdata_fast> to the phone, then use
**Languages → Import .traineddata**. You can select several languages at once
(`eng + tam`). To bundle languages at build time instead:

```
./gradlew assembleRelease -PtessdataLanguages=eng,tam -PtessdataVariant=best
```

## Build

Requirements: JDK 17+ and the Android SDK (platform 35). The build downloads the
Tesseract model once from GitHub and packs it into the APK. The app itself never downloads anything.

```
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # or set ANDROID_HOME
./gradlew testReleaseUnitTest assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the local debug key so it sideloads directly. Debug keys
differ between machines, so an APK built somewhere else won't install over an existing
copy until you uninstall that copy. The `android-app` GitHub Actions workflow builds the
same APK and uploads it as an artifact.

ABIs default to `arm64-v8a,armeabi-v7a` (all current phones). For an x86 emulator, pass
`-Pabis=x86_64`.

## Layout

```
app/src/main/java/com/markitdown/local/
  converter/DocumentConverter.kt   interface, mirrors markitdown's DocumentConverter
  converter/MarkItDown.kt          picks a converter per file, joins the results
  converter/PdfConverter.kt        PdfBox text layer + per-page OCR fallback
  converter/PdfLineStripper.kt     PdfBox stripper that keeps font size, bold and paragraphs
  converter/ImageConverter.kt      EXIF metadata + OCR
  converter/MarkdownFormatter.kt   lines -> Markdown (pure Kotlin, unit tested)
  ocr/OcrEngine.kt                 Tesseract wrapper and model management
  ui/                              Jetpack Compose UI
```

The unit tests run the PDF converter against the upstream fixtures in
`packages/markitdown/tests/test_files` (`test.pdf`, `masterformat_partial_numbering.pdf`).
