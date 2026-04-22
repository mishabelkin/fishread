# Android Local PDF Reader

Android app that runs entirely on the phone:

- paste a PDF URL and download it into app storage
- pick a local PDF file
- receive a shared URL from another Android app
- extract text on-device with PdfBox-Android
- show a clean reading view with headings and paragraphs
- optionally read the extracted text aloud with Android TTS

## Important note

This environment does not have Java or Gradle installed, so I could not generate a real Gradle wrapper or run an Android build here. The project files are in place, but final sync/build needs to happen in Android Studio.

## Open in Android Studio

1. Open Android Studio.
2. Choose `Open`.
3. Select the `android_local_pdf_reader` folder.
4. Let Android Studio sync the Gradle project.
5. If Android Studio asks to create or repair Gradle wrapper/build files, allow it.
6. If it asks for a Gradle version, use `8.11.1`.
7. Build and run on your Android phone or emulator.

## What is implemented

- direct PDF URL import inside the app
- local PDF file picker
- Android Share target for shared text links
- Android `VIEW` intent handling for PDF files and links
- on-device text extraction with PdfBox-Android
- paragraph and heading formatting for a reading view
- Android TextToSpeech read-aloud button

## What I could not verify here

- Gradle sync
- APK build
- device runtime behavior

Those steps require Java/Gradle or Android Studio, which are not installed in this workspace.

## Dependency baseline

- Android Gradle Plugin `8.9.2`
- Kotlin `2.0.21`
- Compose BOM `2026.02.01`
- `androidx.activity:activity-compose:1.12.4`
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0`
- `com.tom-roush:pdfbox-android:2.0.27.0`
