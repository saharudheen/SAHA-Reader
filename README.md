# Saha Reader — all-in-one document reader for Android

Open **PDF, Word (.docx), Excel (.xlsx), CSV, text and image** files in one app.
No ads, no internet permission, no third-party libraries — everything is read on the phone.

## Features

| Type | What you get |
|---|---|
| **PDF** | Smooth page scrolling, **pinch-zoom and double-tap zoom** (pages re-render sharp when zoomed), page counter (tap it to jump), "Go to page" |
| **Word (.docx)** | Headings, bold/italic/underline, bullets, alignment, tables, embedded pictures, Tamil/Unicode text |
| **Excel (.xlsx)** | One tab per sheet, column letters + row numbers (sticky), dates shown as dd-MM-yyyy, percentages, TRUE/FALSE |
| **CSV / TSV** | Same spreadsheet grid; handles quotes, commas inside quotes, `;` and tab separators |
| **Text** (.txt, .log, .json, .md, code…) | UTF-8 / UTF-16 (Notepad files), large files shown up to 4 MB |
| **Images** (jpg, png, webp, gif, bmp, heic) | Pinch zoom, pan, double-tap, auto-rotate from camera EXIF, Rotate button |

Also:
- Shows up in **"Open with"** and **"Share"** from File Manager, WhatsApp, Gmail, etc.
- **Swipe left/right to move to the next/previous file in the same folder** — works for files opened with *Browse files* or from another app's "Open with". Needs a one-time permission (tap the orange link on the home screen). Files opened from *Recent files* swipe through the recent list. (⋮ → Next / Previous file also works.)
- **Pinch-zoom** in every viewer (PDF, Word, Excel, CSV, text, images).
- **Recent files** list on the home screen (long-press to remove).
- **Search inside** Word / Excel / CSV / text files, bigger/smaller text.
- **Share** or **Open with another app** from every viewer (⋮ menu).
- Old `.doc` / `.xls` (97-2003) files show a clear message suggesting to save as .docx/.xlsx.

Requires Android 8.0 or newer.

## Build the APK with GitHub (no Android Studio needed)

1. Create a new repository on GitHub, e.g. `saha-reader`.
2. Upload **everything in this folder**, including the hidden `.github` folder
   (on the GitHub website: *Add file → Upload files*, then drag the folder contents in.
   If `.github` doesn't upload, create the file `.github/workflows/build.yml` by hand via
   *Add file → Create new file* and paste its contents).
3. Open the **Actions** tab. The *Build APK* workflow runs automatically on every push
   (or press *Run workflow*). It takes about 4–6 minutes.
4. When it shows a green tick, open the run and download **SahaReader-APK** under *Artifacts*.
   Unzip it to get `app-debug.apk`, copy it to your phone and install
   (allow "Install unknown apps" for your file manager when asked).

If a build ever fails, open the failed step in the Actions log and copy the red error lines.

## Project layout

```
app/src/main/java/com/saha/reader/
  MainActivity.kt        Home screen: Browse files, type shortcuts, recent files, permission link
  FolderFiles.kt         Finds the opened file's real folder and its neighbour files
  Playlist.kt            The set of files you can swipe through
  ZoomRecyclerView.kt    Pinch-zoomable page list for PDFs
  OpenActivity.kt        Receives "Open with" / "Share" from other apps
  FileRouter.kt          Detects file type and opens the right viewer
  BaseViewerActivity.kt  Common viewer menu (share, open with) + recent-files saving
  PdfActivity.kt         PDF viewer (Android's built-in PdfRenderer)
  WebDocActivity.kt      Word / Excel / CSV / text shown in a WebView with search
  DocxParser.kt          .docx -> HTML
  XlsxParser.kt          .xlsx -> HTML (sheet tabs)
  CsvParser.kt           .csv/.tsv -> HTML grid
  ImageActivity.kt       Image viewer
  ZoomImageView.kt       Pinch-zoom image view (used by images and PDF zoom)
  HtmlKit.kt / Recents.kt  Helpers
```

Package name: `com.saha.reader` · Version 1.2
