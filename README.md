# TCL 32S350G: debloat + Clean TV launcher

TCL 32S350G, Google TV on Android 11 (SDK 30), board `G06_2K_US`, 1280×720, ~1.4 GB RAM.
Built and tested over network ADB on the home Wi-Fi.

## What's here

| Path | What it is |
|---|---|
| `backup/` | State before any change: package lists, `getprop`, `settings list system/secure/global`, Home activities, Private DNS values. `disabled-by-us.txt` lists every package this project disabled. |
| `lists/*.txt` | The disable batches, in the order they were applied. |
| `restore.sh` | Re-enables everything in `backup/disabled-by-us.txt`, gives Home back to the Google TV launcher and resets Private DNS. |
| `install.sh` | Builds the launcher, installs it and makes it Home. |
| `tools/tv` | ADB client (Python `adb-shell` in `.venv`): `tools/tv 'shell cmd'`, `tools/tv --keys DPAD_DOWN`, `tools/tv --screenshot shots/x.png [KEYS]`, `tools/tv --push local remote`. Needs `TV_HOST` set to the TV's address; `TV_PORT` defaults to 5555. |
| `tools/disable.sh` | Disables the packages in the given lists, one `pm` call each. Refuses Android core packages, factory packages, and any list that would leave no Home app. |
| `tools/adb-closed.py` | Checks that network ADB is closed (only "connection refused" counts). |
| `launcher/` | The Clean TV launcher source (Java, minSdk 26, targetSdk 34). |

## What was disabled

Disabled with `pm disable-user --user 0`. Nothing was uninstalled.

- **Telemetry:** `com.google.android.feedback`, `com.tcl.usercenter`
- **TCL content/ads:** `com.tcl.tv.plus` (TCL tv+), `com.tcl.tv.tclchannel` (TCL Channel), `com.tcl.channelplus` (TCL Live IPTV), `com.tcl.dashboard` (TCL HOME/gift box), `com.tcl.messagebox`, `com.tcl.esticker` (store demo sticker), `android.autoinstalls.config.google.gtvpai` (Play auto-installs)
- **Background:** `com.tcl.guard` (TGuard cleaner), `com.google.android.apps.nbu.smartconnect.tv` (data saver/VPN)
- **Voice/cast (your choice):** Alexa (`com.tcl.smartalexa`), Chromecast (`com.google.android.apps.mediashell`)
- **Preloaded (your choice):** Media Center, T-Solo, ambient screensaver (`dreamx`), Play Games
- **Streaming (your choice):** Prime Video, JioHotstar, SonyLIV, ZEE5, Jio On Demand, MX Player, Google TV / Play Movies (`com.google.android.videos`)
- **Launcher takeover:** `com.google.android.apps.tv.launcherx` (Google TV home), `com.google.android.tungsten.setupwraith` (setup wizard; it takes Home once launcherx is off)

**Re-enabled by the firmware at every boot:** `com.tcl.usercenter` and `com.tcl.smartalexa`. TCL turns these
two back on at boot. That's expected, and nothing is broken.

**Kept on purpose:** Netflix, YouTube, YouTube Music, Google Assistant (`com.google.android.katniss`, re-enabled
2026-11-14 because the remote's voice button needs it), TCL screen mirroring (`com.tcl.ttvs`), Live TV and the
input services (`com.tcl.tv`, `com.tcl.tvinput`), Quick panel/Input menu (`com.tcl.suspension`), remote and
Bluetooth (`com.tcl.tcl_bt_rcu_service`, `com.tcl.autopair`, `com.google.android.tv.remote.service`), TCL
key handlers (`com.tcl.keyhelp`, `com.tcl.common.shortcutmenu`, `com.tcl.partnercustomizer`), Play Store,
Play Services, the keyboard, and every Android core package. Never open `com.tcl.factory.view`.

**Wi-Fi:** disabling setupwraith can delete the Wi-Fi networks it saved during first setup. Check your saved networks after a restore.

## Remote buttons (checked 2026-11-14)

The TCL system key handler (`TclPhoneWindowManager` in `/system_ext/framework/tcl-service.jar`) handles the
remote's app keys. Status:

- **Google voice:** works since Google Assistant (`com.google.android.katniss`) was re-enabled.
- **Netflix, YouTube, Prime Video and other app keys:** the handler starts these by broadcasting to the stock
  Google TV launcher (`com.google.android.apps.tv.launcherx`). That launcher is disabled to remove its promo
  ads, so these keys do nothing. Netflix opened once while the launcher was enabled. The Prime Video app is
  also disabled, in this setup.
- **TV+ button:** fails with `ActivityNotFoundException` for `com.tcl.tv.plus`, which is disabled, in this setup.
- **3-dash (menu):** opens the Clean TV Inputs menu (HDMI 1, HDMI 2, AV, Live TV). The TCL system doesn't act on this key.
- **Settings:** opens Android Settings (`com.android.tv.settings`). The launcher handles it through `key_actions` in
  `config.xml`. Change the mapping there and run `./install.sh`.

The TCL screen-mirroring app (`com.tcl.ttvs`) only logs these key presses for usage statistics. It doesn't launch anything.

## Re-enabling apps

Easiest: on the TV, **Settings → Apps → See all apps** (turn on *Show system apps* if needed), pick the app and
choose **Enable**.

Over ADB (switch debugging back on first; see below):

```sh
tools/tv 'pm enable --user 0 com.amazon.amazonvideo.livingroom'   # one app
./restore.sh                                                        # everything, then reboot
```

`restore.sh` also re-enables the Google TV launcher and setupwraith and sets it back as Home. Clean TV stays
installed. Remove it with `tools/tv 'pm uninstall tv.clean.launcher'` *after* the stock launcher is back.

## The launcher

Apple TV-style home: a dark wallpaper, the clock in bold Outfit centered with a greeting and the date, frosted pills for
Inputs / Wi-Fi name / Settings, a dock band with the focused app's name above it and a glow in that app's
colour, and the remaining apps in a grid below the dock on the same band. One glass highlight moves between tiles. Up/Down
moves exactly one row, Up from the dock goes to the pills, Back returns to the dock, and the last focused
tile is remembered. Inputs opens a dropdown with HDMI 1, HDMI 2, AV and Live TV.

### System stats pill

Left of Inputs, a pill shows **CPU clock** and **RAM used / total**, refreshed every 3 s while Home is on
screen. That's local file reads only, with nothing in the background. This TV blocks apps from reading
CPU load (`/proc/stat`) and temperature (thermal zones), so the pill shows the current CPU clock instead. The
`ondemand` governor runs between 0.75 and 1.5 GHz as load rises. On a TV that does allow it, the same code
shows CPU % and °C. Turn the pill off with `<bool name="show_stats">false</bool>` in `config.xml`.

### Change the dock (apps, order, size)

Edit `launcher/app/src/main/res/values/config.xml`:

```xml
<integer name="columns">5</integer>          <!-- dock slots = grid columns (4–7) -->
<string-array name="dock_apps">              <!-- left to right, by package name -->
    <item>com.netflix.ninja</item>
    <item>com.google.android.youtube.tv</item>
    <item>com.tcl.tv</item>
    <item>app.smarttube.fdroid</item>
</string-array>
```

`hidden_apps` hides packages from the grid. `label_overrides` renames tiles (`package=Name`). Find package
names with `tools/tv 'pm list packages'`. Then run `./install.sh`.

### Change the wallpaper

The background is `launcher/app/src/main/res/drawable-nodpi/wallpaper.jpg`, a plain dark placeholder. To use your own
picture, replace that file with a 1920×1080 image, keep it dark or mid-tone so the white clock stays readable, and run
`./install.sh`. A dark radial shade (`drawable/clock_scrim.xml`) sits over the picture behind the clock.

### Rebuild / reinstall

```sh
./install.sh
```

The script builds with Gradle (`launcher/gradlew`, Android SDK in `~/Library/Android/sdk`, JDK 17+), sends the APK as
base64 chunks (the `adb-shell` file sync hangs on large files on this TV), runs `pm install -r`, grants
location (Android only reveals the Wi-Fi name to apps holding it) and sets Home.

## SmartTube

SmartTube F-Droid 32.56 (`app.smarttube.fdroid`, versionCode 2446) was installed from
`https://f-droid.org/repo/app.smarttube.fdroid_2446.apk`. It's a universal APK whose native code includes
`armeabi-v7a`, the TV's ABI. It is signed by F-Droid (`CN=FDroid`, cert SHA-256 `33791872…09deaa8`), and the
file's SHA-256 is `04884ce41c43671120fbaa81bc8953dfb617380dc038f12aca649a3d5b446a33`. It is the 5th dock slot.

Until you sign in (or press **Done** on its sign-in screen), SmartTube keeps opening its own sign-in screen
over whatever is on screen. Press **Done** once and it stops.

To update it, download the newer APK from F-Droid and push/install it the same way:
`tools/tv --push SmartTube.apk /data/local/tmp/st.apk`, then `tools/tv 'pm install -r /data/local/tmp/st.apk'`.

## Ad-blocking DNS

Private DNS is set to AdGuard (`dns.adguard-dns.com`). It blocks ad and tracker domains in most apps, but
**not YouTube ads**, which come from the same servers as the videos. Undo:

```sh
tools/tv 'settings put global private_dns_mode opportunistic'
tools/tv 'settings delete global private_dns_specifier'
```

Or on the TV: Settings → Network & Internet → Private DNS → Automatic.

## Switching ADB back on

Network ADB is **off** after this job. To use any script here again: Settings → System → About → press
*Android TV OS build* 7 times → Developer options → **USB debugging** on. The TV then listens on port 5555
again. Accept the "Allow USB debugging?" prompt on first connect. Switch it off again when done.

Quirk on this TV: the network port (5555) only comes back when you flip **USB debugging off and on again in
the Developer options menu**. A restart alone, or turning it off over ADB, leaves the port closed.

## Notes

- **Home:** Clean TV is Home. The stock launcher's home screen (with ads) is gone because its app is disabled.
  The HOME role holder was also set to Clean TV (`cmd role add-role-holder android.app.role.HOME tv.clean.launcher`).
  `restore.sh` does not undo that role change. Check Home after a restore.
- **Extra apps:** if an app you didn't install appears (AirScreen, for example), remove it in Settings → Apps.
- **"Works with Alexa"** shows in the grid because the firmware re-enables `com.tcl.smartalexa` at every boot.
  Hide it by adding the package to `hidden_apps` in `config.xml`.

## License

MIT. See `LICENSE`.
