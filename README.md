# Morning Gate

Wake-up alarm + morning lockdown for Android (Pixel 9a).

## How a morning goes

1. **Wake alarm.** At the wake time (default 07:00) the phone rings loudly
   (alarm volume, vibration) for every task with **Auto start** enabled. The
   ring stops only when that task's checks are verified, or through the
   emergency bypass. The seeded "Wake up" task (50 steps within 10 min) is a
   starting point — edit it like any other task.
2. **The gate is manual.** Nothing locks on its own: after the alarm, tap
   **Start the gate** when you're up, and every app stays blocked until every
   task is verified. Phone-away dwell can never accrue while you're asleep.
3. **Allowed apps.** Pick apps that stay usable while the gate is up (music,
   maps, whatever you actually need). Calls always work.
4. **Emergency bypass.** A separate screen: tap it 1000 times without pausing
   more than 2 seconds. Completing it releases the gate and silences the alarm
   until tomorrow.

## Checks

- phone-away dwell (screen off, untouched, still)
- step-counter increase within a window
- NFC tag tap (proof of place; tag UID is factory-set and read-only)
- QR / barcode scan — you name each code when binding it, and the gate shows
  that name so you know which code it's demanding
- time spent inside a chosen app

## Guardrails

The dialer always works. Hard auto-release at the configured release time.
Fail-open with a visible warning if verification itself breaks. Config is
locked while gated; night mode (Focus Lock) always wins at overlap.

Fully offline: framework-only Kotlin, no INTERNET permission, no network code.

Status: v0.3 — wake alarm, manual gate start, allowed-app list, tap bypass.
