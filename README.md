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

## Privacy and trust
- No ads, analytics, crash reporting or accounts. Screen frames are processed on the device and never uploaded.
- The only network connections: a TCP handshake to `8.8.8.8:443` for the ping readout, and, **only when you tap
  the button** on the network details page, `speed.cloudflare.com` (speed test) and Cloudflare + `ipinfo.io`
  (public IP lookup). All HTTPS, redirects refused, system certificate authorities only.
- Backups and device-to-device transfer are disabled; settings stay in the app's private storage.
- Every sensitive permission (accessibility, notification access, usage access, microphone, secure settings) is
  optional and explained before it is requested. The full policy is in the app under About > Privacy policy.
- Verify what you installed: About > Verify this app shows the signing certificate; see `SECURITY.md`.
