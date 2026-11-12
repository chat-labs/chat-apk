# Changelog

## 0.1.2

- Tor is the default way rooms reach peers, for new settings and on the first screen.
- A new room starts with a random id and password, made up by chat, to keep, change or clear.
- The console is apart from the chat: chat's notes, its log and errors are behind a button by the
  chat's title, with a line for chat's commands. Warnings still show in the chat too.
- The icon's background is a white to silver gradient.
- Releases can be followed with Obtainium (see the README).

Still a pre-release signed with the local test key, so it installs over 0.1.1.

## 0.1.1

- Tor rooms open: chat-core took the challenge the app's tor sends when a room logs in to it as
  malformed, so every Tor room failed.
- The app stays open when tor or a room's chat-json is stopped under it: leaving the last Tor room,
  every Tor room failing, locking, panic, the settings that restart rooms, or tor refusing its
  bridges closed the whole app.
- The ghost is the app's icon, and its themed icon's shape.

Still signed with the local test key, so it installs over 0.1.0.

## 0.1.0

The first release: chat on Android, each room run by chat-core's `chat-json` and driven over
chat's JSON interface, with tor and lyrebird from Tor Browser 16.0.2's Android bundle. What's in it
is in the [README](README.md#whats-in-it).

Signed with the local test key, as every build is for now: it's for trying out. A later release
signed with the app's own key won't install over it, so uninstall it first (kept rooms go with it).
