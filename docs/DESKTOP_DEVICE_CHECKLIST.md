# Desktop Mode Device Test Checklist

Desktop Mode is experimental until every row below passes on one 6 GB and one 8 GB phone.
Nothing in Desktop Mode has been run on a device when this file is written: the code is
type-checked and its pure parts are unit tested on a JVM, and that is all. Work through
the rows in order and record the result of each. A row that fails is a bug to fix before
the next row, not a note.

Session: device, Android version, RAM, build (debug or release), date.

| ID | Check | Pass when | Result |
| -- | ----- | --------- | ------ |
| DT-1 | Launcher | A second launcher entry named Desktop Mode is present; the normal editor icon still opens the normal editor | |
| DT-2 | RAM gate | On a phone below about 5.5 GiB total RAM the screen says Desktop Mode needs 6 GB and starts nothing | |
| DT-3 | First start, no network | In airplane mode, first open unpacks the system and reaches the desktop; the progress text shows while it works | |
| DT-4 | Interrupted unpack | Force-stop the app during the unpack and reopen: it resumes and finishes | |
| DT-5 | All files access | With the permission off, the screen opens the system screen; after allowing it and coming back the desktop starts | |
| DT-6 | Desktop up | The desktop and VSCodium appear within 60 seconds after the first setup, on the workspace folder | |
| DT-7 | Save to shared storage | A file saved in the editor is visible in Android's file manager under the hidden workspace folder | |
| DT-8 | Toolchains, no install | In the editor's terminal, with no install step: `gcc --version`, `g++ --version`, `python3 --version`, `node -v`, `php -v`, `go version`, `rustc --version`, `git --version` all print | |
| DT-9 | Compile and run | A C file compiles with gcc and runs from the editor terminal | |
| DT-10 | Package managers | `npm install`, `python3 -m venv .venv`, `cargo build` and `go build` succeed in a project whose source is on shared storage | |
| DT-11 | Heavy folders private | The `node_modules` and `.venv` of DT-10 are physically in private storage and appear at the normal path in the project; deleting the project folder in the file manager leaves no orphan data after the next start | |
| DT-12 | Open VSX | An extension installs from the extensions view and activates | |
| DT-13 | Touch | Tap is a left click, long press is a right click, two fingers scroll, pinch zooms the picture | |
| DT-14 | Keyboards | The on-screen keyboard (side tab) types into the editor and the terminal without doubled text; a hardware keyboard works with Ctrl, Alt, F-keys and Ctrl+Shift+P | |
| DT-15 | Clipboard | Text copied in the editor can be pasted in Android and the other way round, through the side tab | |
| DT-16 | Rotation | Turning the phone between the two landscape orientations keeps the session | |
| DT-17 | Back and Stop | Back leaves the screen and the desktop keeps running; reopening shows it again; the notification Stop ends it and removes the notification | |
| DT-18 | Ten cycles | Ten start and stop cycles leave no stray proot, Xtigervnc, openbox or websockify process | |
| DT-19 | Background | Five minutes in the background, then back: the session is intact. Force-stop, then open: it starts cleanly | |
| DT-20 | Phantom processes | The one-time help dialog shows once; on Android 12 or newer, with the restriction left on, note whether the desktop survives a long background stay | |
| DT-21 | Secret | The viewer address needs the session secret: a second app that connects to port 6080 without it is refused by the VNC password | |
| DT-22 | Low memory | Under memory pressure the app shows the low memory message at most once every five minutes | |
| DT-23 | Aliases | `cls`, `copy`, `move`, `dir` work; `type ls` and `type cp` report the Linux programs; non-interactive scripts are unaffected | |
| DT-24 | Parity audit | `compgen -c` in the desktop terminal lists no command that exists only in this app; the only additions to stock Debian are the documented aliases | |
| DT-25 | Normal editor | The normal editor still passes its own checklist (docs/DEVICE_TEST_CHECKLIST.md) after Desktop Mode has been used | |

## Known gaps to look at first

These are the places the code is most likely to need a fix, because nothing has run them:

- The on-screen keyboard: the app relies on noVNC's own control and the screen pans
  rather than resizes while it is up (DT-14).
- Clipboard is only the noVNC panel, not automatic (DT-15).
- Landscape only; no portrait screen size and no UI scale setting.
- No extra-key row of the app's own: Esc, Tab, Ctrl and Alt come from noVNC's extra-keys
  panel, and the arrow keys from the phone's keyboard.
- The proot command line and `PROOT_NO_SECCOMP` fallback (DT-6, DT-18).
