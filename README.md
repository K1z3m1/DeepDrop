# Deepdrop (package `app.fluxa`)

Kotlin sources are grouped by feature under `app/src/main/java/app/fluxa/`. Every file keeps the single package
`app.fluxa`, so moving a file between folders never needs an import change.

| Folder | What lives there |
|---|---|
| `engine/` | Frame-generation / capture core: `Fluxa`, `FluxaNative` (JNI, keep its name), `CaptureService`, `ScreenRecorder`, `DisplayTuner` (upscale / enhance / colour), `StatsStore` |
| `panel/` | The in-game panel (`DrawerOverlay`), dials, button grid, phone buttons, accessibility / notification services |
| `home/` | Main screen, home launcher and monitor, network tools, profile, compare, about |
| `onboarding/` | Consent, permissions, setup, record consent, legal text |
| `ui/` | Shared look and feel: `Ui` (Kit / Palette), `Glass`, `Glyphs`, `TabIcon`, `AppSettings`, `I18n` |

Native code is in `app/src/main/cpp/` (`dis/` = optical-flow engine, `shared/presenter/` = upscaler / enhancer).
