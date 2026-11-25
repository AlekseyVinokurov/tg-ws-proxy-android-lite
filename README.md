**English** · [Русский](README.ru.md)

# TG WS Proxy — Pixel Eco

An experimental, lightweight **local Telegram MTProto proxy** for Android, focused on reducing background activity on Pixel devices. Built from [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android); original authors and GPLv3 notices are retained.

It is a **separate app**, not a VPN or a replacement Telegram client. You start a local proxy and select it in Telegram.

## Project status

| Item | Status |
| --- | --- |
| Target | Pixel 8 / ARM64, Android 17 is the owner's primary test environment |
| Last published APK | [pixel8-stable-go-v1](https://github.com/AlekseyVinokurov/tg-ws-proxy-android-lite/releases/tag/pixel8-stable-go-v1) — **does not contain the new mobile-network fix** |
| Next candidate | `1.2.4-pixel8-lite.1` (`versionCode 126`), source-built Rust core, pending CI and device tests |
| Distribution | GitHub Releases for published builds; Actions artifacts are **test builds only** |

The previously published APK and the current source may use different native implementations. Do not assume a code change is present in the downloaded release.

## What Pixel Eco changes

- Removes the permanent CPU partial wake lock; the Android foreground service still runs while manually enabled.
- Reduces background traffic/statistics refresh frequency to 60 seconds.
- Uses a default WebSocket pool size of **1**, adjustable to **0, 1, 2 or 4**. Disabling preconnections may slow the first request.
- Starts on local port **1444**, to avoid a clash with another proxy commonly using 1443.
- Uses a distinct Android package ID: `com.amurcanov.tgwsproxy.pixel8lite`, so it can coexist with the upstream app.
- Boot autostart is **off by default**.

These are engineering choices, **not proven battery-life gains**. Test connectivity and power use on your own device.

## Mobile-network fallback (candidate)

The new **optional Cloudflare WS/80 fallback** is meant for networks where TCP connections succeed but TLS/WebSocket negotiation to Cloudflare fails.

1. Stop the proxy and open **Settings**.
2. Leave Cloudflare enabled and turn on **Cloudflare WS/80 fallback (no TLS)**.
3. Read and confirm the security warning; restart the local proxy.
4. Apply the proxy in Telegram; check that messages and media actually load.

The native engine first attempts **WSS/443** with the Cloudflare base domain as the verified TLS server name, while keeping `kwsN.<base-domain>` as the HTTP Host. On eligible connection failures, it may retry **plain WS/80** if explicitly enabled. Certificate verification failures and HTTP errors do **not** trigger an automatic plaintext downgrade. Direct Telegram WebSockets and TCP fallback are separate routes.

**Security:** plain WebSocket has **no additional TLS authentication or metadata confidentiality**. Telegram MTProto still encrypts message content, but a network observer may see more connection metadata. The option is **disabled by default**. It is not guaranteed to work across carriers, regions or devices.

This new logic has not yet been verified in a standalone Pixel Eco APK. A similar WS/80 mechanism was confirmed in [Velora 0.1.5](https://github.com/AlekseyVinokurov/Velora/releases/tag/v0.1.5) on one Pixel 8/mobile network, but that test does not establish this separate app's behavior.

## Install and use

Get a published APK from [Releases](https://github.com/AlekseyVinokurov/tg-ws-proxy-android-lite/releases). For experimental PR builds, use the APK artifact in [Actions](https://github.com/AlekseyVinokurov/tg-ws-proxy-android-lite/actions).

1. Install the APK, open **TG WS Proxy Pixel Eco**, and choose a free local port (default **1444**).
2. Keep **WS pool = 1** initially and press **Start proxy**.
3. Use **Apply in Telegram** to configure the local MTProto proxy.
4. Verify real connectivity, including after switching between Wi-Fi and mobile data.
5. Stop the proxy manually when it is not needed.

**Updates and signing:** CI debug artifacts can be signed with different temporary keys and therefore may not install over an earlier published version. Do not delete an installed app merely to bypass an update/signature error; a consistent release-signing process must be established before claiming seamless in-place upgrades.

## Source, builds and maintenance

The new workflow [`build-pixel8.yml`](.github/workflows/build-pixel8.yml) runs on **GitHub-hosted runners**. It tests the Rust source, rebuilds the Android ARM64 native library and packages it into the debug APK. A successful Gradle build alone does not prove the network proxy works. No local Android build is required.

This repository also contains archived Go code in `old_src_golang/`. It is **not** the native implementation used by the new Rust-source build. The missing `src/faketls.rs` file was restored from the GPLv3 upstream to make the checked-in Rust source complete.

Known limitations: primary ARM64 target only, no universal-device test coverage, no verified automatic in-app update path for test APKs, and no guarantee of bypassing network filtering.

For bugs, device reports and suggestions use [GitHub Issues](https://github.com/AlekseyVinokurov/tg-ws-proxy-android-lite/issues).

## License and acknowledgments

GPL-3.0 — see [LICENSE](LICENSE). Original implementation: [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android), with earlier ideas and components from [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). The optional Cloudflare transport was independently adapted after work on [Velora](https://github.com/AlekseyVinokurov/Velora). Original copyright and attribution must remain intact.
