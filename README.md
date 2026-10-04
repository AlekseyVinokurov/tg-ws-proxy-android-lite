# Pixel 8 Stable Go — test branch

This branch is the **stability baseline for Google Pixel 8 / arm64-v8a**.

## Why this branch exists

The first Pixel Eco builds used the newer Rust native core from the current Android fork. On the test Pixel 8 the local proxy started, but Cloudflare connections then degraded into repeated errors such as:

```text
CF fail kws2.<domain>.co.uk via <Cloudflare IP>: timeout
```

The same failure pattern is reported in the upstream Android project, including a Pixel 8 Pro report where users confirmed that **v1.2.0 worked while later builds did not**.

Therefore this branch deliberately returns to the proven **v1.2.0 arm64 native core** for the first stability test.

## Pixel-specific changes

- Target device: **Google Pixel 8 / arm64-v8a**
- Native proxy core: **upstream v1.2.0 arm64**
- Separate application id: `com.amurcanov.tgwsproxy.pixel8stable`
- Separate app label: **TG WS Proxy Pixel Stable**
- Default local port: **1445** to avoid conflicts with other installed builds
- Default WS pool: **1**
- Available pool values: **1 / 2 / 4**
- LeakCanary removed from the test APK
- Automatic request to disable Android battery optimization removed
- Explicit **Start proxy / Stop proxy** control retained
- Cloudflare mode remains available and enabled by default

## Important battery note

This is a **stability-first** build, not the final low-power build. The v1.2.0 foreground service still keeps the upstream wake-lock behavior. That is intentional for this test: first verify that Telegram/Cloudflare stays connected on Pixel 8. After stability is confirmed, battery-saving changes should be introduced one at a time so we can identify which optimization breaks background reliability.

## First test

Use:

```text
IP: 127.0.0.1
Port: 1445
WS pool: 1
Cloudflare CDN: ON
Autostart: OFF for the first test
```

Stop older TG WS Proxy test builds before testing this one. Start **TG WS Proxy Pixel Stable**, wait for the proxy to start, then apply the generated proxy link in Telegram.

Test:

1. messages;
2. photos/videos;
3. screen off for 10–20 minutes;
4. reopen Telegram;
5. Wi-Fi ↔ mobile-data switch.

If this build remains stable, the next iteration will reduce wake-lock duration/stat polling while keeping the v1.2.0 network core unchanged.

---

# TG WS Proxy Android — Pixel 8 Stable Go

This branch is the **stability-first Pixel 8 build**.

It is based on upstream **v1.2.0**, because real-device reports in the upstream issue tracker show that v1.2.0 restored reliable operation for multiple users after later builds became unstable; one report specifically mentions a **Pixel 8 Pro / Tensor G3**.

The purpose of this branch is to establish a known-good baseline before trying more aggressive battery changes.

## Why this branch exists

The first Pixel Eco experiment based on the newer Rust build did start on the Pixel 8, but real use showed repeated Cloudflare timeouts such as:

```text
CF fail kws2.<domain> via <Cloudflare IP>: timeout
```

Those failures are not unique to our fork. The same pattern is reported in upstream Android issues with v1.2.x and Cloudflare routing.

For the next test we therefore changed strategy:

- use the older/proven **v1.2.0 Go native core**;
- keep Android foreground-service/wakelock behavior required for stability;
- reduce only low-risk background work;
- use a small WS pool;
- leave aggressive power-saving changes for a later build.

## Pixel 8 Stable configuration

Default settings:

```text
Local address : 127.0.0.1
Port          : 1445
WS pool       : 1
Cloudflare    : enabled
Architecture  : arm64-v8a
```

Port 1445 is used so this build can coexist with upstream/previous test builds using 1443 or 1444.

## Changes relative to upstream v1.2.0

- separate Android package: `com.amurcanov.tgwsproxy.pixel8stable`;
- app label: **TG WS Proxy Pixel Stable**;
- explicit **Запустить прокси / Остановить прокси** button;
- default local port **1445**;
- default WS pool **1** instead of 4;
- statistics/notification polling reduced from **3 seconds to 30 seconds**;
- LeakCanary removed from the test build;
- automatic request to exempt the app from Android battery optimization removed.

### Intentionally retained

For this stability baseline, the permanent wake lock from v1.2.0 is retained.

That is deliberate. Removing it was one of the major changes in the first Eco experiment, and upstream users also report foreground failures when Android suspends the proxy. Once this Stable build proves reliable on the Pixel 8, wake-lock behavior can be optimized separately and measured instead of changing several variables at once.

## Native core

The build workflow downloads the official upstream **v1.2.0 arm64 APK** and extracts its proven:

```text
lib/arm64-v8a/libtgwsproxy.so
```

Our Kotlin app is then compiled around that exact native core.

This avoids rebuilding a different networking implementation and gives the test the same Go proxy engine that shipped in the working upstream release.

## Build verification

GitHub Actions performs:

1. checkout of `pixel8-stable-go`;
2. Java 17 setup;
3. download of official upstream v1.2.0 arm64 APK;
4. extraction and non-empty check of `libtgwsproxy.so`;
5. Kotlin compilation;
6. APK assembly;
7. verification that the resulting APK actually contains `lib/arm64-v8a/libtgwsproxy.so`;
8. artifact upload.

## Pixel 8 test procedure

1. Install **TG WS Proxy Pixel Stable**.
2. Stop other local TG WS Proxy variants.
3. Keep:
   - port 1445;
   - WS pool 1;
   - Cloudflare enabled.
4. Press **Запустить прокси**.
5. Apply it in Telegram.
6. Confirm text/media work for several minutes.
7. Turn the screen off for 15–30 minutes.
8. Re-open Telegram and test messages/media again.
9. Test Wi-Fi → mobile data and mobile data → Wi-Fi.
10. Only after stability is confirmed, evaluate battery drain.

## Interpreting Cloudflare errors

Occasional individual CF domain failures can be normal because the proxy rotates/falls back across domains.

The important distinction is:

- **some domains fail, Telegram still works** → fallback is doing its job;
- **all/most domains repeatedly time out and Telegram stalls** → this is a route/Cloudflare/network problem, not a local-port UI problem.

If the Stable Go build still shows sustained CF failure, the next engineering step is not to remove more Android power controls. It is to improve CF route selection/fallback or use a dedicated CF endpoint.

## Branches

- `main` — Pixel Eco/Rust experiment.
- `pixel8-stable-go` — stability-first Pixel 8 build using upstream v1.2.0 Go core.

## License

GPLv3, preserving upstream licensing and attribution.

Upstream Android project: **amurcanov/tg-ws-proxy-android**

Original project lineage: **Flowseal/tg-ws-proxy**
