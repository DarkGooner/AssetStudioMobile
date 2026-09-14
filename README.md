# AssetStudio Mobile

An **Android Unity asset browser and texture replacement tool** ported from [AssetStudio](https://github.com/Perfare/AssetStudio). It is built with **Kotlin + Jetpack Compose (Material Design 3)**. The core AssetStudio parser is ported line-by-line from C#, while texture decoders are compiled natively through **NDK + JNI**.

## Stability and memory design

Android application heaps are much smaller than desktop heaps, so the parser contains multiple independent safety layers. A malformed asset should fail locally instead of taking down the entire load operation.

- **Unity array bounds validation:** Unity float/int/vector/object arrays validate `count × elemSize` against remaining file bytes before allocation. Corrupt lengths such as `0xFE000000` therefore produce an `EOFException` instead of a multi-gigabyte allocation attempt.
- **Per-object fault isolation:** a failed asset, including an `OutOfMemoryError`, is recorded and skipped while other assets continue loading.
- **Large-file preflight:** files larger than 768 MB are rejected before being read into memory.
- **Folder loading protection:** folder imports use filtering, file-count limits, dynamic heap checks, batching, type-tree stripping, cancellation, and detailed summaries.
- **Dynamic heap budgeting:** memory decisions are based on the runtime application heap rather than assuming that every Android device has the same heap size. `largeHeap` is enabled for devices that support a larger application heap.
- **8 GB RAM tuning:** on high-memory devices the loader uses a less conservative runtime budget while still leaving headroom for Android, the UI, native decoders, decompression, and temporary allocations. The app does **not** attempt to reserve all 8 GB of physical RAM; Android controls the process heap limit.

## Folder loading

The home screen provides **Load Folder**: select a directory, optionally include subdirectories, scan Unity resource files, review the detected files, and start a single batch operation.

| Protection | Description |
| --- | --- |
| Smart filtering | Recognizes Unity extensions such as `bundle`, `unity3d`, `assetbundle`, `assets`, `zip`, `unitypackage`, `resS`, etc. Unknown extensions are checked using file-header magic. Common media/document files are excluded. |
| File-count safety limit | A single folder scan is capped at 5,000 load candidates. If the limit is reached, the smallest files are retained so a pathological directory cannot consume the entire process. |
| Runtime heap protection | Loading periodically checks the real Java/ART heap. Reading and object construction use separate safety thresholds because parsed object graphs can be several times larger than the source file bytes. |
| Type-tree stripping | After object construction, unnecessary type-tree data is stripped for files without `MonoBehaviour`, substantially reducing retained memory. |
| Dynamic memory budget | The folder budget is calculated from the actual application heap. Large-memory devices receive a larger working budget, while low-memory devices remain conservative. |
| Per-file isolation | A corrupt, encrypted, oversized, or otherwise unsupported file is recorded as a failure and does not abort the remaining files. |
| Cancellation | Loading can be cancelled while retaining already loaded assets. |
| Performance protection | Files are processed in size order and expensive global work is delayed until the batch has been read. |
| Transparent summary | Completion reports successful files, skipped files, memory stops, failures, and lazy/on-disk assets. |

## Unified asset view and lazy loading

The user sees one asset library rather than having to understand internal memory batches.

```text
Internal implementation:
  load a safe batch → release reloadable objects → load the next batch → repeat

User view:
  Loading 12/34: example.bundle
  Complete: 34 files · 8,512 assets
  Assets that cannot remain resident are kept as lightweight disk-backed entries.
  Opening one loads only the source file required for that asset.
```

### Key behavior

- **Unified asset list:** all files and all assets remain represented in one list, even when some parsed objects have been released.
- **Index/object separation:** `AssetItem.obj` may be `null`. A null object means the lightweight index entry is resident but its heavy parsed object is currently on disk.
- **On-demand loading:** opening or exporting a lazy asset automatically reloads only its source file through the same memory safety checks.
- **On-demand cache:** at most 48 reloaded objects are retained; older objects are evicted so long browsing sessions do not permanently refill the heap.
- **Grouped export:** batch export groups lazy assets by source file so one source file is not parsed repeatedly for every asset.
- **SAF limitation:** files selected through Android's Storage Access Framework do not always provide persistent URI access, so those sessions are kept resident rather than evicted for later disk reload.

## Background processing

Long-running asset loading/export operations can run while AssetStudio is **in the background or the screen is locked**.

The app uses an Android **foreground service** while a heavy processing operation is active. Android displays a persistent notification with the current operation, so the process is less likely to be reclaimed while the user uses another app.

The foreground service protects the existing parser operation; it does not pretend that Android can guarantee survival after the operating system force-stops the application. If the user force-stops the app or the process is terminated, the in-memory job cannot continue.

## Batch export

Long-press an asset in the asset list to enter multi-selection, choose **Batch Export**, and select an output directory.

Formats are selected by asset type:

- Texture2D / Sprite → PNG
- Mesh → OBJ
- AudioClip → WAV
- Video → original data
- TextAsset / MonoBehaviour / Material / Shader → readable text dump
- Font → TTF where supported
- Other assets → raw data dump where no specialized exporter exists

Exports are individually isolated: one failed asset does not stop the remaining exports. File names are sanitized and duplicates receive a numeric suffix.

## Texture orientation fix

Unity texture data follows the OpenGL convention where row 0 is the bottom of the image, while Android `Bitmap` and PNG data use the top row first. AssetStudio Mobile therefore performs one vertical flip when decoding and the inverse flip when writing replacement texture data.

Preview, PNG export, Sprite cropping, and texture replacement all use the same `PixelFlip` implementation so the workflow is consistent:

```text
Unity texture data → decode + vertical flip → Android/PNG orientation
Android replacement → inverse vertical flip → Unity texture orientation
```

## Features

| Feature | Description |
| --- | --- |
| Unity asset loading | `.unity3d`, `.assetbundle`, `.bundle`, UnityFS, UnityWeb/Raw v6, legacy Web/Raw, `.assets`, `.zip`, `.unitypackage` and related structures |
| Encrypted bundles | Automatic handling for UnityKHFS / UnityKHNFS / UnityKH1FS variants, including compressed/aligned bundle sections; automatic detection of custom leading bytes and XOR-style encryption; manual XOR/AES-ECB/AES-CBC decryption with hexadecimal keys and prefix offsets |
| Asset browsing | Filter by type, search by name, and locate assets by PathID |
| Asset information | Long-press/selectable asset information including PathID, file name, and container path |
| Texture preview | 30+ texture formats, mipmap selection, adaptive scaling, pixel-preserving enlargement, zoom from 25% to 800%, and two-direction scrolling for oversized images |
| Mesh preview | Pure Kotlin software rasterizer with z-buffering, camera-facing normals, Lambert lighting, ambient lighting, rotation, drag interaction, and automatic downsampling for very large meshes |
| Renderer preview/replacement | SkinnedMeshRenderer and MeshFilter can resolve their referenced Mesh, show mesh statistics, and replace the Mesh reference while retaining bone/material slots; cross-file references are supported |
| MCP renderer support | MCP can filter renderer components, report mesh/bone/material/blend-shape information, dump renderer data, and export referenced meshes/text |
| Export | Texture2D → PNG, TextAsset/MonoBehaviour → text, AudioClip → WAV, and raw asset data where appropriate |
| Texture replacement | Replace a Texture2D, rebuild its serialized object, rewrite the SerializedFile, repack UnityFS bundles, and save a replacement file |
| Batch texture replacement | Select multiple textures, choose an image folder, match by normalized filename, replace all matches, and package multi-container results as ZIP where required |
| ASTC preservation | Native ASTC texture formats can remain ASTC after replacement instead of always falling back to RGBA32 |
| Background processing | Heavy loading/processing continues under an Android foreground service while the app is backgrounded or the screen is locked |

## Texture replacement pipeline

```text
PNG/JPG
  ↓
TextureEncoder (mipmap generation + target-format encoding)
  ↓
Texture2DObjectRewriter (dimensions/format/mipmap/data-size fields)
  ↓
SerializedFileRewriter (object offsets/sizes + file header)
  ↓
BundleRepacker (UnityFS blocksInfo/directory rebuild + LZ4 compression)
  ↓
Saved .bundle / .unity3d / .assets / .zip
```

### Replacement support

| Item | Support |
| --- | --- |
| Original format preservation | RGBA32, ARGB32, RGB24, BGRA32, Alpha8, R8, RGB565, ARGB4444, DXT1, DXT5, ASTC 4x4/5x5/6x6/8x8 (RGB/RGBA) |
| Automatic fallback | Unsupported source formats are rewritten as RGBA32 with the format field updated |
| Mipmaps | Generated with a box filter and the original mip count is preserved |
| Dimensions | Source and replacement dimensions may differ; serialized width/height/data fields are updated |
| Containers | UnityFS Bundle, UnityWeb/Raw v6, plain `.assets`, ZIP-embedded assets, and nested WebFiles where supported |
| Limitation | Repacking pre-v6 bundles is not currently supported; some nested Crunch replacements become normal-format data |

## Native texture decoding

The NDK decoder supports:

- BC1–BC7 / DXT1/3/5 / BC4/5/6H/7
- ETC1 and ETC2 (RGB/A1/A8)
- EAC R/RG, including signed variants
- ASTC block sizes
- PVRTC 2bpp/4bpp
- ATC
- Crunch / Unity Crunch

Uncompressed formats such as RGBA32, RGB24, RGB565, ARGB4444, Alpha8, R8/R16 and palette/indexed formats are decoded in Kotlin where appropriate.

## Project structure

```text
app/src/main/
├── java/com/assetstudio/mobile/
│   ├── core/                      # AssetStudio C# core ported to Kotlin
│   │   ├── io/                    # Binary readers / file / gzip / Brotli
│   │   ├── bundle/                # Bundle / WebFile / LZ4 / LZMA / 7zLZMA
│   │   ├── serialized/            # SerializedFile / TypeTree / CommonString
│   │   ├── classes/               # Texture2D / Sprite / Mesh / MonoBehaviour etc.
│   │   ├── texture/               # Texture conversion + JNI decoder bridge
│   │   ├── manager/               # AssetsManager and source/reference tracking
│   │   └── math/                  # Vector / Matrix / Quaternion / Rect
│   ├── replace/                   # Texture/mesh replacement and bundle repacking
│   ├── export/                    # PNG / OBJ / text / raw export
│   ├── mcp/                       # MCP server integration
│   ├── ui/                        # Compose Material 3 UI
│   └── MainActivity.kt / MainViewModel.kt / App.kt
└── cpp/                           # Native texture decoders and JNI bridge
    ├── texture2ddecoder_jni.cpp
    └── decoder/                   # ASTC / BCn / ETC / PVRTC / ATC / Crunch
```

## Usage

1. Install the APK. Android 8.0+ is supported; **arm64-v8a is recommended**.
2. On the home screen choose **Open File** to select `.bundle`, `.unity3d`, `.assets`, `.zip`, etc., or choose **Load Folder** to scan an entire directory, including optional subdirectories.
3. Enter a Unity version when working with files whose version information has been stripped.
4. Open the asset list, filter by type, search, and tap an asset for details.
5. Use **Export** to save an asset with the appropriate extension.
6. Use the Mesh preview to inspect models and drag to rotate them.
7. Use **Replace Texture** on a Texture2D to select a new image. The app attempts to preserve the original texture format and can fall back to RGBA32 where necessary.
8. For large jobs, you can leave the app or lock the screen while the foreground-processing notification remains active.

## Building

Requirements:

- JDK 17
- Android SDK 34
- Android NDK 26.3.11579264
- CMake 3.22.1+

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<sdk>
./gradlew assembleDebug
```

Supported ABIs:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`
- `x86`

Minimum SDK: 26 (Android 8.0)  
Target SDK: 34

## Differences from desktop AssetStudio

- File access uses Android storage APIs and the application's file browser rather than assuming a desktop filesystem.
- The parser does not depend on runtime code generation for TypeTree deserialization; MonoBehaviour data is represented using the built-in TypeTree parser.
- Texture replacement is built into the Android application instead of requiring an external UABE workflow.
- Large asset operations use mobile-specific heap protection, batching, lazy loading, and foreground background processing.
- Mesh and audio export use mobile-friendly implementations; support differs from the desktop application for some specialized formats.

## License

AssetStudio is licensed under MIT, and this project follows the same license. Use the software for learning and research and do not use it to distribute copyrighted resources without permission.

---

Author: 醉莫
