# Ply

An image editor for Android that is capable of handling multiple layers. Built with Kotlin, Jetpack Compose in Material 3 design.

## Requirements

- JDK 17 or newer (JDK 21 works)
- Android SDK Platform 35 + Build-Tools 34+
- Android Studio Ladybug or newer (optional)

The Gradle wrapper is included; a local Gradle install is not required.

## Build

The build only needs the Android SDK; any OS with a JDK works.

### Windows

```powershell
# Point Gradle at your SDK (or create local.properties with sdk.dir=...)
$env:JAVA_HOME = "your-java-dir"
.\gradlew.bat :app:assembleDebug
```

### Linux/macOS

```bash
# Point Gradle at your SDK (or create local.properties with sdk.dir=...)
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64   # any JDK 17+ path
export ANDROID_HOME=$HOME/Android/Sdk                # or use local.properties
./gradlew :app:assembleDebug
```

Linux SDK setup, if starting fresh:

```bash
mkdir -p ~/Android/Sdk && cd ~/Android/Sdk
# Command-line tools: https://developer.android.com/studio#command-line-tools-only
unzip commandlinetools-linux_*.zip -d cmdline-tools
mv cmdline-tools cmdline-tools-tmp && mkdir -p cmdline-tools/latest && mv cmdline-tools-tmp/* cmdline-tools/latest/ && rm -r cmdline-tools-tmp
yes | cmdline-tools/latest/bin/sdkmanager --licenses
cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"
```

Then `export ANDROID_HOME=$HOME/Android/Sdk` and build as above.

### Install and verify

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` on every OS.

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Useful checks without a device:

```bash
./gradlew :app:lintDebug          # 0 errors expected
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

Release builds are minified; supply your own signing config before shipping.

## Features

### Projects page
- Home grid of saved projects with flattened thumbnails, layer counts and relative timestamps.
- New canvas from presets (Square, Story, Post, HD) or exact pixel dimensions with white,
  transparent or black background.
- Open a photo through the system app chooser (gallery, Files/DocumentsUI, OEM file
  managers, cloud providers, or the camera); it becomes a one-layer project.
- Long-press a project to duplicate or delete it.
- Projects are saved when they open, again 1.5 s after each edit, and once more when you leave
  the editor. The exit save runs off the main thread behind a short "Saving…" indicator, so a
  large photo import no longer freezes the editor or the project list.

### Layers
- Add empty raster layers, image layers and text layers from the **+** button in the top bar
  (one tap, then pick the type) or from the three add buttons at the top of the layers sheet.
- Reorder, rename, duplicate, delete, merge down and flatten.
- Per-layer visibility, opacity and blend mode (Normal, Multiply, Screen, Overlay, Darken,
  Lighten, Difference, Add).
- Floating layer rail on the canvas for one-tap layer switching; full layers sheet for the rest.

### Tools
The bottom dock holds seven tools, left to right: **Move, Brush, Eraser, Shape, Text, Remove,
Picker**. Crop, Resize, Fill and the Hand (pan) live in the top-bar transform and overflow
menus, so the dock stays uncluttered.

| Tool | Behavior |
| --- | --- |
| Move | Drag the active layer; pinch with two fingers to scale and rotate it. The options row nudges it one document pixel at a time (← ↑ ↓ →) and can center it. |
| Brush | Round, antialiased strokes with size and flow. |
| Eraser | Clears pixels on the active layer. |
| Shape | Rectangle, ellipse or line; filled or outlined. |
| Text | Multi-line type with size, bold, italic and color. Tap the canvas to place new text; the tool's "Add text" button edits the active text layer. |
| Remove | Three modes. **Brush** paints a red mask over an object (a magnified loupe follows your finger), **Lasso** circles it: the bounding rectangle of the loop becomes the mask: and then Remove fills the selection with the chosen on-device engine: **Classic** (BFS, instant, no AI model), **Telea** (edge-aware, instant, no AI model), **MI-GAN** (bundled, offline) or **LaMa** (196 MB one-time download, tiled dynamic pipeline ported from ImageToolbox). **Background** skips the mask and cuts the active layer's subject out automatically with a segmentation model (U²-Net P bundled; ISNet / BiRefNet as downloads). The mask can be cleared with the restart button. |
| Picker | Samples the flattened color under your finger; the hex value is copied to the clipboard automatically and shown in the options row. |
| Resize | From the transform menu: eight grips around the active layer. Corner grips keep the aspect ratio, edge grips stretch a single axis, and the opposite side stays anchored. The options row shows the layer's live output size in pixels and offers Reset and Fit. |
| Crop | From the transform menu: crops the **active layer** to a free or 1:1 / 4:5 / 3:2 / 16:9 selection, with corner handles and 1 px nudge arrows. |
| Fill | From the transform menu: fills the whole active layer with the current color. Flood fill with tolerance is still available for the Fill tool. |
| Hand | From the overflow menu: drag to pan; pinch to zoom. Two fingers pan and zoom the viewport with any other tool too. |

Tapping outside the artwork clears the selection; the next tap inside the canvas selects the
top-most layer under your finger, so editing resumes immediately. The layer rail and the
layers sheet both show when nothing is selected.

The viewport also has a Fit control, and an optional rule-of-thirds grid.

The top bar follows the reference app's single row: back, project name, **+** (add layer),
the transform menu, save, and the overflow. Adding a layer is now two taps: **+**, then
Image / Empty / Text. The transform menu runs one action at a time: Resize and Crop switch
the active tool, while Rotate 90° left/right, Flip horizontal/vertical, Auto-fit, Reset
stretch, Merge down, Flatten layers, Fill layer and Clear layer are one-shot commands on the
active layer. Undo and redo sit next to the zoom readout in the bottom-left canvas pill.

Leaving the editor asks for confirmation and saves the project before returning to the
project list, so an accidental back gesture cannot drop unsaved work.

### Color
- The color sheet has a HSV field, a hue bar, recent swatches, an editable hex field
  (`#RGB` or `#RRGGBB`), and a copy button for the current hex.
- **Pick from canvas** closes the sheet and switches to the Picker; the sampled color is
  applied and its hex is placed on the clipboard.
- The text dialog also accepts a hex value next to its quick palette.

### Editing
- 30-step undo/redo with a ~160 MB bitmap budget; untouched layers are shared between snapshots.
- On-device object removal with Classic (BFS), Telea, MI-GAN or LaMa, and one-tap automatic
  background removal with U²-Net P / ISNet / BiRefNet (see below), applied to the active
  layer.
- Brightness, contrast and saturation adjustment on the active layer.
- Canvas resize, scale-to-50% content resampling and canvas background color.
- Export to `Pictures/Ply` as PNG, JPEG, WebP, **JPEG XL** or **HEIC** (quality control),
  optionally sharing the result straight away.
- Import supports JPEG, PNG, WebP, **JPEG XL** and **HEIC**: EXIF orientation is honored and
  the long edge is capped at 3072 px.

### Background removal
The Remove tool's **Background** mode cuts the active layer's subject out automatically —
no mask painting. The result is a soft alpha matte (not a hard threshold), applied to the
layer's existing pixels, so layers that already contain transparency keep it.

- **U²-Net P** (bundled, 4.5 MB): the same `u2netp.onnx` model rembg's light profile uses,
  bundled from ImageToolbox. Works offline, runs in a fraction of a second at 320 px, and is
  the default.
- **ISNet general-use** (178 MB one-time download): 1024 px, sharper subject boundaries.
- **BiRefNet swin-tiny** (224 MB one-time download): the highest-quality tier at 1024 px.
- Downloads stream through the same resumable, SHA-256-verified downloader as LaMa and can
  be deleted again from the options row. The engine keeps one segmentation session alive at
  a time and swaps it when the model changes.
- Post-processing follows rembg's u2net recipe: the raw probability map is min-max stretched
  before it becomes the alpha channel, so soft predictions do not wash out to gray.

### Object removal
The **Remove** tool's options panel has two modes: **Object removal** (brush-painted,
lasso-boxed, or tap-selected mask) and **Background removal** (automatic cutout): with the
engine picked from a model dropdown and remembered between sessions:

**Tap-to-select** (MobileSAM, 45 MB one-time download): tap an object and MobileSAM's mask
decoder returns a tight mask around it; each further tap refines the selection (SAM's
point prompts accumulate). The resulting mask flows into the same removal overlay as the
brush, so any engine (Classic, Telea, MI-GAN, LaMa) can fill it. The image encoder runs
once per layer edit (the expensive half, ~1024 px); the decoder runs per tap and is fast
enough for interactive use. Models come from the ImageToolbox repository with published
sizes and SHA-256 digests; the encoder session is cached until the layer bitmap changes.
Leaving Tap mode or the reset button clears the prompts.

**Classic** (bundled, instant): the original pre-ONNX fill: multi-source BFS nearest-color
fill plus a few smoothing passes, following the approach of
[EmreO33/unmark](https://github.com/EmreO33/unmark). No neural network, no download, linear
time; best on marks, logos and other thin selections.

**Telea** (bundled, instant): [Telea inpainting](https://doi.org/10.2312/EGSG.20041036)
("An Image Inpainting Technique Based on the Fast Marching Method", 2004) as a Kotlin port of
the TypeScript implementation in [maxgfr/unmark](https://github.com/maxgfr/unmark) (MIT).
Also no neural network and no download, but edge-aware: it continues gradients into the hole
instead of averaging toward the nearest color. For phone-scale speed the fill runs on a
half-resolution copy of the crop (≤512 px) with OpenCV's default radius 3, and the result is
bilinearly scaled back through the full-resolution mask.

**MI-GAN** (bundled, offline): [MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN)
(Picsart AI Research, MIT-licensed weights) via the official pipeline, converted to ONNX
Runtime's portable `.ort` format (`migan_pipeline_v2.ort`, 28.3 MB).

**LaMa** (downloaded on first use): big-LaMa with a **dynamic-resolution** graph
(`LaMa_dynamic.onnx`, 196 MB), the same pipeline ImageToolbox ships. The file comes from the
[ImageToolbox model repository](https://huggingface.co/T8RIN/imagetoolbox-models) on
HuggingFace, streams with resume support into `filesDir/onnx`, and is verified against the
published SHA-256 before it becomes loadable. The 196 MB download is one-time; the model can
be deleted from the same options row to reclaim storage.

- Paint over the object with the mask brush; the mask is stored at up to 1536 px on the long
  edge and drawn as a red 55% overlay. While brushing, a circular loupe above your finger shows
  the canvas at 2.6× with the live mask, so your hand does not hide the edge you are painting.
- **Lasso** draws a freehand loop; on release the mask becomes the rectangle whose four sides
  touch the loop's extremes, with a dashed preview while you drag.
- **Remove** renders a bounded crop around the painted area: max 1280 px work edge for
  MI-GAN, 2560 px for LaMa: runs the selected engine through ONNX Runtime for Android, and
  writes only the masked pixels back into the active layer. The layer's position, scale and
  rotation are preserved.
- The LaMa pipeline mirrors ImageToolbox exactly: float tensors normalized to 0–1 with a
  0/1 mask, inputs padded to a multiple of 8 by edge reflection, a single full-resolution
  pass when the padded context fits in 1024 px, and otherwise an overview pass plus 768 px
  tiles (128 px context padding) whose predictions blend against the upscaled overview
  (`overview + patch − low-pass patch`) so large removals stay consistent.
- Runs on the `Default` dispatcher behind a busy state, so the canvas stays responsive; the
  result is committed to history like any other edit and can be undone.
- MI-GAN is extracted from assets into `filesDir` on first use, then reused. Models run on
  `arm64-v8a` and `armeabi-v7a` only.

### Integration
- Receives `ACTION_SEND`, `ACTION_EDIT` and `ACTION_VIEW` image intents.
- Import honors EXIF orientation and caps the long edge at 3072 px. JPEG XL is decoded
  through `jxl-coder` (BSD-3 libjxl), HEIC through the platform `BitmapFactory`.
- Export writes through MediaStore to `Pictures/Ply`. JPEG XL encodes via `jxl-coder` with
  the user's quality; HEIC encodes through `androidx.heifwriter` (the platform `HeifWriter`
  is not in the public SDK stubs) in a temp file that is streamed into MediaStore. JPEG and
  HEIC have no alpha channel, so transparent canvases composite onto white for those two
  formats; PNG, WebP and JPEG XL keep transparency.
- Image import opens the system chooser with `ACTION_OPEN_DOCUMENT` as its payload, so
  DocumentsUI, the OEM document picker, and installed storage providers are always listed.
  The photo picker (`ACTION_PICK_IMAGES`), the default gallery (`ACTION_PICK`), and camera
  capture (`ACTION_IMAGE_CAPTURE` via a cache-backed `FileProvider`) are added as initial
  intents. No storage or camera permissions are declared; picked URIs are read immediately
  and copied into the project.
- `ACTION_OPEN_DOCUMENT` is used instead of `ACTION_GET_CONTENT` because Android 16 lets the
  system photo picker take over image/video `GET_CONTENT`, which hides every document
  provider. Caller-supplied initial intents that duplicate a payload handler are also dropped
  by the chooser, so DocumentsUI could not be re-added as an extra while `GET_CONTENT` was
  the payload.

## Architecture

```
app/src/main/java/com/ply/editor/
├── MainActivity.kt          edge-to-edge host, share/import intents, system bar styling
├── model/
│   ├── Document.kt          immutable Document/Layer, transform matrix, blend modes
│   └── Tools.kt             tool and shape enums
├── engine/
│   ├── Compositor.kt        layer compositing, flattening, thumbnails
│   ├── Inpainter.kt         engine routing, crop/mask rendering, masked patch, MI-GAN session
│   ├── BfsInpainter.kt      original BFS nearest-color fill (no neural net)
│   ├── TeleaInpainter.kt    Telea fast-marching fill (no neural net), port of maxgfr/unmark
│   ├── onnx/
│   │   ├── InpaintModels.kt     engine registries (removal + background models)
│   │   ├── ModelDownloader.kt   streamed, resumable, SHA-256-verified model downloads
│   │   ├── LamaInpainter.kt     dynamic LaMa pipeline (ImageToolbox port): tiling, reflection padding
│   │   ├── SmartSelector.kt     MobileSAM tap-to-select: encoder/decoder sessions, dilated masks
│   │   └── BackgroundRemover.kt U²-Net / ISNet / BiRefNet subject cutout (rembg zoo, ImageToolbox plumbing)
│   └── Painter.kt           brush session, flood fill, shapes, text layout, adjustments
├── history/History.kt       snapshot stacks with identity-based memory accounting
├── io/
│   ├── ProjectStore.kt      JSON manifest + per-layer JPEG/PNG + thumb.png
│   ├── Importer.kt          EXIF-aware decode
│   ├── PickImageSource.kt   chooser-based image import (apps + camera)
│   └── Exporter.kt          MediaStore export
├── state/EditorViewModel.kt editing state, gestures, tools, undo, autosave, export
├── settings/Settings.kt     theme/grid/export preferences
└── ui/
    ├── HomeScreen.kt        projects, presets, import, settings sheet
    ├── editor/              canvas, tool dock, layers sheet/rail, color picker, dialogs
    └── theme/Theme.kt       Material 3 palette and typography
```

Key decisions:

- **Immutable layer snapshots.** A layer bitmap is never mutated after it is committed. Brush,
  eraser, fill, shape and adjustment all copy first, so history snapshots can share every
  untouched bitmap and undo is allocation-light.
- **On-device models, bounded work.** Object removal ships the official MI-GAN pipeline as
  `migan_pipeline_v2.ort` and runs it with the full `onnxruntime-android` package (~52 MB AAR,
  16 MB arm64 / 11 MB armv7 native libraries after ABI filtering). The smaller
  `onnxruntime-mobile` package
  was tried first (6.9 MB AAR), but its reduced operator set lacks `ReduceSum(13)`, which the
  MI-GAN graph needs. The mobile runtime also cannot parse `.onnx` protobuf, so that model is
  converted once with ONNX Runtime's `convert_onnx_models_to_ort` tool and the `.ort` file is
  bundled. The LaMa engine instead downloads a plain `.onnx` graph: the full runtime parses
  protobuf, so no conversion step is needed: and mirrors ImageToolbox's execution plan:
  float inputs normalized to 0–1, masks as 0/1, alignment to multiples of 8 via edge
  reflection, a single pass when the padded context fits in 1024 px and an overview + 768 px
  tile plan with guide blending beyond that, with only the requested output computed per run.
  Only a crop around the mask is rendered and fed to either engine (1280 px work edge for
  MI-GAN, 2560 px for LaMa), so inference memory and time stay bounded on large documents.
  The generated pixels are composited through the inverse layer matrix, which keeps transformed
  layers aligned and matches the immutable-bitmap/history model used by every other tool.
- **Canvas drawing in Compose.** Layers are drawn through the native canvas inside a Compose
  `Canvas`, using `android.graphics` matrices and `BlendMode`. No OpenGL/GLSurfaceView.
- **Autosave on a private store.** Each editor visit owns a `ViewModelStore`; leaving the editor
  clears it and releases bitmaps and history. A final save runs on a plain thread so it can
  outlive the ViewModel.
- **Serialized project writes.** `ProjectStore` synchronizes save/delete and writes each layer
  and the thumbnail to a temporary file before renaming it into place, so the autosave job, the
  exit save and the home grid cannot read a half-written file.
- **Storage sized for phone photos.** Opaque layers (imported photos, flattened artwork) are
  stored as JPEG quality 95; layers that actually contain transparency stay as lossless PNG.
  A 12 MP photo drops from an 8.5 MB PNG to roughly 2 MB of JPEG and encodes several times
  faster. Bitmaps decoded from JPEG carry a `hasAlpha=false` flag that survives `Bitmap.copy`;
  every edit that can punch holes into such a layer (eraser, flood fill, shapes, fill) re-arms
  the flag on its working copy, and the project store re-arms it before any PNG encode —
  otherwise the cleared pixels bake into an alpha-less file and return from disk as black.
  Thumbnails are rendered at 720 px on the long edge, which keeps the home grid sharp
  on a 1080×2400 screen without decoding the full document. Layers that still match the file on
  disk are never re-encoded, so opening a project and autosaving only refreshes the manifest and
  the thumbnail.
- **Ply palette and mark.** The interface is built from `#B6C4FF`, `#34457A` and `#292A2F`
  (Material You default blue) in both light and dark schemes. The launcher icon stacks three
  isometric sheets in those blues on charcoal, with a monochrome variant for themed icons.
  The same mark is the splash screen: a `windowBackground` layer for API 29–30 and
  `windowSplashScreen*` attributes for Android 12+.

## Verified

- `:app:assembleDebug` and `:app:lintDebug` succeed with **0 lint errors** (AGP 8.6.1,
  Kotlin 2.1.20, Compose BOM 2024.10.01). Kotlin moved 2.0.21 → 2.1.20 because jxl-coder
  2.6.x ships Kotlin 2.2 metadata, which the 2.0 compiler refuses to read.
- Image formats: JPEG XL via `io.github.awxkee:jxl-coder:2.6.0` (2.6.1 requires
  compileSdk 36; the project stays on 35) and HEIC encode via `androidx.heifwriter:1.1.0`
  (the platform `android.media.HeifWriter` is absent from the public SDK stub jars, verified
  against the installed android-35 platform). Import: JXL detected by the library's own
  `isJXL` signature check, decoded sampled, EXIF-oriented; HEIC decodes through the platform
  `BitmapFactory`. Export: JXL encodes LOSSY at the user's quality (SQUIRREL effort); HEIC
  encodes via `HeifWriter` bitmap mode into a temp file streamed into MediaStore. The export
  dialog lists both formats with a quality slider (all lossy formats) and an alpha note on
  HEIC; lint confirms 0 errors across the new I/O code.
- ONNX engine layer (Classic/Telea/MI-GAN/LaMa routing, downloader, model picker UI):
  compiles clean with the same toolchain; ONNX Runtime for Android bumped 1.18.0 → 1.31.1 to
  match the version ImageToolbox runs the LaMa graph on. The model's protobuf header was
  inspected remotely: IR version 8, producer pytorch 2.3.0, doc string confirming the tensor
  contract the port uses (image RGB 0..1, mask 1=inpaint, output composited RGB 0..255).
  A desktop JVM harness (`TestLama.java`) ran the real `LaMa_dynamic.onnx` through the exact
  tensor-building logic the app uses and confirmed the contract end to end: the hole changes
  by a mean of ~127 levels while known pixels stay untouched, inverted-mask and 0/255-scale
  variants degrade exactly as a polarity/scale mistake would. The harness also caught a real
  bug the runtime bump introduced: ORT 1.20+ changed `Result.get(String)` to return
  `Optional<OnnxValue>`, which made the app's cast fail on every LaMa run (the model ran but
  its output was discarded). APK badging confirms the `android.permission.INTERNET` and
  unchanged `arm64-v8a`/`armeabi-v7a` native-code set, minSdk 29, targetSdk 35. The LaMa
  download URL, 205,294,245-byte size and SHA-256 digest are taken from the model
  repository's published LFS metadata. Final download → session → tiled-inference still
  needs a pass on a physical device; if LaMa fails on device, the failure toast now shows
  the runtime's own error text to make the cause identifiable.
- Background removal: the bundled `u2netp.onnx` (4,574,861 bytes, byte-identical to
  ImageToolbox's asset) was exercised on a desktop JVM through the app's exact tensor
  pipeline against the real model: the first output is a `[1,1,320,320]` matte that
  separates a synthetic subject (mean 0.998) from its background (mean 0.002) with plain
  0..1 normalization, confirming the recipe before shipping. The harness approach also
  caught two shipping bugs from the first cut: the run call passed the *output* name as the
  input tensor's key, so ORT rejected every inference before it started, and the BiRefNet
  digest constant had picked up a stray 65th hex character, which failed every download's
  checksum. Both fixed; the ISNet and BiRefNet digests are now verified against the model
  repository's metadata *and* by downloading both files and hashing them locally
  (`isnet-general-use.onnx` 178,647,984 bytes → `4fcc3f7f…53e`;
  `birefnet_swin_tiny.onnx` 224,005,046 bytes → `efd35f77…b0e`). The downloader additionally
  discards a resumed partial file and re-downloads from scratch once if verification still
  fails, and the engine reports matte statistics (min/max/mean) to logcat and refuses flat
  mattes with a visible error.
- `:app:lintDebug`: 0 errors, a handful of informational warnings (dependency versions, the
  obsolete `-v26` resource qualifier, and missing x86_64 support for ChromeOS after the ABI filter).
- APK badging: `com.ply.editor` v1.14 (versionCode 14), minSdk 29, targetSdk 35, launchable
  `MainActivity`, adaptive launcher icon, `android.permission.INTERNET`, native code for
  `arm64-v8a` and `armeabi-v7a` only.
- Tap-to-select (MobileSAM) was verified end to end on desktop before shipping: a JVM harness
  ran the real encoder + decoder on a synthetic image and the returned logits matched the
  object's area to within a fraction of a percent (positive fraction 0.1196 vs the true 0.12),
  which then exposed the shipping bug: the mask painter raised alpha on the photo's opaque
  pixels instead of building the mask from the logits alone, selecting the whole image.
  Masks are now dilated by 2% of the long edge (4–24 px) with a 0.45 sigmoid threshold, per
  the "rough brush beats exact" inpainting heuristic; dilation is a two-pass distance
  transform, O(n) at any radius.
- The bundled pipeline was checked against the MI-GAN reference example on desktop (ONNX
  Runtime 1.18): mask polarity (`255` = keep, `0` = hole) and the official crop/blend pipeline
  reproduce the published result. The `.ort` conversion was diffed against the source `.onnx`
  on the same input; output differs by at most one 8-bit level per channel.
- The debug APK is ~155 MiB with the 28.3 MB MI-GAN model, the jxl/brotli native libraries
  and the ONNX Runtime 1.31.1 native libraries for `arm64-v8a` and `armeabi-v7a`; no
  `x86`/`x86_64` variants are packaged.

The debug build has been installed and exercised on a physical vivo V2405A (Android 16, 1080×2400)
across v1.0 through v1.14: layer deselect/reselect, the tool dock, color-sheet taps and hex
entry, eyedropper sampling plus clipboard paste, the exit warning and its off-thread save,
pixel nudges persisting to `project.json`, resize grips, reopening saved projects, object
removal with all four engines (Classic, Telea, MI-GAN, LaMa), background removal with all
three models (U²-Net P, ISNet, BiRefNet), tap-to-select with MobileSAM, JXL/HEIC import and
export, and the text selection chrome (delete / duplicate / rotate-scale).

Object removal was exercised on a 2088×3072 photo: first use extracts the model into `filesDir`;
later removals of a large painted area finish in roughly 2–3 s including crop rendering and
compositing. Undo restored the original pixels exactly and redo restored the generated result
exactly. The loupe renders above the brush point at the system density (58 dp radius, 2.6×),
and a freehand lasso produced a mask whose filled rectangle matched the loop's bounding box.

Large-photo behavior was checked with a 3400×4400 JPEG import and a 2775×4096 camera photo:

- the import is capped to 2374×3072 and opens in about 2.7 s;
- the first save writes a 2.0 MB JPEG layer plus a 754 KB 720 px thumbnail in about 0.6 s;
- the migrated 2775×4096 project fell from 8.5 MB of PNG to a 1.7 MB JPEG layer;
- project cards show the full thumbnail (`ContentScale.Fit`) with the resolution line
  (e.g. `2374×3072 · just now`) no longer clipped by the rounded card, and the preview updates
  as soon as the editor closes because thumbnails are renamed into place atomically.

## Known limitations

- Blend modes composite against the app's checkerboard when the canvas background is
  transparent. Flattening/exporting uses an opaque or transparent render, so exported files are
  correct.
- Brush flow below 100% accumulates at segment joins (each segment is stamped separately).
- Text is rasterized on commit; changing a text layer recreates its bitmap at the same center.
- Object removal engines trade speed against quality. **Classic** (BFS) and **Telea** fill
  small and medium selections in well under a second (Telea works on a half-resolution copy
  of the crop, so large selections stay fast too). **MI-GAN** is tuned for small,
  object-shaped masks; very large or rectangular masks can leave ghosts or repeated texture
  because its generator works at 512 px, and a loose lasso selects the whole rectangle around
  the loop, including the background inside it. **LaMa** exists for exactly those cases: it
  tiles large masks and keeps detail: at the cost of a one-time 196 MB download and
  noticeably longer runs (several seconds per 1024 px region, more when tiling splits a big
  mask into many tiles).
- The Telea marching core is covered by JVM unit tests
  (`TeleaInpainterTest`), which exist because a porting bug once made the fast-marching
  front never advance: the fill looped forever on device.
- Generated pixels are opaque, so removing an object on a layer that is transparent there
  fills the hole with reconstructed color instead of transparency.
- The LaMa engine needs a one-time 196 MB download (the app asks through the Remove options
  row), creates its session on first use (a few seconds), and holds a ~200 MB fp32 graph in
  memory while running. Low-RAM 32-bit (`armeabi-v7a`) devices may struggle with it; MI-GAN
  stays the safe offline choice there.
- The model loads on first use (session creation takes a moment) and only runs on `arm64-v8a`
  and `armeabi-v7a` devices; there are no x86 emulator libraries in the APK.
- Crop applies to the active layer and leaves the canvas size unchanged; cropping a text layer
  rasterizes it.
- Crop snaps to the drag rectangle rather than offering draggable handles after release.
- No filters, stickers, frames or perspective tools yet; the reference app's ~80 filters are out
  of scope for this MVP.
- Large (4096 px) documents with many layers can be memory-hungry; the app requests a large heap.
- Layers with real transparency are still stored as lossless PNG, so they save more slowly than
  opaque photo layers. Painting a small opaque area onto an otherwise transparent layer keeps
  that whole layer on the PNG path until it is flattened.
- Debug APK is ~151 MiB because `material-icons-extended`, the MI-GAN model and the ONNX
  Runtime native libraries are bundled unshrunk; release builds shrink code and resources but
  the model and native libraries remain. The 1.18.0 → 1.31.1 runtime bump (LaMa
  compatibility) added ~31 MB of native libraries across the two ABIs.

## LLM use Disclosure
AI was used during development of this project.

## Credit
Object removal uses [MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN) by Picsart AI
Research. The model weights and code are MIT-licensed. Inference runs on
[ONNX Runtime](https://onnxruntime.ai/) (MIT) via the `onnxruntime-android` package.

The dynamic LaMa pipeline and its tiled execution plan are ported from
[ImageToolbox](https://github.com/T8RIN/ImageToolbox) by T8RIN (Apache License 2.0); the
`LaMa_dynamic.onnx` graph is distributed through T8RIN's
[model repository](https://huggingface.co/T8RIN/imagetoolbox-models). LaMa itself is
["Resolution-robust Large Mask Inpainting with Fourier Convolutions"](https://arxiv.org/abs/2109.07139)
by Suvorov et al. (Samsung Research), Apache-2.0.

The Classic engine follows the BFS-fill approach of
[EmreO33/unmark](https://github.com/EmreO33/unmark) (GPL-3.0; approach credited, the Kotlin
implementation here is original). The Telea engine is a Kotlin port of the fast-marching
inpainting implementation in [maxgfr/unmark](https://github.com/maxgfr/unmark) (MIT License);
the algorithm is
["An Image Inpainting Technique Based on the Fast Marching Method"](https://doi.org/10.2312/EGSG.20041036)
by Alexandru Telea (2004).

Background removal follows the model zoo of
[rembg](https://github.com/danielgatis/rembg) (MIT): U²-Net P bundled, ISNet and BiRefNet
as downloads: with ONNX plumbing adapted from ImageToolbox. U²-Net is
[Apache-2.0](https://github.com/xuebinqin/U-2-Net) by Qin et al.; ISNet is Apache-2.0
(DIS); BiRefNet is MIT. The bundled `u2netp.onnx` ships with ImageToolbox.

JPEG XL support uses [jxl-coder](https://github.com/awxkee/jxl-coder) by Radzivon
Bartoshyk (MIT), wrapping libjxl (BSD-3, JPEG XL Project Authors). HEIC encoding uses
[androidx.heifwriter](https://developer.android.com/reference/androidx/heifwriter/HeifWriter)
(Apache-2.0). Tap-to-select uses [MobileSAM](https://github.com/ChaoningZhang/MobileSAM)
(Apache-2.0) through the ONNX exports and Kotlin plumbing of ImageToolbox; SAM itself is
by Meta AI Research ([Segment Anything](https://github.com/facebookresearch/segment-anything),
Apache-2.0).
