# Hyperliquid Holdings (minimal Android app)

Single-screen port of the Hyperliquid weekend holdings check
(`~/workspace/custom-skills/hyperliquid-holdings-chart/`).

## What it does

On launch and on Refresh, it POSTs to `https://api.hyperliquid.xyz/info`
(public, no auth, no API key), reads the `xyz` builder dex universe for
the tracked tickers and BTC on the main dex as the IBIT proxy, and shows
one row per holding sorted best to worst by percent change versus the
open of the first hourly candle at or after the most recent
regular-session close (20:00 UTC / 1pm PT on a weekday). On weekends
that is Friday's close, matching `scripts/hyperliquid_weekend.py`; on a
weeknight it is that day's close, and the screen title names the actual
baseline day (e.g. "Holdings vs Tuesday's close"). Known limitation:
market holidays are not accounted for; the rule is purely weekday-based. Gains are
green, losses red. The footnote states these are perpetual prices, not
the stocks or ETFs, and lists the holdings with no Hyperliquid market
(TQQQ, CURE, SATA, VUG, XLU, MLPX) instead of inventing prices. On any
API failure it shows an error and no stale numbers. Read-only: no
trading, no account, no login.

Version 1.1 adds a searchable, editable tracked list: a search field
matches the xyz dex universe (plus BTC as the IBIT proxy) by substring,
case-insensitive, and tapping a result adds it; each row has a small
remove button. The list persists in SharedPreferences. Fresh installs
start with the v1.0 list (SMH, TSLA, SPCX, XBI, DRAM, BTC); upgrading in
place never resets an existing list. Only markets that actually exist on
Hyperliquid can be added, since results come straight from the universe.

Version 1.2 makes the baseline dynamic: instead of always using Friday's
close, it uses the most recent regular-session close (1pm PT / 20:00 UTC
on a weekday). Weekend behavior is unchanged (Friday), but a Tuesday
night now shows Tuesday's close, and the screen title names the actual
baseline day. Market holidays are not accounted for in this version.

## Build

Standard path (works on a normal machine with an Android SDK):

```
./gradlew assembleDebug
```

This VM's sandbox breaks the Gradle daemon's local socket protocol
("Could not receive a message from the daemon" / "Unexpected type tag 109"
on every run, including a fresh Gradle home and a low heap), so the APK
shipped here was built with the equivalent manual toolchain instead:

```
aapt2 compile --dir app/src/main/res -o build12/compiled.zip
aapt2 link -o build12/base.apk -I $SDK/platforms/android-34/android.jar \
  --manifest app/src/main/AndroidManifest.xml --java build12/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 3 --version-name 1.2 build12/compiled.zip
kotlinc app/src/main/java/com/marcel/hyperliquid/MainActivity.kt \
  build12/gen/com/marcel/hyperliquid/R.java \
  -cp $SDK/platforms/android-34/android.jar -include-runtime \
  -d build12/classes.jar
d8 --lib $SDK/platforms/android-34/android.jar --min-api 26 \
  --output build12/dex build12/classes.jar
# add build12/dex/classes.dex to base.apk, then zipalign + apksigner
# (signed with build/debug.keystore, the same debug key as v1.0/v1.1)
```

The project keeps standard AGP build files (`settings.gradle.kts`,
`app/build.gradle.kts`, AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24) so the
Gradle path works wherever the daemon runs.

## Deliverables

- `hyperliquid-holdings-debug.apk` — v1.0, debug-signed, sideloadable.
- `hyperliquid-holdings-debug-v1.1.apk` — v1.1, debug-signed, sideloadable.
  Package `com.marcel.hyperliquid`, minSdk 26, targetSdk 34.
- `hyperliquid-holdings-debug-v1.2.apk` — v1.2, debug-signed, sideloadable.
  Same package, versionCode 3, signed with the same debug key as v1.0/v1.1,
  so it upgrades in place.
