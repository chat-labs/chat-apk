# chat-apk

An Android app for [chat](https://github.com/chat-labs/chat): encrypted chat without a server, with
rooms that peers reach directly or through Tor.

The app is the screens. [chat-core](https://github.com/chat-labs/chat/tree/main/chat-core) does
the rest: each open room is a `chat-json` process, and the app drives it over chat's
[JSON interface](https://github.com/chat-labs/chat/blob/main/chat-core/src/json.rs), so the
protocol, the crypto and the network are chat-core's, the same as on a desktop. A peer's picture
is decoded by chat-core, in Rust, and comes to the app as pixels, so no Android image decoder ever
reads what a peer sent.

## What's in it

- Rooms: make one and share its invite as text or a QR code, join with an invite or an id and
  password, or `/call` a verified peer in your shared daily room. Direct (UDP, the BitTorrent DHT
  over IPv4/IPv6, your network, Nostr relays when needed) or Tor.
- Messages with delivery ticks, grouped threads, replies, `@mention` completion, direct messages,
  message timers, clickable links with IP-leak warnings, and files and pictures (with metadata
  stripping, `Send as-is`, `Save as…` export, and zoomable viewer).
- Commands in the composer: `/` lists them (`/msg`, `/reply`, `/verify`, `/ignore`, `/call`,
  `/timer`, `/nick`, `/net`, `/leave` and more), with what each takes.
- Peers, with their verify codes to compare, cross-room verified and ignored keys, signing
  identities and builds, and `:move`.
- A signing key, random, derived from a password, or imported (AGE or PGP), with secret key export.
- Kept rooms and their histories, in a vault sealed the way you choose: a passphrase and a key in
  the phone's secure hardware, the phone's key only, or a passphrase only — plus screen lock while
  keeping rooms connected, inactivity auto-lock, wrong-passphrase self-destruct, shadow duress
  passphrase, and panic shutoff.
- Tor and lyrebird (obfs4, webtunnel, snowflake, meek) from Tor Project's Android bundle, one tor for
  every Tor room, with Tor Browser's built-in bridges or your own sent over Tor's control port and
  Tor's state sealed in the vault.

## Privacy

- Nothing is kept unless you keep a room, and what's kept is sealed. Backups and device transfers
  get nothing.
- Release builds keep chat out of screenshots, screen recordings and the recent apps.
- Text fields tell the keyboard not to learn from what's typed in them, and nothing is offered to
  autofill.
- Notifications never name the room, show who and what only as far as the preview setting allows,
  and on the lock screen only say a message came.
- chat and tor run with an empty environment, in folders only this app can read, and tor quits if
  the app is gone.

## Installing

The APKs are on the [releases](https://github.com/chat-labs/chat-apk/releases) page, for Android 9
or later. To get them and their updates with [Obtainium](https://github.com/ImranR98/Obtainium),
add `https://github.com/chat-labs/chat-apk` as an app and turn on "Include prereleases": every
release is a pre-release for now, signed with the local test key (see below).

## Building

You need a JDK (17 or later), rustup, [just](https://github.com/casey/just), and chat's source
next to this folder (`../chat`, or set `chat.source` in `gradle.properties`). The build compiles
chat-core's `chat-json` with Android's NDK for each ABI in `chat.abis` (chat-core's
`just android`, with the Rust chat pins), and fetches Tor Project's Android bundle, checked against
its pinned SHA-256.

```sh
just setup           # the Android SDK, NDK and emulator, into ~/.local/share/android-sdk (or ANDROID_HOME)
just build           # a debug APK in app/build/outputs/apk/debug/
just build release   # a release APK, shrunk, signed with the local test key
just run             # build, install on the connected phone (or the emulator, opened in a window) and start it
just emulator        # a foldable emulator (Pixel 9 Pro Fold, Android 17) in a window, with chat installed and open
just emulator fresh  # the same, wiped first: nothing kept from before, in its storage or its saved memory
just emulator-stop   # stop it
just peer            # desktop chat from ../chat, with the relays always on, to talk to the app on the emulator
```

On GitHub, [CI](.github/workflows/ci.yml) builds the APK with chat's main branch for each pull
request and push, runs Android's lint on it, and keeps the debug APK as an artifact.

The emulator sits behind a NAT of its own, where neither the LAN nor the DHT finds it, so desktop
chat and the app meet on the Nostr relays (`just peer` turns them on), or through Tor. Copy an
invite in one and paste it in the other, with clipboard sharing on in the emulator (its Extended
controls, Settings).

Android 9 or later. Release builds are signed with the same local test key as debug builds for
now, so they're for trying out, not for passing around.

## License

GPL-3.0, as chat. tor and lyrebird are Tor Project's builds, under their own licenses.
