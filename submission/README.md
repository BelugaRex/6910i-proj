# Nutrition Label Assistant for Smart Glasses

A **barcode-first, nutrition-label fallback** assistant for smart glasses (Rokid, Meta Ray-Ban, RayNeo, Brilliant Labs Frame, Omi), built on the [xg-glass SDK](https://github.com/hkust-spark/xg-glass-sdk).

Point the glasses camera at a packaged food or drink — the app reads the barcode, queries [Open Food Facts](https://world.openfoodfacts.org/), computes a nutrition grade (A–E), estimates whole-package sugar, and displays a concise verdict on the glasses display.

## Architecture

```
Capture Photo (1920×1080)
  → ZXing algorithmic barcode decode
    → success? → Open Food Facts lookup → Local grading → Display
    → failure? → Vision LLM barcode reading → Open Food Facts lookup → Local grading → Display
```

If Open Food Facts has no nutrition data for the product, the system falls back to a **category-average search** across similar products, or prompts the user to use the **Analyze Nutrition** command (vision-based OCR of the nutrition facts label).

## Features

- **Two commands**: `Scan Barcode` (primary) and `Analyze Nutrition` (fallback)
- **ZXing + Vision LLM hybrid** barcode decoding — algorithmic first, AI as backup
- **Open Food Facts integration** for structured nutrition data
- **Local grading engine** for drinks and foods (sugar, calories, sodium, saturated fat, protein, fiber)
- **Whole-package sugar estimation** using OFF `quantity` / `serving_size` fields
- **Simulator-first development** — test with Android emulator + PC webcam or local video

## Verified Results

| Input | Barcode | Product | Grade | Display |
|-------|---------|---------|-------|---------|
| Simulator + local video | `5449000214911` | Coca-Cola | E | Grade E · 高糖 · 整瓶 糖≈35g · 约8块方糖/瓶 |
| Real webcam | — | — | — | Photo capture works; barcode recognition pending |

> **Note**: The barcode pipeline is end-to-end validated on simulator + local video. Real webcam photo capture succeeds, but barcode recognition stability is still being improved.

## Prerequisites

- **JDK 21**
- **Android SDK** (API 34)
- **[xg-glass SDK](https://github.com/hkust-spark/xg-glass-sdk)** installed locally
- An OpenAI-compatible Vision API key (e.g., [Manifest Build](https://app.manifest.build/), OpenAI, or any `/v1/chat/completions` endpoint)

## Quick Start

```bash
# Clone and enter the project
cd nutrition-label-assistant

# Run with simulator (auto-connects, uses PC webcam)
xg-glass run ug_app_logic/src/main/java/com/example/xgglassapp/logic/NutritionLabelEntry.kt --sim

# Run with a repeatable local video for validation
xg-glass run ug_app_logic/src/main/java/com/example/xgglassapp/logic/NutritionLabelEntry.kt --sim --local_video /path/to/barcode_video.mp4
```

On first run, open the host app Settings and configure:
- **API Base URL** (default: `https://app.manifest.build/v1/`)
- **API Key**
- **Model** (default: `auto`)

## Project Structure

```
├── app/                          # Android host app
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/.../MainActivity.kt
│       └── res/
├── ug_app_logic/                 # Business logic module
│   ├── build.gradle.kts
│   └── src/main/java/.../logic/
│       ├── NutritionLabelEntry.kt  # Core implementation
│       └── ExampleAppEntry.kt      # Entry wrapper
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── xg-glass.yaml                # SDK config
└── README.md
```

## Dependencies

- **xg-glass SDK** — smart glasses abstraction layer
- **OpenAI Kotlin client** (`com.aallam.openai:openai-client`) — Vision LLM integration
- **ZXing** (`com.google.zxing:core`) — algorithmic barcode decoding
- **Ktor** (`io.ktor:ktor-client-okhttp`) — HTTP engine
- **Open Food Facts** public API — product nutrition database

## Limitations

- Barcode decoding is sensitive to angle, blur, glare, and size
- Open Food Facts does not cover all products (especially local/small brands)
- Some OFF entries have incomplete nutrition fields
- Vision-only nutrition label OCR is less reliable than structured database lookup
- Real-device (Rokid/Meta/RayNeo) testing not yet completed

## License

This project is part of HKUST ELEC6910I coursework.
