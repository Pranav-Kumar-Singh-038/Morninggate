# Morning Gate

Wake-up alarm + morning lockdown for Android (Pixel 9a). Fully offline:
framework-only Kotlin, no `INTERNET` permission, no network code.

## Current capability (v0.5)

- **Wake alarm.** At the wake time the gate arms itself, the phone rings on the
  alarm stream (looped sound + vibration), and a full-screen mission screen
  comes up over the lock screen. A high-priority notification also names the
  task that is keeping the alarm on. The ring stops only when every auto-start
  task is verified, a no-check task is marked done, or the emergency bypass is
  completed.
- **30-second check pause.** Opening a QR, barcode or NFC check pauses the
  ringing for 30 seconds, so the camera/NFC read is not drowned out. The check
  screen confirms the pause; the alarm screen shows the remaining seconds. If
  the check is still pending after 30 seconds, the alarm rings again.
- **Tasks with no checks.** A task can be saved with zero completion checks.
  It appears on the gate and alarm screens with a **Mark done** button. This is
  useful for a plain reminder ("take the bins out") that still belongs in the
  morning routine.
- **Checks.** Dwell timer (clock time, one task at a time), step-counter
  increase inside a window, NFC tag UID, QR / barcode payload, and time spent
  in chosen apps. Two or more checks on one task is "hard mode".
- **Allowed apps.** Chosen apps stay usable while the gate is up; keyboards are
  always allowed, and calls always work. Allowed apps appear as shortcut chips
  on the gate screen.
- **Emergency bypass.** A separate screen: 1000 deliberate taps, never pausing
  more than 2 seconds. Completing it releases the gate and silences the alarm
  until tomorrow.
- **Access lifecycle.** When a session ends, the overlay is removed, the
  accessibility watch is switched off, and camera + physical-activity access
  are scheduled for revocation on Android 13+. The next morning asks for
  activity access on the alarm screen when a steps check is pending, and the
  scanner asks for camera when it is opened.

## How a morning goes

1. **Wake time.** The gate arms itself at the configured wake time. Every app
   is blocked until every task is verified. Auto-start tasks ring the alarm;
   the seeded "Wake up" task (50 steps within 10 min) is a starting point and
   can be edited like any other task.
2. **The alarm tells you what to do.** The alarm screen lists the pending
   auto-start tasks and their checks. The wake-mission notification repeats the
   task name in the shade and on the lock screen.
3. **Doing a scan or NFC check.** Tapping **Scan QR**, **Scan barcode** or
   **Tap tag** opens the check screen and pauses the alarm for 30 seconds. The
   alarm screen changes to `ALARM PAUSED · Ns`. If the check is not complete
   when the pause ends, the alarm starts again automatically.
4. **Dwell tasks are started by hand.** A dwell task shows **Start dwell** on
   the gate screen. The timer runs on clock time until the required minutes are
   complete; the screen can stay on and the phone can be used. Only one dwell
   timer runs at a time: starting another task resets the previous timer.
5. **No-check tasks.** Finish the thing, then tap **Mark done** on the gate or
   alarm screen. If it is an auto-start task, the alarm stops as soon as it is
   marked.
6. **End of session.** Completing everything, bypassing, stopping the gate or
   reaching the hard release time all tear the session down. The next wake
   alarm is scheduled before the service stops.

## Checks

| Check | What proves it |
| --- | --- |
| Dwell | clock time after tapping **Start dwell** (one task at a time) |
| Steps | step-counter increase within the configured window |
| NFC tag | tap of the bound tag UID (factory-set, read-only) |
| QR / barcode | scan of the bound payload; you can name each code |
| App time | time spent inside one of the chosen apps |
| No check | an explicit **Mark done** tap |

## Setup / changes needed on the phone

1. Install the debug APK (or build with `./gradlew assembleDebug`).
2. Grant **Display over other apps**, **Accessibility watch**, **Usage access**,
   **Notifications**, **Ignore battery optimization**, and exact-alarm access
   if the Android version asks for it. The setup screen links each one.
3. Grant **Physical activity** and **Camera** when the morning asks for them.
   These two are deliberately revoked after a session, so expect the grant
   prompt again on the next morning (activity on the alarm screen, camera when
   opening the scanner).
4. For automatic accessibility off/on, run the one-time ADB grant:

   ```sh
   adb shell pm grant com.pranav.morninggate android.permission.WRITE_SECURE_SETTINGS
   ```

   Without it the app can still switch accessibility **off** through
   `disableSelf()`, but Android needs the user (or this grant) to switch it back
   on before the next wake time.
5. Keep the **Wake alarm mission** notification channel enabled and at high
   importance. The alarm itself uses the alarm audio stream; the notification
   is the visible mission notice.

### Upgrading from v0.4

- The wake alarm now pauses for 30 seconds when a scan/NFC check opens. No
  setup change is needed.
- Tasks may now be saved with zero checks. Existing tasks are unchanged.
- Camera and physical-activity permissions are revoked after each session on
  Android 13+. The next morning will ask again when those checks are needed.
  Overlay, usage access, notifications, exact alarms and the ADB
  `WRITE_SECURE_SETTINGS` grant are intentionally kept.

## Decisions and limits

- **Why 30 seconds?** It is long enough to open the scanner, frame a code or
  find a tag, and short enough that putting the phone down does not buy a long
  silent stretch. The pause is only triggered by the QR/barcode/NFC check
  screens, not by opening any other app or by dismissing a dialog.
- **Why a separate high-priority notification?** The foreground-service
  notification is low importance by design. The wake mission needs to be
  visible over the lock screen, so it uses its own channel with no sound (the
  alarm stream already provides the sound) and no vibration.
- **Why no-check tasks are manual.** Treating an empty task as automatically
  done would make it meaningless and would let an auto-start task silence the
  alarm instantly. A no-check task therefore stays pending until the user taps
  **Mark done**.
- **Why only camera + activity are revoked automatically.** Android lets an app
  schedule revocation of its own runtime permissions (Android 13+,
  `revokeSelfPermissionsOnKill`). It does not let an app revoke overlay,
  usage-access, notification or secure-settings grants. Those core grants are
  kept so the next wake alarm can still arm, show its notice, block apps and
  switch accessibility back on.
- **Revocation is asynchronous.** The system performs it when the app is safely
  in the background and then kills the process. It is not an instant wipe while
  the completion toast is still on screen. Previews (`Preview 2 min`) skip the
  cleanup so testing does not repeatedly remove permissions.
- **Accessibility is still the special case.** With `WRITE_SECURE_SETTINGS` the
  watch is switched off after the morning and back on five minutes before the
  next wake time. Without the grant, the app switches it off and posts a
  reminder; the gate keeps working through usage access.
- **Fail-open.** If verification itself breaks, the gate shows a warning and
  the hard release time still opens the phone. Calls always work.

Status: v0.5 — wake alarm with mission notice and 30-second check pause,
zero-check tasks with manual completion, and automatic post-session revocation
of camera / physical-activity access.
