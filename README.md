# Docs Viewer

An Android 8.0+ offline document viewer prototype. Use **Open** to choose a local file or open a supported file from another app.

- PDF: Android PdfRenderer, one page rendered at a time, with previous/next controls.
- DOCX, XLSX, PPTX and ODT/ODS/ODP: extracts text into a selectable reading view. Layout, images, formulas, rich formatting and sheet coordinates are not faithfully rendered. Spreadsheet and presentation order follows internal file numbers, which may differ from the order displayed by Office. Password-protected files are not supported.
- TXT, Markdown, CSV and common source/text files: plain text. Markdown is not rendered as rich text.
- Legacy DOC/XLS/PPT, EPUB and other formats are not supported. This is not a universal document renderer.

No network permission is requested. PDFs are copied to the app cache to support non-seekable document providers; the temporary copy is removed when another document opens or the activity closes. Text extraction has a 16 MB uncompressed content limit. PDF rendering is paginated, with no zoom or continuous scrolling yet. Speed has not been benchmarked against the PDF viewer.

## Build

Install JDK 17, Android SDK platform 35, Android build tools, and Gradle 8.9. Run `gradle assembleDebug`. The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Alternatively push the project to GitHub and run the included Build APK workflow; download the `DocsViewer-debug` artifact. A debug APK is intended for testing, not distribution as a production release.


## Download a CI build

Open the repository's Actions tab, select a successful Build APK run, and download the DocsViewer-debug artifact. Extract the ZIP and install app-debug.apk on an Android 8.0 or newer device. GitHub may require you to sign in to download artifacts. The workflow also runs on pull requests and can be started manually from the Actions tab.
