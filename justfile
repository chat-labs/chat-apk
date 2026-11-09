set positional-arguments

sdk := env("ANDROID_HOME", env("HOME") / ".local/share/android-sdk")
adb := sdk / "platform-tools/adb"
export ANDROID_HOME := sdk

# List recipes
default:
    @just --list --unsorted

# Download the Android SDK, NDK and emulator chat-apk builds with into ANDROID_HOME (pinned versions)
[group('build')]
setup:
    #!/bin/sh
    set -eu
    sdk={{quote(sdk)}}
    if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
        tmp=$(mktemp -d)
        trap 'rm -rf "$tmp"' EXIT
        curl -fsSL -o "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
        echo "e025545c62a8e64c7559119566a569fb1dec5f60  $tmp/clt.zip" | sha1sum -c -
        mkdir -p "$sdk/cmdline-tools"
        unzip -q "$tmp/clt.zip" -d "$tmp/clt"
        mv "$tmp/clt/cmdline-tools" "$sdk/cmdline-tools/latest"
    fi
    yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" --licenses >/dev/null
    "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" --install platform-tools "platforms;android-37.0" \
        "build-tools;37.0.0" "ndk;29.0.14206865" emulator "system-images;android-37.0;default;x86_64"

# Build the APK (debug, or release signed with the local test key), with chat built from ../chat
[group('build')]
build kind="debug":
    #!/bin/sh
    set -eu
    case "$1" in
        debug) ./gradlew -q assembleDebug ;;
        release) ./gradlew -q assembleRelease ;;
        *) echo "just build takes debug or release, not $1" >&2; exit 1 ;;
    esac
    ls -1 app/build/outputs/apk/"$1"/*.apk

# Build, then install on the connected phone, or the emulator if none is, and open chat
[group('run')]
run kind="debug": (build kind)
    #!/bin/sh
    set -eu
    {{adb}} get-state >/dev/null 2>&1 || {{quote(just_executable())}} emulator
    {{adb}} install -r app/build/outputs/apk/{{kind}}/app-{{kind}}.apk >/dev/null
    {{adb}} shell am start -n moe.vin.chat/.ui.MainActivity >/dev/null
    echo "chat is open"

# The test emulator, a foldable (Pixel 9 Pro Fold, Android 17), with chat built, installed and open; fresh: wiped first
[group('run')]
emulator *mode: (build "debug")
    #!/bin/sh
    set -eu
    sdk={{quote(sdk)}}
    case "${1:-}" in
        "") fresh= ;;
        fresh) fresh=1 ;;
        *) echo "just emulator takes fresh or nothing, not $1" >&2; exit 1 ;;
    esac
    if {{adb}} get-state >/dev/null 2>&1; then
        if [ -n "$fresh" ]; then echo "an emulator is running: just emulator-stop first" >&2; exit 1; fi
    else
        avd="$HOME/.android/avd/chat37fold.avd"
        [ -d "$avd" ] || echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" -s create avd -n chat37fold \
            -k "system-images;android-37.0;default;x86_64" -d pixel_9_pro_fold
        # The laptop's keyboard types into the app, and the window draws with its GPU. Folded, it
        # shows the middle of the screen, where Android draws once the loop below shrinks it.
        sed -i -e 's/^hw.keyboard=.*/hw.keyboard=yes/' -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' \
            -e 's/^hw.displayRegion.0.1.xOffset=.*/hw.displayRegion.0.1.xOffset=498/' "$avd/config.ini"
        # Drawn on the laptop's GPU: left to choose, the emulator distrusts the driver and draws in
        # software. And more of the laptop than its defaults (4 cores, 2 GB): 6 is the most it takes.
        set -- -gpu host -cores 6 -memory 4096
        # Fresh: its storage wiped, and the memory it saved when it last closed skipped.
        if [ -n "$fresh" ]; then set -- "$@" -wipe-data -no-snapshot-load; fi
        log="${XDG_STATE_HOME:-$HOME/.local/state}/chat-apk/emulator.log"
        mkdir -p "$(dirname "$log")"
        setsid nohup "$sdk/emulator/emulator" -avd chat37fold -no-audio -no-boot-anim -accel on "$@" >"$log" 2>&1 &
        {{adb}} wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
    fi
    # Folded, this image tells apps so but keeps the inner screen's size, of which the emulator then
    # shows a part. A loop on the emulator gives Android the outer screen's size while it's folded;
    # one only, as it may have woken from its snapshot with one running.
    fold='pid=/data/local/tmp/chat-fold.pid
    [ -f $pid ] && grep -qs chat-fold /proc/$(cat $pid)/cmdline && exit
    echo $$ >$pid
    was=
    while :; do
        now=$(cmd device_state print-state)
        if [ "$now" != "$was" ]; then
            if [ "$now" = 1 ]; then wm size 1080x2152; else wm size reset; fi
            was=$now
        fi
        sleep 0.5
    done'
    {{adb}} shell "setsid sh -c '$fold' chat-fold </dev/null >/dev/null 2>&1 &"
    {{adb}} install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    {{adb}} shell am start -n moe.vin.chat/.ui.MainActivity >/dev/null
    echo "chat is open on the emulator"

# Stop the emulator
[group('run')]
emulator-stop:
    #!/bin/sh
    for s in $({{adb}} devices | awk 'NR > 1 && /^emulator-/ { print $1 }'); do {{adb}} -s "$s" emu kill >/dev/null; done

# Desktop chat from ../chat, with the relays always on, to talk to the app on the emulator
[group('run')]
peer *args:
    #!/bin/sh
    set -eu
    [ -x ../chat/target/release/chat ] || (cd ../chat && just tui::build release)
    # The emulator sits behind a NAT of its own, where neither the LAN nor the DHT finds it, so the
    # two meet on the relays.
    exec ../chat/target/release/chat --nostr always "$@"

# Remove build output
[group('build')]
clean:
    ./gradlew -q clean
