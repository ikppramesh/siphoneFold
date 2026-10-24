# siphoneFold — Frost Fold

An iPhone Duo–style **frosted-glass fold animation** for the Samsung Galaxy Z Fold 7, written from scratch in plain Java with its own GPU shader. No root.

When you open the phone, each half of the screen looks as if a pane of frosted glass is lifting off it from the crease. It's sharp at the hinge, and softer, darker and slightly magnified toward the outer edges. The glass settles as you open the phone and clears to sharp when it's flat. Closing plays it in reverse.

> **Status: work in progress, about 70% of the way to the real thing.** It runs on a Galaxy Z Fold 7 (Android 17 / One UI 9) and the overall look is there, but the timing is approximate and some folds don't animate. See [Known issues](#known-issues).

![Opening animation](docs/images/opening.gif)

---

## How it looks

These are the stages of an opening on the inner screen:

![Glass at 100%, 45%, 20% and 0%](docs/images/opening_sequence.png)

| Stage | Glass | What you see |
|---|---|---|
| Inner screen lights up | 100% | Both halves lifted: blurred, dimmed and magnified toward the edges. Black shows where the glass has risen past the screen. |
| Half open | 45% | The glass waits here until you finish opening. |
| Flattening | 20% | Content comes into focus from the crease outward. |
| Flat | 0% | Sharp. The overlay cross-fades to the live screen and is removed. |

> These images are rendered on a stand-in home screen by [`tools/render_preview.py`](tools/render_preview.py), using the same optics as the app's shader. They aren't screenshots from the phone.

---

## How it works

### The optics
The screen content stays still. Above it, each half of the display is a pane of frosted glass hinged at the crease, with its outer edge lifted toward you by an angle θ. Your eye is about 30 cm in front of the screen, level with the hinge.

For every pixel, the shader [`GlassShader.java`](src/com/rameshinampudi/frostfold/GlassShader.java) runs these steps:
1. Finds the point of the glass drawn there. At distance *d* from the hinge, the glass is *h = d·sin θ* above the content.
2. Casts a ray from the eye through that point down to the content. Lifted glass magnifies by *E / (E − h)*, where *E* is the eye distance.
3. Samples the content there with a grainy disc blur. The blur radius grows with *h*, using 6–20 samples on a golden-angle spiral rotated per pixel, which gives the frosted-glass grain.
4. Darkens it in proportion to *h*. Where the whole blur disc misses the screen, it shows black.

So the glass is crisp at the crease and most diffuse at the lifted edge, which is how real frosted glass behaves when you hold it off a surface.

### Drawing over everything
Android won't let one app draw another app's pixels through a shader. So Frost Fold runs as an **accessibility service**, which is allowed to:
- take a screenshot of the screen (`AccessibilityService.takeScreenshot`, at most about one every 333 ms);
- draw a `TYPE_ACCESSIBILITY_OVERLAY` window above everything, including the status bar and lock screen. The overlay doesn't take touches, so your taps go through.

The screenshot is drawn through the shader in that overlay. Screenshots stay in memory only and are never saved or sent; the app has no internet permission.

### Timing on the Fold 7
Two limits on the Fold 7 shape how the timing works:
- **Its public hinge sensor only reports 0°, 90° and 180°.** Samsung's smooth angle sensor needs a system permission.
- **Only one screen is lit at a time**, and the screen being switched on is briefly black.

So the fold plays in phases, driven by the hinge stops and by Android switching the main display between the panels:

| Event | Glass |
|---|---|
| Hinge 0 → 90° (start opening, cover screen) | Screenshot the cover, frost in to 60% |
| Main display switches to the inner screen | Show the last good inner screenshot at 100% right away, then settle to 45% and wait |
| Hinge reaches 180° (flat) | Clear to 0% and cross-fade to the live screen |
| Hinge 180 → 90° (start closing, inner screen) | Screenshot the inner screen, frost in to 55% |
| Hinge 90 → 0° (shut) | Fill to 100% in 0.14 s, then fade out before the panels swap |
| Main display switches to the cover | Show the last good cover screenshot frosted, then clear |

Other rules:
- **No black images.** Every screenshot is checked, and a black one (from a screen switching off) is never shown or kept.
- **Fresh images.** 0.6 s after each fold finishes, the current screen is captured again so the next fold starts from what's on screen now.
- **Never stuck on screen.** If the hinge stops halfway (Flex mode) or goes back, the glass clears after at most 2.2 s.

### Files

| File | Role |
|---|---|
| [`FoldService.java`](src/com/rameshinampudi/frostfold/FoldService.java) | Accessibility service. Handles hinge and display events, the phase timing, screenshots and the cache, and the overlay window. |
| [`GlassShader.java`](src/com/rameshinampudi/frostfold/GlassShader.java) | The AGSL shader: lifted-glass optics, blur and darkening. |
| [`GlassView.java`](src/com/rameshinampudi/frostfold/GlassView.java) | Draws the screenshot through the shader and turns the settings into shader values. |
| [`MainActivity.java`](src/com/rameshinampudi/frostfold/MainActivity.java) | Settings: service status, tilt, frost, darkening, "also when closing", live hinge readout and **Preview**. |
| [`res/xml/fold_service.xml`](res/xml/fold_service.xml) | Accessibility service config (`canTakeScreenshot`). |
| [`build.sh`](build.sh) | Builds and signs the APK straight from the Android SDK, with no Gradle. |
| [`tools/render_preview.py`](tools/render_preview.py) | Renders the reference images in `docs/images/`. |

---

## Requirements

- An Android foldable with a hinge-angle sensor, **Android 13 (API 33) or newer**. The shader uses `RuntimeShader`.
- Tested only on a **Galaxy Z Fold 7 (SM-F966B)**.
- To build: **JDK 17+** and the **Android SDK** with `platforms;android-36` and `build-tools;36.0.0` in `~/Library/Android/sdk`. Edit `SDK=` in `build.sh` if yours is elsewhere.

## Build

```bash
git clone https://github.com/ikppramesh/siphoneFold.git
cd siphoneFold
./build.sh                    # → build/FrostFold.apk
```

## Install

**Over Wi-Fi with ADB** (recommended). Apps installed with `adb install` aren't blocked from accessibility on Android 13+.
1. On the phone: **Settings → About phone → Software information**, tap **Build number** 7 times.
2. **Settings → Developer options → Wireless debugging** → on → **Pair device with pairing code**.
3. On the computer:
   ```bash
   adb pair <ip>:<pairing-port> <code>
   adb install -r build/FrostFold.apk
   ```
4. Turn the service on. Either use the phone (**Settings → Accessibility → Installed apps → Frost Fold animation → On**), or run this from the computer. It keeps any services you already have on:
   ```bash
   ME=com.rameshinampudi.frostfold/com.rameshinampudi.frostfold.FoldService
   CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
   adb shell settings put secure enabled_accessibility_services "${CUR:+$CUR:}$ME"
   ```

If you sideload the APK instead and the switch is greyed out, go to **Settings → Apps → Frost Fold → ⋮ → Allow restricted settings**. On Samsung you may also need to turn off **Auto Blocker** first.

> **After reinstalling,** Android marks the service as stopped. Switch it off and on again in Accessibility.

## Use

Open **Frost Fold** and check it says **✓ Fold animation is on**. Tap **Preview animation**, then open and close the phone. The first fold in each direction after starting has no saved screenshot yet, so the second one shows the full effect.

| Setting | Default | Effect |
|---|---|---|
| Also when closing | on | Play the effect when closing too |
| Tilt | 30° | How far the glass lifts at full strength |
| Frost | 70 px | Blur radius at the lifted edge |
| Darkening | 45% | Light lost at the lifted edge |

Debug commands from a computer:
```bash
adb shell am broadcast -a com.rameshinampudi.frostfold.PREVIEW -p com.rameshinampudi.frostfold   # replay
adb logcat -s FrostFold          # hinge events, panel switches, screenshots
```

## Tuning

The timing constants are at the top of `FoldService.java`:

| Constant | Value | Effect |
|---|---|---|
| `COVER_OPENING` | 0.6 | Glass on the cover as you start to open |
| `INNER_HALF_OPEN` | 0.45 | Glass on the inner screen while half open |
| `INNER_CLOSING` | 0.55 | Glass on the inner screen as you start to close |
| `FROST_IN_MS` / `SETTLE_MS` / `SHUT_MS` | 420 / 450 / 140 ms | Speed of each step |
| `HOLD_TIMEOUT_MS` | 2200 ms | Longest the glass waits for the next hinge stop |
| `REFRESH_AFTER_MS` | 600 ms | When the screen is re-captured after a fold |

Clearing takes 450–1100 ms, scaled by how much glass is left.

---

## Known issues

- **About 70% accurate.** The Fold 7's public hinge sensor only reports 0°, 90° and 180°, so the animation follows your hand in steps, not continuously.
- **Some folds don't animate.** A very quick flick can switch panels before a screenshot is ready, and the first fold after starting has no saved screenshot.
- **The screen sometimes turns off after closing.** Android sometimes decides to sleep on fold (`LogicalDisplayMapper … sleepDevice=true`). In testing it did this with and without the overlay on screen, so it looks like Samsung's fold-lock behaviour rather than this app, but that's not confirmed yet. Check **Settings → Display → Continue apps on cover screen**.
- **The picture is frozen during a fold.** The glass shows a screenshot, not the live screen, until it cross-fades at the end.
- **Secure screens can't be captured**, such as banking apps and DRM video, so the effect skips them.
- **The cover screen's hinge is assumed to be on the left edge**, which is correct for the Fold 7 held normally.

## Ideas for the remaining 30%

- A smooth hinge angle. Samsung's fold wallpaper logs the real angle, which a shell-level helper such as Shizuku could read.
- Live content under the glass, with no frozen screenshot.
- A setting for which half moves (left, right or both).
- Testing on other foldables.

## Credits

The design approach — an accessibility-service overlay, a screenshot bent through a shader, and stepped timing for the Fold's coarse hinge sensor — was learned by studying [marcoazeem/duo-open](https://github.com/marcoazeem/duo-open) (MIT) and its notes on Samsung hardware. All code here is written independently. Inspired by the fold animation on the iPhone Duo.

## License

MIT — see [LICENSE](LICENSE).
