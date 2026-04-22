# Read! App Summary

## Overview

`Read!` is an Android reading app for opening PDFs and web articles, converting them into a cleaner reading view, and optionally listening to the text with synchronized speech.

Project location:
- `C:\Users\mikha\Desktop\Android\android_local_pdf_reader`

Current Android package:
- `org.read.mobile`

## Core Functionality

### 1. Open documents
The app can open content in several ways:
- paste a PDF URL
- paste a web article URL
- pick a local PDF file from device storage
- open supported links through Android share/open intents
- restore the last opened document on app launch

### 2. Read cleaned text
For both PDFs and web pages, the app converts source content into a structured reader view.

Current cleanup goals and behavior:
- separates main body text from metadata/front matter where possible
- moves footnotes out of the top of the document
- tries to suppress repeated headers/footers in PDFs
- tries to suppress chrome/noise for web pages (navigation, comments, share blocks, etc.)
- preserves headings and paragraphs as reader blocks

### 3. Listen to documents
The app includes sentence-level read-aloud with background playback.

Playback behavior:
- tap text to start reading from that point
- active spoken sentence is highlighted
- current sentence can auto-follow while reading
- manual scrolling temporarily disables follow mode
- `Current` button jumps back to the spoken sentence and resumes follow mode
- Bluetooth / lock-screen controls work through a foreground playback service
- playback continues with screen off
- progress is shown in the bottom controls

### 4. Save reading state
The app persists reading state locally.

Stored locally:
- history of opened PDFs and web pages
- cached extracted documents
- reading progress per document
- bookmarks
- to-read list
- last opened document

## Main Screens and Layout

### Home screen
The home screen is the entry point.

It contains the top input/import area only:
- URL input box
- button to open a URL
- button to pick a local PDF
- fish menu for navigation

The URL box is empty by default.

### Document screen
Once a document is opened, the app switches to a separate reading screen.

Top area:
- top app bar
- fish menu
- `Home` / `Current` style navigation depending on state
- bookmark icon

Main content:
- `Paper details` card if metadata exists
- document summary card with title, source, counts, and actions
- main body text blocks
- footnotes section at the bottom

Right-side progress rail:
- shows approximate position in the document
- can be tapped or dragged to navigate
- marker indicates current location in the document

Floating controls:
- `Current` button when the active spoken sentence is off-screen
- `Top` button to jump to the beginning of the document

Bottom playback bar:
- play / pause
- previous / next spoken section
- speed controls
- playback progress
- stays visible while text scrolls

### History screen
History is separate from the reader.

Behavior:
- split into `PDFs` and `Web pages`
- lists recently opened items
- uses paper/article title instead of raw long URLs when possible
- shows compact bookmark previews under items
- supports reopening and deletion

### Bookmarks screen
Bookmarks are separate from history.

Behavior:
- grouped by document
- supports open, rename, and delete
- bookmark labels prefer headings/subheadings when available
- bookmark items jump back to saved positions in cached documents

### To read screen
`To read` is a deliberate saved list separate from history.

Behavior:
- add current document to `To read`
- remove items
- mark items done / undo
- reopen items using cached content when available

## Bookmarking Behavior

Bookmarks are position-based and sentence-aware.

Current bookmark actions:
- tap top bookmark icon to open the current document's bookmark menu
- long-press the top bookmark icon to toggle bookmark at the current reading position
- long-press directly on text to add/remove a bookmark at that sentence/segment
- haptic feedback confirms text long-press bookmark toggles

Bookmark display:
- bookmarked text is highlighted with a stronger persistent color
- current spoken sentence uses a different highlight color
- bookmarked blocks show a bookmark indicator on the right

## Reader Interaction Model

### Tap on text
- starts read-aloud from the tapped sentence or segment

### Long-press on text
- toggles bookmark for the pressed sentence/segment
- requires a slightly extended hold to reduce accidental triggers
- triggers vibration when the bookmark action fires

### Scrubbing and jumping
- drag the right-side progress rail to navigate through the document
- use `Current` to jump back to the active spoken sentence
- use `Top` to jump to the top of the document

## Content Support

### PDFs
Supported well:
- local PDFs
- direct PDF URLs

PDF pipeline currently tries to:
- extract readable text and headings
- separate metadata/front matter from body text
- move footnotes to the end
- suppress repeated page headers/footers

### Web pages
Supported for standard article-like pages.

Web pipeline currently tries to:
- identify the main article container
- strip obvious chrome/noise
- extract headings and paragraph text
- support sites like Substack via custom fallback paths

## Visual Identity

Current app name:
- `Read!`

Current fish asset:
- `TuringFish2.png` is used for:
  - launcher icon imagery
  - splash imagery
  - in-app fish imagery

## Main Source Files

Key files in the project:
- `app/src/main/java/org/read/mobile/MainActivity.kt`
  - main Compose UI
  - screen layout
  - reader interactions
  - bookmarking gestures
  - playback controls
- `app/src/main/java/org/read/mobile/PdfReaderViewModel.kt`
  - document opening
  - extraction
  - caching
  - history / bookmarks / to-read persistence
- `app/src/main/java/org/read/mobile/ReaderPlayback.kt`
  - speech segmentation and playback state
- `app/src/main/java/org/read/mobile/ReaderPlaybackService.kt`
  - foreground playback service
  - lock-screen / Bluetooth integration

## Current Product Direction

The app is currently strongest as:
- a personal mobile reading tool for papers and articles
- a cleaner reader for PDFs and supported web pages
- a read-aloud app with persistent position, bookmarks, and saved reading flow

The main remaining quality work is content extraction polish:
- more PDF cleanup for messy academic papers
- more web article extraction cleanup for difficult sites
