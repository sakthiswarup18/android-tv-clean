# CLAUDE.md: TCL TV debloat and Clean TV launcher

Read this whole file and `README.md` before you run anything. This project changes a real television in the owner's home. A wrong command can leave it without a home screen.

## 1. What this project is

Two things for one TV, a **TCL 32S350G** (Google TV, Android 11 / SDK 30, board `G06_2K_US`, 1280x720, about 1.4 GB RAM, ABI `armeabi-v7a`):

1. **Debloat.** Unwanted TCL and Google packages are disabled over network ADB. Nothing is uninstalled, so everything can be undone.
2. **Clean TV launcher** (`tv.clean.launcher`). A small custom Android home screen that replaces the Google TV launcher: black background, clock, a dock of five apps, an app grid, and pills for Inputs, Wi-Fi and Settings.

Also installed: SmartTube (F-Droid build, 5th dock slot) and AdGuard Private DNS.

How it is built:

- **Shell and Python tools** on the Mac talk to the TV. `tools/tv` is a small ADB client using the Python `adb-shell` package from `.venv` (not Google's `adb` binary).
- **The launcher** is plain Java in a single Activity. Gradle 8.7, Android Gradle Plugin 8.5.2, compileSdk 34, minSdk 26, targetSdk 34, Java 17. The only library is `androidx.recyclerview`. No Kotlin, no Compose, no Leanback library.
- **TV address:** set `TV_HOST` to the TV's IP address on the home Wi-Fi. Port is `TV_PORT` (5555 by default).

The original job was done on 2026-10-24. The owner's open wishes: cast to the TV from a Windows laptop, a MacBook and an iPhone, and control it (including power) from their iPhone.

## 2. Folder map

```
.
├── README.md             The full record: what was disabled, what was kept, how to undo. Keep it current.
├── install.sh            Build the launcher, install it, make it Home
├── restore.sh            Re-enable everything, give Home back to Google TV, reset Private DNS
├── tools/
│   ├── tv, tv.py         ADB client: shell commands, key presses, screenshots, file push
│   ├── disable.sh        Disable the packages in list files, with safety checks
│   └── adb-closed.py     Proves network ADB is closed
├── lists/1..9-*.txt      Disable batches, in the order applied. One package per line, # comments.
├── backup/               TV state before any change, plus disabled-by-us.txt (what restore.sh re-enables)
├── launcher/             Android project "CleanTV"
│   └── app/src/main/
│       ├── java/tv/clean/launcher/MainActivity.java   All launcher logic (about 880 lines)
│       ├── java/tv/clean/launcher/HighlightView.java  The moving glass highlight
│       ├── res/values/config.xml    Dock apps, column count, hidden apps, labels, stats pill
│       ├── res/layout/              activity_main.xml, tile.xml
│       ├── res/drawable/            Shapes and icons (XML vectors), wallpaper.xml
│       └── assets/fonts/Outfit.ttf  The launcher typeface
├── shots/                Screenshots taken as evidence, numbered (00-baseline.png ...)
└── .venv/                Python environment for tools/tv. Generated.
```

Ignored by git: `.venv/`, `shots/`, `launcher/.gradle/`, `launcher/build/`, `launcher/app/build/`, `launcher/local.properties`, and everything in `backup/` except `backup/disabled-by-us.txt`.

## 3. Commands

Run these on the owner's Mac, on the same Wi-Fi as the TV. A cloud or sandboxed shell cannot reach the TV.

```bash
tools/tv 'getprop ro.build.version.sdk'            # run a shell command on the TV
tools/tv --keys DPAD_DOWN DPAD_RIGHT               # press remote keys
tools/tv --screenshot shots/20-name.png [KEYS]     # press keys, then capture the screen
tools/tv --push local.apk /data/local/tmp/x.apk    # copy a file to the TV (md5 checked)
tools/disable.sh lists/NAME.txt                    # disable a batch of packages
./install.sh                                       # build + install + set Home + show focused app
./restore.sh                                       # undo everything, then: tools/tv reboot
tools/adb-closed.py "$TV_HOST"                  # exit 0 = ADB closed, 1 = open, 2 = cannot tell
(cd launcher && ./gradlew -q assembleDebug)        # build only; APK in launcher/app/build/outputs/apk/debug/
```

- Build needs JDK 17+ and the Android SDK at `~/Library/Android/sdk`.
- **There are no automated tests.** Testing means installing on the TV and taking screenshots.
- **Network ADB is off by default.** To turn it on, the owner does this on the TV: Settings → System → About → press "Android TV OS build" 7 times → Developer options → USB debugging on. On this TV port 5555 only opens when USB debugging is switched off and on again in that menu. They accept the "Allow USB debugging?" prompt on first connect. You cannot do this step for him.

## 4. Rules for changing the TV

1. **Disable, never uninstall.** Use `pm disable-user --user 0` through `tools/disable.sh`. No `pm uninstall` of system packages, no `pm clear` on system packages, no root tricks.
2. **Always go through `tools/disable.sh`.** It refuses Android core packages, factory packages, and any list that would leave the TV with no Home app, and it records what it disabled in `backup/disabled-by-us.txt`. Do not bypass it with raw `pm disable` calls.
3. **Never leave the TV without a Home.** Before disabling any launcher, confirm Clean TV is installed and set as Home. Before removing Clean TV, run `restore.sh` first so the stock launcher is back.
4. **Never open `com.tcl.factory.view`** or any factory or service menu.
5. **Keep list:** Netflix, YouTube, YouTube Music, TCL screen mirroring (`com.tcl.ttvs`), Live TV and inputs (`com.tcl.tv`, `com.tcl.tvinput`), quick panel (`com.tcl.suspension`), remote and Bluetooth services, TCL key handlers, Play Store, Play Services, the keyboard, every Android core package.
6. **New package to disable:** check what it is first (`tools/tv 'dumpsys package NAME'`), add it to a new numbered file in `lists/` with a comment saying why, run `disable.sh` on that file, screenshot the home screen, and add it to `README.md`.
7. **Ask before each of these:** disabling anything not already listed, changing a system setting, installing an APK, rebooting the TV.
8. **APKs from outside:** only from a source the owner names. Record the URL, version, signer and SHA-256 in `README.md`, as was done for SmartTube.
9. **Close the door after.** When the job is finished, ask the owner to switch USB debugging off, then run `tools/adb-closed.py`. Only "connection refused" (exit 0) counts as closed.
10. **Known and expected:** the firmware re-enables `com.tcl.usercenter` and `com.tcl.smartalexa` at every boot. That is not a failure. Chromecast (`com.google.android.apps.mediashell`) is disabled, so casting needs it re-enabled or uses TCL screen mirroring.
11. **Disabling `setupwraith` deleted Wi-Fi networks it had saved.** Tell the owner before any change that could drop the TV off Wi-Fi, because ADB goes with it.

## 5. Coding rules (launcher and tools)

1. **Java only, one Activity.** Keep the logic in `MainActivity.java`. Do not add Kotlin, Compose, Leanback, image loaders or any other dependency. The TV has 1.4 GB RAM.
2. **Things the owner may want to change go in `res/values/config.xml`** (dock apps, columns, hidden apps, labels, stats pill), not in Java constants.
3. **Focus is handled by hand.** `dispatchKeyEvent` drives a row/column model: row `TOP` (-1) is the pill bar, `DOCK` (0) is the dock, 1..n are grid rows. Do not swap this for Android's default focus search. Any new focusable thing must be added to that model.
4. **No background work.** No services, no receivers that run when Home is not visible. The stats pill reads local files every 3 s only while Home is on screen. Stop timers in `onPause`.
5. **Slow work off the main thread.** App loading and icon colour sampling run on the single background executor and post results back to the UI handler.
6. **Keep the manifest pieces that look optional but are not:** the `<queries>` block (without it the app grid is empty on Android 11+), location permission (Android only gives the Wi-Fi name to apps that hold it), `HOME` and `DEFAULT` categories, `singleTask`.
7. **This TV blocks `/proc/stat` and thermal zones.** The stats pill falls back to CPU clock. Do not "fix" that by asking for more permissions.
8. **Shell scripts are POSIX `sh`**, start from their own directory, and call `tools/tv`. Python tools use only `adb-shell` and the standard library.
9. **File transfer:** use `tools/tv --push`. The ADB sync service hangs on large files on this TV, so the tool sends base64 chunks and checks md5. Do not replace it with a plain push.
10. **Screenshots** come back empty when Live TV, HDMI or a DRM app is on screen. Go Home first.
11. Change only what the task needs. Match the existing style. Do not edit `backup/` (it is the record of the original state) and do not delete files in `shots/`.

## 6. Design rules (launcher)

- Apple TV feel: pitch-black background, lots of space, nothing decorative.
- Typeface: Outfit. The clock is Outfit Thin, with a greeting and the date.
- A pill bar above the dock: frosted pills for stats, Inputs, Wi-Fi name and Settings, in that order.
- A dock band of `columns` apps (default 5) with the focused app's name above it and a soft glow in that app's sampled colour. The rest of the apps sit in a grid below with the same number of columns.
- One glass highlight slides between tiles. Focused tiles scale to 1.08. Movement is short and decelerating.
- Remote behaviour: Up/Down moves exactly one row, Up from the dock goes to the pills, Back returns to the dock, and the last focused tile is remembered.
- Inputs opens a dropdown: HDMI 1, HDMI 2, AV, Live TV.
- Design for 1280x720 seen from a sofa: large text, high contrast, a dark wallpaper if one is used.
- Use XML vector drawables and shapes. No bitmap UI assets apart from an optional wallpaper.

## 7. Mistakes a weaker model makes here, and how to avoid each

| Mistake | Why it is wrong | Do this |
|---|---|---|
| Running `adb ...` | The project uses its own client; `adb` may not be installed or authorised | Use `tools/tv` |
| Trying to reach the TV from a cloud or sandboxed shell | It is on a home LAN | Run on the Mac, or give the owner the commands |
| Assuming ADB is on | It is switched off after each job | Ask the owner to enable USB debugging (section 3) |
| `pm uninstall` on a system app | Cannot be undone without a factory reset | `pm disable-user --user 0` through `disable.sh` |
| Disabling "unknown" `com.tcl.*` packages in bulk | Some run the remote, inputs and Live TV | Check the keep list, look each one up, one small batch at a time |
| Disabling a launcher before the replacement is Home | Black screen with no way to navigate | Install and verify Clean TV first; `disable.sh` also checks |
| Treating `usercenter` or `smartalexa` being enabled after reboot as a bug | The firmware does it every boot | Leave them; hide Alexa from the grid with `hidden_apps` |
| Promising to block YouTube ads with DNS | YouTube ads come from the same servers as the videos | Say so; SmartTube is the answer already installed |
| Removing the `<queries>` block or location permission as "unused" | Empty app grid, missing Wi-Fi name | Keep them (rule 6) |
| Adding Leanback, Glide, Kotlin or Compose | Heavier app on a 1.4 GB TV, and a rewrite nobody asked for | Plain Java and the existing views |
| Letting Android handle D-pad focus | Breaks one-row moves and remembered focus | Extend the row/column model |
| Hardcoding a package in Java | The owner edits `config.xml` | Put it in `config.xml` |
| Saying the launcher works after a successful Gradle build | A build proves nothing about the TV | Run `./install.sh` and take screenshots |
| Leaving ADB open when finished | An open debug port on the home network | Ask the owner to turn it off and run `adb-closed.py` |
| Reading a timeout from `adb-closed.py` as "closed" | Timeout is inconclusive (exit 2) | Only exit 0 means closed |
| Rebooting or changing settings without asking | It is the owner's TV and someone may be watching | Ask first |
| Editing `backup/` or forgetting `disabled-by-us.txt` | `restore.sh` depends on them | Never hand-edit `backup/`; let `disable.sh` write the log |
| Not updating `README.md` | It is the only record of what was done to the TV | Add every change there |

## 8. Checklist before you say a task is done

1. I did what was asked and nothing extra, and I asked before anything in section 4, rule 7.
2. Every package I disabled is in a `lists/` file with a reason, in `backup/disabled-by-us.txt`, and in `README.md`.
3. The TV still has a working Home: `tools/tv 'dumpsys activity activities | grep -E "mFocusedApp|topResumedActivity"'` shows `tv.clean.launcher` (or the stock launcher after a restore).
4. Launcher changes: `./install.sh` ran without errors and I took screenshots into `shots/` of the home screen, the dock, the grid, the pills and the Inputs menu.
5. I pressed through it with `tools/tv --keys`: Left/Right along the dock, Down into the grid, Up to the pills, Back to the dock, Home.
6. Netflix, YouTube and Live TV still open.
7. `restore.sh` would still undo everything I did.
8. `README.md` describes the new state.
9. The owner has been asked to switch USB debugging off, and `tools/adb-closed.py` returned exit 0. If they want it left on, I said so in the report.
10. My report says what changed, what I verified on the TV and how, and what I could not verify. If I could not reach the TV, I say that plainly and do not claim anything about its state.
