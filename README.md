# Morning Gate

Morning lockdown for Android (Pixel 9a). Between 07:00 and the 11:00 hard
release, all apps stay blocked behind the gate until every configured daily
task is verified by real-world evidence:

- phone-away dwell (screen off, untouched, still)
- step-counter increase within a window
- NFC tag tap (proof of place; tag UID is factory-set and read-only)
- QR / barcode scan
- time spent inside a chosen app

Guardrails: the dialer always works, hard auto-release at 11:00, fail-open
with a visible warning if verification itself breaks. Config is locked while
gated; night mode (Focus Lock) always wins at overlap.

Fully offline: framework-only Kotlin, no INTERNET permission, no network code.

Status: v0.1 scaffold — gate engine (7–11 window), criteria engine, and task
UI are next.
