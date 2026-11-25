# Docs Viewer

An offline document viewer for Android 8.0+. Use **Browse files** or open a supported file from another app. Files picked through Android's file browser can appear in the recent list and reopen at the last page, sheet, slide, or section.

- PDF: Android PdfRenderer draws one page at a time. Tap the page count to jump to a page, or use **2×** and **Fit** to switch between views.
- DOCX and ODT render headings, lists, text styles, tables and embedded raster images in a reflowing reading view.
- XLSX and ODS show named worksheets in order, with a scrollable grid, column and row labels, merged cells and common cell formatting. CSV and TSV use the same grid and handle quoted fields.
- PPTX and ODP show slides on a canvas with positioned text, images and common shapes. Tap the slide count to jump to a slide.
- Markdown renders headings, lists, quotes, code, tables and task lists. HTML shows an offline preview with scripts disabled. JSON and XML have structured source views, and Java, Kotlin, JavaScript, CSS and logs show line numbers. TXT keeps its whitespace and wraps to the screen.
- Office previews approximate the original layouts. Features such as complex charts, macros, animations, formula recalculation, precise Word pagination, and password protection are outside the reader's scope. Legacy DOC/XLS/PPT and EPUB are unsupported.

PDFs open with Android's native renderer. Formatted previews use Android System WebView, CommonMark and jsoup. The app has no network permission. External images, scripts and stylesheets are not loaded by previews. Text inputs are limited to 16 MB, with smaller budgets for complex formatted pages. Office archives have limits for expanded size and preview complexity.

## Build

Install JDK 17, Android SDK platform 35, Android build tools, and Gradle 8.9. Run `gradle assembleDebug`. The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Alternatively push the project to GitHub and run the included Build APK workflow; download the `DocsViewer-debug` artifact. A debug APK is intended for testing, not distribution as a production release.


## Download a CI build

Open the repository's Actions tab, select a successful Build APK run, and download the DocsViewer-debug artifact. Extract the ZIP and install app-debug.apk on an Android 8.0 or newer device. GitHub may require you to sign in to download artifacts. The workflow also runs on pull requests and can be started manually from the Actions tab.
