const pptxgen = require("pptxgenjs");
const {
  warnIfSlideHasOverlaps,
  warnIfSlideElementsOutOfBounds,
} = require("./pptxgenjs_helpers/layout");

const pptx = new pptxgen();
pptx.layout = "LAYOUT_WIDE";
pptx.author = "OpenAI Codex";
pptx.company = "OpenAI";
pptx.subject = "Read! app architecture and pipelines";
pptx.title = "Read! App Architecture";
pptx.lang = "en-US";
pptx.theme = {
  headFontFace: "Aptos Display",
  bodyFontFace: "Aptos",
  lang: "en-US",
};

const COLORS = {
  ink: "17324D",
  subInk: "4A647A",
  canvas: "F6F3ED",
  card: "FFFDF8",
  line: "9DB3C7",
  accent: "0F6C7A",
  accentSoft: "D9EEF0",
  accentAlt: "CFE0F7",
  accentAlt2: "F6DDBB",
  ok: "E5F3E9",
  warn: "F8E7E4",
};

function addSlideFrame(slide, title, subtitle) {
  slide.background = { color: COLORS.canvas };
  slide.addText(title, {
    x: 0.55,
    y: 0.35,
    w: 8.2,
    h: 0.45,
    fontFace: "Aptos Display",
    fontSize: 24,
    bold: true,
    color: COLORS.ink,
    margin: 0,
  });
  if (subtitle) {
    slide.addText(subtitle, {
      x: 0.58,
      y: 0.82,
      w: 10.8,
      h: 0.32,
      fontFace: "Aptos",
      fontSize: 10.5,
      color: COLORS.subInk,
      margin: 0,
    });
  }
  slide.addShape(pptx.ShapeType.line, {
    x: 0.55,
    y: 1.15,
    w: 12.2,
    h: 0,
    line: { color: COLORS.line, width: 1.2 },
  });
}

function addFooter(slide, text = "Read! architecture outline") {
  slide.addText(text, {
    x: 0.55,
    y: 6.95,
    w: 5.2,
    h: 0.18,
    fontFace: "Aptos",
    fontSize: 8.5,
    color: COLORS.subInk,
    margin: 0,
  });
}

function addCard(slide, { x, y, w, h, fill = COLORS.card, line = COLORS.line, radius = 0.12 }) {
  slide.addShape(pptx.ShapeType.roundRect, {
    x, y, w, h,
    rectRadius: radius,
    fill: { color: fill },
    line: { color: line, width: 1.1 },
    shadow: { type: "outer", color: "CED6DE", blur: 1, angle: 45, distance: 1, opacity: 0.12 },
  });
}

function addNode(slide, opts) {
  addCard(slide, {
    x: opts.x,
    y: opts.y,
    w: opts.w,
    h: opts.h,
    fill: opts.fill || COLORS.card,
    line: opts.line || COLORS.line,
  });
  slide.addText(opts.title, {
    x: opts.x + 0.14,
    y: opts.y + 0.11,
    w: opts.w - 0.28,
    h: 0.24,
    fontFace: "Aptos",
    fontSize: opts.titleSize || 12.5,
    bold: true,
    color: COLORS.ink,
    margin: 0,
  });
  if (opts.body) {
    slide.addText(opts.body, {
      x: opts.x + 0.14,
      y: opts.y + 0.39,
      w: opts.w - 0.28,
      h: opts.h - 0.48,
      fontFace: "Aptos",
      fontSize: opts.bodySize || 10,
      color: COLORS.subInk,
      valign: "top",
      breakLine: false,
      margin: 0,
      bullet: opts.bullets ? { indent: 12 } : undefined,
    });
  }
}

function addArrow(slide, x1, y1, x2, y2, color = COLORS.accent) {
  const x = Math.min(x1, x2);
  const y = Math.min(y1, y2);
  const w = Math.max(Math.abs(x2 - x1), 0.001);
  const h = Math.max(Math.abs(y2 - y1), 0.001);
  slide.addShape(pptx.ShapeType.line, {
    x,
    y,
    w,
    h,
    flipH: x2 < x1,
    flipV: y2 < y1,
    line: {
      color,
      width: 1.6,
      endArrowType: "triangle",
    },
  });
}

function addBulletList(slide, items, x, y, w, h, opts = {}) {
  const runs = [];
  items.forEach((item) => {
    runs.push({
      text: item,
      options: {
        bullet: { indent: 14 },
        breakLine: true,
      },
    });
  });
  slide.addText(runs, {
    x,
    y,
    w,
    h,
    fontFace: "Aptos",
    fontSize: opts.fontSize || 13,
    color: opts.color || COLORS.subInk,
    margin: 0,
    valign: "top",
  });
}

function addTitleSlide() {
  const slide = pptx.addSlide();
  slide.background = { color: COLORS.canvas };

  slide.addShape(pptx.ShapeType.roundRect, {
    x: 0.65,
    y: 0.75,
    w: 12,
    h: 5.55,
    rectRadius: 0.16,
    fill: { color: "FDFBF6" },
    line: { color: "C9D3DD", width: 1.1 },
  });

  slide.addShape(pptx.ShapeType.rect, {
    x: 0.92,
    y: 1.08,
    w: 2.1,
    h: 0.18,
    fill: { color: COLORS.accent },
    line: { color: COLORS.accent, transparency: 100 },
  });

  slide.addText("Read! App Architecture", {
    x: 0.95,
    y: 1.42,
    w: 5.9,
    h: 0.62,
    fontFace: "Aptos Display",
    fontSize: 28,
    bold: true,
    color: COLORS.ink,
    margin: 0,
  });
  slide.addText("Functional outline and pipeline map", {
    x: 0.98,
    y: 2.1,
    w: 4.9,
    h: 0.28,
    fontFace: "Aptos",
    fontSize: 14,
    color: COLORS.subInk,
    margin: 0,
  });

  addBulletList(
    slide,
    [
      "User-facing surfaces and entry points",
      "Document open, cleanup, summary, playback, and diagnostics pipelines",
      "Key coordinating classes in the Android codebase",
    ],
    1.0,
    2.75,
    5.7,
    1.8,
    { fontSize: 13.5 }
  );

  addNode(slide, {
    x: 7.55,
    y: 1.45,
    w: 4.25,
    h: 1.0,
    title: "Main coordinator",
    body: "MainActivity.kt + PdfReaderViewModel.kt",
    fill: COLORS.accentSoft,
    line: COLORS.accent,
  });
  addNode(slide, {
    x: 7.55,
    y: 2.85,
    w: 4.25,
    h: 1.0,
    title: "Core pipelines",
    body: "Loading • Cleanup • Summary • Playback",
    fill: COLORS.accentAlt,
    line: "6E8FB9",
  });
  addNode(slide, {
    x: 7.55,
    y: 4.25,
    w: 4.25,
    h: 1.0,
    title: "Support systems",
    body: "Accessibility • Cache • Diagnostics • Local models",
    fill: COLORS.accentAlt2,
    line: "C9A56E",
  });

  addFooter(slide, "Read! app architecture overview");
  warnIfSlideHasOverlaps(slide, pptx);
  warnIfSlideElementsOutOfBounds(slide, pptx);
}

function addSystemMapSlide() {
  const slide = pptx.addSlide();
  addSlideFrame(slide, "1. Main System Map", "Top-level app areas and the classes that connect them");

  addNode(slide, {
    x: 0.65, y: 1.5, w: 3.6, h: 1.55,
    title: "Entry points",
    body: "Fish / accessibility\nOpen link\nPick PDF\nHistory / To read / Bookmarks",
    fill: COLORS.accentSoft, line: COLORS.accent,
  });
  addNode(slide, {
    x: 0.65, y: 3.35, w: 3.6, h: 1.5,
    title: "Compose UI",
    body: "MainActivity.kt\nScreens, details card, diagnostics, reader surface",
  });
  addNode(slide, {
    x: 0.65, y: 5.15, w: 3.6, h: 1.1,
    title: "Accessibility capture",
    body: "ReadAccessibilityService.kt",
    fill: COLORS.accentAlt2, line: "C9A56E",
  });

  addNode(slide, {
    x: 4.8, y: 2.15, w: 3.25, h: 1.75,
    title: "State coordinator",
    body: "PdfReaderViewModel.kt\nLoads documents\nUpdates UI state\nStarts background cleanup\nStarts summary generation",
    fill: COLORS.accentAlt, line: "6E8FB9",
  });

  addNode(slide, {
    x: 8.5, y: 1.45, w: 3.55, h: 1.2,
    title: "Storage + cache",
    body: "ReaderStorageRepository.kt",
  });
  addNode(slide, {
    x: 8.5, y: 2.95, w: 3.55, h: 1.2,
    title: "Document loading",
    body: "ReaderDocumentLoader.kt",
  });
  addNode(slide, {
    x: 8.5, y: 4.45, w: 3.55, h: 1.2,
    title: "Cleanup + summary",
    body: "ReaderLocalCleanup.kt",
  });
  addNode(slide, {
    x: 8.5, y: 5.95, w: 3.55, h: 0.75,
    title: "Playback + diagnostics",
    body: "ReaderPlaybackService.kt\nDiagnosticsLoggingService.kt",
    bodySize: 9.5,
    fill: COLORS.ok,
    line: "8DAE97",
  });

  addArrow(slide, 4.25, 4.08, 4.8, 3.05);
  addArrow(slide, 4.25, 2.25, 4.8, 2.55);
  addArrow(slide, 8.05, 3.0, 8.5, 2.1);
  addArrow(slide, 8.05, 3.0, 8.5, 3.55);
  addArrow(slide, 8.05, 3.0, 8.5, 5.05);
  addArrow(slide, 8.05, 3.0, 8.5, 6.25);

  addFooter(slide);
  warnIfSlideHasOverlaps(slide, pptx);
  warnIfSlideElementsOutOfBounds(slide, pptx);
}

function addOpenPipelineSlide() {
  const slide = pptx.addSlide();
  addSlideFrame(slide, "2. Document Open Pipeline", "How URLs, PDFs, fish capture, and cached items become a reader document");

  addNode(slide, {
    x: 0.7, y: 1.7, w: 2.25, h: 0.9,
    title: "Open source",
    body: "URL • PDF • fish • history • bookmark",
    fill: COLORS.accentSoft, line: COLORS.accent,
  });
  addNode(slide, {
    x: 3.25, y: 1.7, w: 2.4, h: 0.9,
    title: "ViewModel entry",
    body: "openUrl / openUri / openSourceLabel",
  });
  addNode(slide, {
    x: 5.95, y: 1.7, w: 2.0, h: 0.9,
    title: "Cache check",
    body: "ReaderStorageRepository",
  });
  addNode(slide, {
    x: 8.25, y: 1.7, w: 2.9, h: 0.9,
    title: "ReaderDocumentLoader",
    body: "download + parse if cache miss",
    fill: COLORS.accentAlt, line: "6E8FB9",
  });

  addArrow(slide, 2.95, 2.15, 3.25, 2.15);
  addArrow(slide, 5.65, 2.15, 5.95, 2.15);
  addArrow(slide, 7.95, 2.15, 8.25, 2.15);

  addNode(slide, {
    x: 1.0, y: 3.25, w: 2.5, h: 1.15,
    title: "Web source",
    body: "Download HTML\nExtract cleaned article blocks",
  });
  addNode(slide, {
    x: 3.9, y: 3.25, w: 2.5, h: 1.15,
    title: "PDF source",
    body: "Read bytes\nExtract PDF text and metadata",
  });
  addNode(slide, {
    x: 6.8, y: 3.25, w: 2.5, h: 1.15,
    title: "Captured text",
    body: "Parse visible text into blocks",
  });
  addNode(slide, {
    x: 9.7, y: 3.25, w: 2.0, h: 1.15,
    title: "ReaderDocument",
    body: "title + blocks + metadata",
    fill: COLORS.ok, line: "8DAE97",
  });

  addArrow(slide, 9.25, 2.6, 2.25, 3.25);
  addArrow(slide, 9.25, 2.6, 5.15, 3.25);
  addArrow(slide, 9.25, 2.6, 8.05, 3.25);
  addArrow(slide, 3.5, 3.82, 9.7, 3.82);
  addArrow(slide, 6.4, 3.82, 9.7, 3.82);
  addArrow(slide, 9.3, 3.82, 9.7, 3.82);

  addNode(slide, {
    x: 1.1, y: 5.1, w: 10.8, h: 1.2,
    title: "After open",
    body: "Save cache and history, show the reader immediately, then optionally start cleanup in the background. If the document is already cached, the cached ReaderDocument opens directly without reprocessing.",
    fill: COLORS.card,
  });

  addFooter(slide);
  warnIfSlideHasOverlaps(slide, pptx);
  warnIfSlideElementsOutOfBounds(slide, pptx);
}

function addCleanupSlide() {
  const slide = pptx.addSlide();
  addSlideFrame(slide, "3. Cleanup Pipeline", "Background LLM cleanup that preserves a stable reading surface");

  addNode(slide, {
    x: 0.75, y: 1.65, w: 2.2, h: 1.0,
    title: "Input document",
    body: "Raw extracted ReaderDocument",
  });
  addNode(slide, {
    x: 3.2, y: 1.65, w: 2.35, h: 1.0,
    title: "Eligibility",
    body: "Web/PDF mode\nParagraph filtering\nChunk building",
  });
  addNode(slide, {
    x: 5.8, y: 1.65, w: 2.15, h: 1.0,
    title: "Model runtime",
    body: "Local model registry\nCPU only for stability",
    fill: COLORS.accentAlt2, line: "C9A56E",
  });
  addNode(slide, {
    x: 8.2, y: 1.65, w: 2.85, h: 1.0,
    title: "Chunk cleanup",
    body: "LLM cleans chunk text\nContext-aware prompt",
    fill: COLORS.accentSoft, line: COLORS.accent,
  });

  addArrow(slide, 2.95, 2.15, 3.2, 2.15);
  addArrow(slide, 5.55, 2.15, 5.8, 2.15);
  addArrow(slide, 7.95, 2.15, 8.2, 2.15);

  addNode(slide, {
    x: 1.0, y: 3.5, w: 3.0, h: 1.5,
    title: "Validation",
    body: "Paragraph count\nToken preservation\nFormatting guards\nDuplication checks",
  });
  addNode(slide, {
    x: 4.45, y: 3.5, w: 3.0, h: 1.5,
    title: "Accepted chunk",
    body: "Replace cleaned paragraphs\nStream partial cleaned document into UI",
    fill: COLORS.ok, line: "8DAE97",
  });
  addNode(slide, {
    x: 7.9, y: 3.5, w: 3.0, h: 1.5,
    title: "Rejected chunk",
    body: "Keep original paragraphs\nRecord rejection reason if diagnostics enabled",
    fill: COLORS.warn, line: "C59A92",
  });

  addArrow(slide, 9.6, 2.65, 2.5, 3.5);
  addArrow(slide, 4.0, 4.25, 4.45, 4.25);
  addArrow(slide, 4.0, 4.25, 7.9, 4.25);

  addNode(slide, {
    x: 1.0, y: 5.55, w: 10.85, h: 0.95,
    title: "Diagnostics path",
    body: "When cleanup diagnostics are enabled, the pipeline stores per-document CleanupRunDiagnostics: chunk reports, rejection reasons, change kinds, changed paragraphs, and the data needed for a before/after diff viewer.",
    fill: COLORS.card,
  });

  addFooter(slide);
  warnIfSlideHasOverlaps(slide, pptx);
  warnIfSlideElementsOutOfBounds(slide, pptx);
}

function addSummaryDiagnosticsSlide() {
  const slide = pptx.addSlide();
  addSlideFrame(slide, "4. Summary + Diagnostics Pipelines", "On-demand summary generation and internal runtime logging");

  addNode(slide, {
    x: 0.75, y: 1.55, w: 5.35, h: 4.8,
    title: "Summary generation",
    body: "1. Details card triggers summarize.\n2. ViewModel builds whole-document summary source.\n3. Source is capped to a maximum safe chunk.\n4. Local summary pipeline warms the selected model.\n5. Draft text streams into the UI while generation runs.\n6. Final summary is normalized, timed, and cached on the document.",
    fill: COLORS.accentSoft,
    line: COLORS.accent,
    bodySize: 11,
  });
  addNode(slide, {
    x: 6.55, y: 1.55, w: 5.2, h: 4.8,
    title: "Diagnostics logging",
    body: "1. Diagnostics screen shows live runtime state.\n2. Optional CSV logging samples battery, CPU, memory, playback, cleanup, and summary state.\n3. Cleanup diagnostics toggle lives in Diagnostics.\n4. Cleanup report viewer is document-scoped and opens from the details card.\n5. Logs are internal only and omit document-identifying URLs.",
    fill: COLORS.accentAlt,
    line: "6E8FB9",
    bodySize: 11,
  });

  addFooter(slide);
  warnIfSlideHasOverlaps(slide, pptx);
  warnIfSlideElementsOutOfBounds(slide, pptx);
}

function addCodeMapSlide() {
  const slide = pptx.addSlide();
  addSlideFrame(slide, "5. Key Files and Responsibilities", "Where each major behavior lives in the Android project");

  const rows = [
    ["MainActivity.kt", "Compose UI, details card, diagnostics screen, reader surface, playback controls"],
    ["PdfReaderViewModel.kt", "Main coordinator for open flows, cleanup startup, summary generation, and UI state"],
    ["ReaderDocumentLoader.kt", "Web/PDF/captured-text loading and extraction"],
    ["ReaderStorageRepository.kt", "Cache, history, bookmarks, to-read, last-opened state"],
    ["ReaderLocalCleanup.kt", "Cleanup pipeline, summary pipeline, local model selection, validation"],
    ["ReadAccessibilityService.kt", "Fish button and accessibility capture path"],
    ["ReaderPlaybackService.kt", "TTS / media session playback service"],
    ["DiagnosticsLoggingService.kt", "Internal CSV logging for diagnostics"],
  ];

  addCard(slide, { x: 0.7, y: 1.55, w: 11.8, h: 5.15, fill: COLORS.card });

  slide.addText("File", {
    x: 0.95, y: 1.8, w: 2.3, h: 0.3,
    fontFace: "Aptos", fontSize: 12, bold: true, color: COLORS.ink, margin: 0,
  });
  slide.addText("Responsibility", {
    x: 3.35, y: 1.8, w: 8.7, h: 0.3,
    fontFace: "Aptos", fontSize: 12, bold: true, color: COLORS.ink, margin: 0,
  });
  slide.addShape(pptx.ShapeType.line, {
    x: 0.92, y: 2.12, w: 11.15, h: 0,
    line: { color: COLORS.line, width: 1.1 },
  });

  let y = 2.24;
  rows.forEach((row, index) => {
    if (index > 0) {
      slide.addShape(pptx.ShapeType.line, {
        x: 0.95, y: y - 0.06, w: 11.05, h: 0,
        line: { color: "E1E5EA", width: 0.8 },
      });
    }
    slide.addText(row[0], {
      x: 0.95, y, w: 2.2, h: 0.42,
      fontFace: "Aptos", fontSize: 10, bold: true, color: COLORS.ink, margin: 0,
    });
    slide.addText(row[1], {
      x: 3.35, y, w: 8.55, h: 0.42,
      fontFace: "Aptos", fontSize: 9.6, color: COLORS.subInk, margin: 0,
    });
    y += 0.53;
  });

  addFooter(slide, "Editable source accompanies this deck");
  warnIfSlideHasOverlaps(slide, pptx);
  warnIfSlideElementsOutOfBounds(slide, pptx);
}

async function main() {
  addTitleSlide();
  addSystemMapSlide();
  addOpenPipelineSlide();
  addCleanupSlide();
  addSummaryDiagnosticsSlide();
  addCodeMapSlide();

  await pptx.writeFile({
    fileName: "read_app_architecture_overview.pptx",
  });
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
