set positional-arguments

sdk := env("ANDROID_HOME", env("HOME") / ".local/share/android-sdk")
adb := sdk / "platform-tools/adb"
export ANDROID_HOME := sdk

# List recipes
default:
    @just --list --unsorted

# Download the Android SDK, NDK and emulator chat-gui builds with into ANDROID_HOME (pinned versions)
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

# Build, then install on the connected phone or emulator and start it
[group('run')]
run kind="debug": (build kind)
    {{adb}} install -r app/build/outputs/apk/{{kind}}/app-{{kind}}.apk
    {{adb}} shell am start -n moe.vin.chat/.ui.MainActivity

# Start the test emulator (Android 17, x86_64) without a window
[group('run')]
emulator:
    #!/bin/sh
    set -eu
    sdk={{quote(sdk)}}
    [ -d "$HOME/.android/avd/chat37.avd" ] || echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" -s create avd -n chat37 \
        -k "system-images;android-37.0;default;x86_64" -d pixel_6
    "$sdk/emulator/emulator" -avd chat37 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -accel on &
    {{adb}} wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
    echo "emulator up"

# Remove build output
[group('build')]
clean:
    ./gradlew -q clean
