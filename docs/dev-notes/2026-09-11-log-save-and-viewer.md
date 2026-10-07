# Logs: Save as, Share/Save row, full-screen viewer — 2026-09-11

🧪 VERIFYING — checked on the OP9 by Claude only. Waits on the maintainer, and on mirror176 saying he
can now get a report off his phone. What settles it is at the bottom.

## What was asked

- **mirror176, #28:** "Is there a way to access the debug log as a file on the phone?"
- **mirror176, #29 (2026-09-05):** tried to find the log to `adb pull` it; the closest he found was the
  Share button naming `callvault_debug_report.txt`, and `find /storage/self/primary/` found nothing.
- **Maintainer, 2026-09-11:** add Save as a *Save as* (the user picks where); replace the single
  "Share debug logs" button with two buttons, *Share* and *Save*, in one row; fix the log viewer popup,
  after looking at it on the OP9 first. Viewer design chosen: **full screen, wrap toggle**.

## Why the log could not be reached

The reports are built into the app's private cache. A file manager cannot see that, and `adb pull` /
`run-as` cannot reach it on a release build, which is not debuggable. Share was the only way out.

## The viewer before (OP9, 2026-09-11)

- Portrait: a small popup. Every line cut off at the right edge, no sign it scrolled either way, opened
  at the oldest lines (the setup journal), and left empty space below.
- Landscape: rotating closed it, because `showLogViewer` used `remember`.

## What changed

| Change | Where |
|---|---|
| *Save* opens Android's save dialog (`CreateDocument("text/plain")`), suggested name `callvault_report_YYYY-MM-DD_HH-MM.txt`. No storage permission. | `SettingsScreen.kt` |
| The dialog makes one file, so both reports go into it, each under a `===== <file name> =====` header; the system half is left out if it could not be collected in time. | `ReportBundle.kt` (+ test) |
| Share and Save build the report through the same function, so both hand over the same thing. | `buildReports()` in `SettingsScreen.kt` |
| *Share* and *Save* side by side, equal width: `CvPrimaryButton` + `CvSecondaryButton`. The first build used a plain M3 `Button`, which is shorter than the 52 dp Cv buttons; seen on the OP9 and fixed. | `BugReportSection` |
| Viewer is a full-screen dialog: back arrow, title, *Wrap lines* switch (on by default). Wrap off = one line per row and sideways scrolling. | `DebugLogViewer` |
| Visible scrollbars (vertical always, horizontal when wrap is off). | `ScrollbarThumb.kt` (+ test) |
| Opens at the newest lines, once per opening, so a rotation does not throw away where you scrolled. | `DebugLogViewer` |
| Viewer open state and the wrap switch survive rotation (`rememberSaveable`). | `BugReportSection`, `DebugLogViewer` |

## Checked on the OP9 by Claude (🧪, not maintainer-verified)

| Check | Result |
|---|---|
| Share and Save in one row, same height | seen after the height fix |
| Viewer in portrait, wrap on | fills the screen, lines wrap, opens at the end, scrollbar shows |
| Wrap off | one line per row, horizontal scrollbar at the bottom |
| Rotate to landscape with the viewer open | stays open, wrap stays off, vertical scrollbar shows |
| Save | dialog opened with `callvault_report_2026-09-11_14-55.txt`; "Preparing debug report…" showed; file written to `/sdcard/OP9Recordings/`, 27,631 bytes, debug header on line 1, system header on line 63 |

Unit tests: 1293, 0 failures (includes `ReportBundleTest` and `ScrollbarThumbTest`, both seen RED first).

## Known limits, left as they are

- In landscape the dialog leaves a strip on the camera side and on the navigation-bar side.
- The viewer shows the last 500 lines (`LOG_VIEW_LINES`); Share and Save carry the whole report.
- The hint still says "Logs from your last debug session are ready to share" when there was no debug
  session (noted before this work, not changed here).

## To settle it

- Maintainer: Settings ▸ Debug ▸ *Save*, pick a folder, open the file in a file manager. Then open
  *Log file*, rotate the phone, and flip *Wrap lines*.
- mirror176: confirm on the S20 FE that *Save* puts a file where he can `adb pull` it.
