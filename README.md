# Docs Viewer

An offline document viewer for Android 8.0+. Use **Browse files** or open a supported file from another app. The reader follows the system light or dark theme. Files picked through Android's file browser can appear in the recent list and reopen at the last page or text section.

- PDF: Android PdfRenderer draws one page at a time. Tap the page count to jump to a page, or use **2×** and **Fit** to switch between views.
- DOCX, XLSX, PPTX and ODT/ODS/ODP: extracts text into a selectable reading view. Layout, images, formulas, rich formatting and sheet coordinates are not faithfully rendered. Spreadsheet and presentation order follows internal file numbers, which may differ from the order displayed by Office. Password-protected files are not supported.
- TXT, Markdown and common source files open as selectable, wrapped text. CSV, TSV and spreadsheet previews can scroll sideways. Long text is split into sections to keep scrolling responsive.
- Legacy DOC/XLS/PPT, EPUB and other formats are not supported. This is not a universal document renderer.

The viewer has no runtime dependencies or network permission. It opens seekable PDFs directly and makes a temporary cache copy only for providers that stream the file. Text extraction has a 16 MB input limit for text or the Office XML parts being read, and a two-million character limit for extracted Office text.

## Build

Install JDK 17, Android SDK platform 35, Android build tools, and Gradle 8.9. Run `gradle assembleDebug`. The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Alternatively push the project to GitHub and run the included Build APK workflow; download the `DocsViewer-debug` artifact. A debug APK is intended for testing, not distribution as a production release.


## Download a CI build

Open the repository's Actions tab, select a successful Build APK run, and download the DocsViewer-debug artifact. Extract the ZIP and install app-debug.apk on an Android 8.0 or newer device. GitHub may require you to sign in to download artifacts. The workflow also runs on pull requests and can be started manually from the Actions tab.
