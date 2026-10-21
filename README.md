# Morning Gate

Wake-up alarm + morning lockdown for Android (Pixel 9a).

## How a morning goes

1. **Wake time.** At the wake time the gate comes up on its own — no manual
   start. Every app is blocked until every task is verified. The phone also
   rings loudly (alarm volume, vibration) for every task with **Auto start**
   enabled; the ring stops only when that task's checks are verified, or
   through the emergency bypass. The seeded "Wake up" task (50 steps within
   10 min) is a starting point — edit it like any other task.
2. **Dwell tasks are started by hand.** Once the gate is up, a task with a
   phone-away check shows **Start dwell**. Tap it, then turn the screen off and
   put the phone down. Dwell never accrues on its own after the alarm, so lying
   in bed earns nothing. Picking the phone up wipes the current dwell and the
   task must be started again.
3. **Allowed apps.** Pick apps that stay usable while the gate is up (music,
   maps, whatever you actually need). They appear as small shortcut chips on
   the gate screen. Calls always work.
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

Status: v0.3 — auto-starting gate, wake alarm, manually started dwell checks,
allowed-app shortcuts, tap bypass.
