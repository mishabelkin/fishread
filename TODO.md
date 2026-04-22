# Read! Project TODO

## Next concrete tasks

Polish select/copy mode.
- make sure text selection, tap-to-read, and long-press bookmarking stay easy to understand
- consider whether selection should also be available from history/bookmark previews or only in reader views

Stabilize auto-follow in the reader
- verify `Current`, back, and seek all return to the highlighted sentence cleanly
- reduce any remaining jitter during automatic scrolling

Keep improving citation skipping for read-aloud
- verify numeric, alphanumeric, `+`-delimited, and superscript citation forms on real documents
- add examples to tests whenever a new citation pattern shows up

Tune PDF extraction against real messy examples
- fix headers/footers leaking into text
- improve footnote separation
- reduce paragraph merging/cutoff issues

Tighten manual reading/scroll feel
- make sure scrolling, tapping, and long-press bookmarking do not fight each other
- smooth out the reading experience on long documents

Add a small settings area
- clear cache
- maybe toggle citation muting / read-clean behavior later
- give the app a better place for utility controls

## Current focus

This app is already a strong personal-use reader for:

- local PDFs
- web articles
- bookmarks
- history and `To read`
- read-aloud with lock-screen controls

The most important remaining work is stability and content quality, not large new features.

## High-priority stability work

1. Reader auto-follow stability
- keep the highlighted sentence visible without jitter
- reduce occasional `Current` button inconsistencies
- make seek/back/current all use one stable follow path

2. Reader touch behavior
- make scrolling feel natural even on dense text
- keep tap-to-read and long-press bookmark behavior predictable
- avoid accidental bookmark creation while moving through text

3. Citation/reference cleanup
- keep improving citation stripping for TTS
- cover more citation label styles such as:
  - `[12, 13]`
  - `[abc23, abc24]`
  - `[abc+32, abc+33]`
  - superscript footnote-style markers

4. Playback robustness
- verify pause/resume/seek behavior on real devices
- make sure highlighted text and playback position always stay aligned
- keep lock-screen controls stable on Pixel devices

## Content quality improvements

### PDF extraction

- improve paragraph reconstruction
- better separate:
  - authors
  - affiliations
  - footnotes
  - headers/footers
- reduce cases where section text is cut or merged incorrectly
- tune against real problematic PDFs as they appear

### Web extraction

- better remove page chrome:
  - nav
  - share bars
  - cookie banners
  - related links
- improve article-body detection across more sites
- continue site-specific tuning when needed

## Reader UX improvements

1. Skim mode
- keep refining which passages are shown
- make section-opening behavior more intuitive
- possibly add a `skim summary` card at the top

2. Bookmarks
- keep bookmark add/remove flows simple
- improve bookmark visibility in the document
- consider pinning or starring important bookmarks

3. Reading navigation
- continue refining the right-side progress rail
- consider tappable section markers on the rail
- consider showing heading positions on long documents

## Interesting future features

1. Quote + note capture
- save a sentence or paragraph as a quote
- add a short note
- keep source/position attached

2. Cross-document notebook
- searchable saved quotes and notes
- useful for research rather than only reading

3. Smart skim / study mode
- show:
  - title
  - intro
  - headings
  - conclusion
  - saved highlights

4. Queue listening
- let `To read` behave like a listening queue
- move from one document to the next

5. Document Q&A / discussion
- ask questions about the current document
- allow follow-up discussion without losing the source context
- answers should point back to the relevant paragraph/section instead of sounding free-floating

6. LLM summary generation
- generate grounded summaries of the current document
- support a few useful summary modes:
  - short overview
  - section-by-section summary
  - key points only
- summaries should stay tied to the document text rather than sounding generic

7. RSS subscriptions
- subscribe to article feeds from favorite sites/blogs
- surface new items directly into `To read`
- allow quick add/remove of feeds and lightweight feed management
- eventually support reading/listening queues built from RSS updates

## LLM roadmap notes

- keep `cleanup` and `question answering` as separate pipelines
- keep `summarization` separate from both `cleanup` and `question answering`
- use canonical extracted text as the grounding source, even if presentation text is cleaned
- do paragraph/chunk retrieval first, then answer only from retrieved chunks
- prefer source-linked answers:
  - quote/snippet
  - section heading
  - jump-back location in the document
- summaries should cite or link back to the relevant section/chunk, especially for longer documents
- start with `current document only` before attempting cross-document chat
- web first is fine, but PDF support will matter a lot for this feature
- add model switching so cleanup models and Q&A models can evolve independently
- consider whether summary generation should reuse the Q&A model or have its own profile/model
- think about on-device cache strategy for:
  - chunk embeddings or retrieval metadata
  - conversation state tied to a document
  - generated summaries tied to model + prompt version
- keep a strict non-goal early on:
  - no summarizing hallucinations
  - no answers that are not grounded in the document text

## Maintenance and hardening

1. MainActivity cleanup
- keep reducing size and state complexity
- continue extracting reader-specific UI pieces into separate files

2. Test coverage
- add more tests around:
  - citation stripping
  - bookmark mapping
  - extraction heuristics
  - playback progress/seek behavior

3. Release prep
- create a proper signed release build
- clean final app metadata/resources
- verify install/update flow outside debug deploys

## Nice-to-have ideas

- export/import bookmarks and reading history
- optional local backup
- better visual distinction for metadata vs main text
- heading-aware jump list for very long papers
- more polished settings screen

## Working principle

When choosing what to do next:

1. Prefer fixes that improve real reading comfort.
2. Prefer stability over feature count.
3. Tune extraction against real examples rather than abstract heuristics.
4. Keep the app simple enough that it remains pleasant to use.
