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
   dwell check shows **Start dwell**. The timer then runs on clock time
   until the task's required minutes are complete — the screen can stay on and
   the phone can be used; there is no screen-off or "put it down" requirement.
   Only **one dwell timer can run at a time**: starting a second task resets
   the first task's timer. Each task shows elapsed time out of its total.
3. **Allowed apps.** Pick apps that stay usable while the gate is up (music,
   maps, whatever you actually need). Keyboards are always allowed, so typing
   in an allowed app never summons the gate. Allowed apps appear as small
   shortcut chips on the gate screen. Calls always work.
4. **Emergency bypass.** A separate screen: tap it 1000 times without pausing
   more than 2 seconds. Completing it releases the gate and silences the alarm
   until tomorrow.

Tasks can be reordered with the **↑ / ↓** controls in the setup screen. The
gate screen uses that custom order.

## Checks

- dwell timer — clock time after tapping Start (one task at a time)
- step-counter increase within a window
- NFC tag tap (proof of place; tag UID is factory-set and read-only)
- QR / barcode scan — you name each code when binding it, and the gate shows
  that name so you know which code it's demanding
- time spent inside a chosen app

## Accessibility watch

The accessibility watch makes app blocking instant, but keeping it enabled all
day can make banking and payment apps (Paytm, etc.) refuse to run. With
**Turn accessibility off after the morning** enabled, Morning Gate switches the
watch off when the day's tasks are done, bypassed, or auto-released, and tries
to switch it back on five minutes before the wake time.

Android does not allow a normal app to turn accessibility back on. To make the
whole cycle automatic, grant the one-time permission over ADB:

```sh
adb shell pm grant com.pranav.morninggate android.permission.WRITE_SECURE_SETTINGS
```

Without the grant, Morning Gate still switches the watch **off** (an
accessibility service can disable itself), then posts a quiet reminder before
the wake time and offers an **Accessibility is off** button on the gate screen.
The gate itself keeps working through usage access in the meantime.

## Guardrails

The dialer always works. Hard auto-release at the configured release time.
Fail-open with a visible warning if verification itself breaks. Config is
locked while gated; night mode (Focus Lock) always wins at overlap.

Fully offline: framework-only Kotlin, no INTERNET permission, no network code.

Status: v0.4 — auto-starting gate, wake alarm, single manually started dwell
timer, keyboard-aware allowed apps, custom task order, automatic accessibility
off/on when permitted.
