# Vyze's offline guarantee — the precise claim

Vyze is an offline-first accessibility assistant for blind and low-vision
users. This document states exactly what "offline" means, what is
machine-verifiable, and one known exception with its risk analysis.

## The three-layer proof

**Layer 1 — Zero network code in the app.**
The codebase contains no HTTP client, no socket, no URL fetch: no OkHttp,
no Retrofit, no Ktor, no `HttpURLConnection`, no `java.net.URL` usage.
All AI inference is on-device: Gemma via LiteRT-LM, OCR via ML Kit,
routing via `StudentRouter` (distilled offline rules — see below).

**Layer 2 — Debug/release source-set split.**
The Phase 1 transcript exporter (`InteractionLogExporter` +
`InteractionLogExportReceiver`) lives ONLY in `app/src/debug/`. The
release variant never compiles these classes; there is no flag, no dead
code, no reflection. The merged **release** manifest contains no receiver
and no app-declared INTERNET permission (verified every build via
`app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml`).

**Layer 3 — The Jev harness never touches a device.**
`tools/jev_harness/` runs on the dev machine (cloud Jev API). Its only
output that ships is *knowledge*: the distilled `StudentRouter` rules and
frozen eval fixtures. No Jev code, no Jev credentials, no Jev calls exist
in any APK.

## The known exception — ML Kit's transport dependency

**Fact:** the merged release manifest DOES contain
`android.permission.INTERNET` — but it is not declared by Vyze. It is
merged in from Google's `com.google.android.datatransport:transport-backend-cct`,
pulled transitively by the ML Kit text-recognition (OCR) and
barcode-scanning artifacts. (A second rider previously came from
`com.google.mlkit:genai-prompt` via Google ADK; that dead dependency was
excluded — Vyze never imported `com.google.mlkit.genai`.) Vyze's own code
never invokes this transport; it is Google's telemetry plumbing, inert
without an enqueuer (Vyze uses no Firebase/analytics).

**What this means for claims:**

| Claim | Status |
|---|---|
| "Vyze's app code performs zero network communication; all inference and storage are on-device" | ✅ True, verifiable by code inspection |
| "Vyze declares no network permissions of its own" | ✅ True — the sole INTERNET declaration comes from Google's ML Kit dependency manifests |
| "The APK contains no INTERNET permission" | ❌ Do NOT claim — the merged manifest carries it |

**Optional hardening (deliberate decision, NOT done):**
`<uses-permission android:name="android.permission.INTERNET"
tools:node="remove"/>` in the app manifest would strip the
library-declared permission. This is a runtime-risk decision: it must be
validated against every ML Kit path (OCR, genai-prompt) on real hardware
before shipping, and re-validated on every ML Kit upgrade. Until someone
runs that validation, the honest table above is the position.

## Verification commands

```sh
# What the release APK actually requests (on the final artifact):
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk

# Which component declared INTERNET (after any dependency change):
grep -B 2 -A 2 INTERNET app/build/outputs/logs/manifest-merger-release-report.txt

# Confirm the dead genai-prompt exclusion stays in effect (expect 0):
./gradlew :app:dependencies --configuration releaseRuntimeClasspath \
  | grep -c "genai-prompt"

# Confirm the exporter never reaches release:
grep -c InteractionLogExportReceiver \
  app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml   # expect 0
```

## What ships where

| Component | Release APK | Debug APK | Dev machine |
|---|---|---|---|
| `StudentRouter` (distilled rules) | ✅ | ✅ | — |
| Jev harness (`tools/jev_harness/`) | — | — | ✅ (cloud API) |
| Transcript exporter + receiver | — | ✅ (local file only) | pulls via adb |
| INTERNET use by Vyze code | none | none | harness only |
