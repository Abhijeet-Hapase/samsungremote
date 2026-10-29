# TV Remote

Android remote for 2018+ Samsung Tizen TVs. Talks to the TV directly over your Wi-Fi, no Samsung account or cloud.

## Features
- Remote: power, D-pad, OK, back, home, exit, source, menu, volume, mute, channels, info, playback. Hold arrows or volume to repeat.
- Phone volume buttons control TV volume while the app is open.
- Apps tab: lists apps installed on the TV and launches them (falls back to Netflix, YouTube, Prime Video, Apple TV, Spotify if the TV does not share its list).
- Keyboard tab: type on the phone into the TV's search or text box. The tab opens automatically when the TV keyboard appears.
- Power on over Wi-Fi (Wake-on-LAN). The TV's MAC address is saved automatically the first time you connect.
- Auto discovery (SSDP, then a subnet scan) or manual IP entry.

## Get the APK (no Android Studio needed)
1. Create a GitHub repository (private is fine) and push this folder to its `main` branch.
2. The **Build APK** workflow runs automatically (Actions tab, about 4 to 6 minutes).
3. Open the repository's **Releases** on your phone, download `tv-remote.apk`, and install it. Allow "Install unknown apps" for your browser when Android asks.

Every build is signed with the same key (`app/remote.keystore`), so new builds install over old ones.

To build locally instead: open the folder in Android Studio, or run `./gradlew assembleRelease` with the Android SDK installed. Output: `app/build/outputs/apk/release/app-release.apk`.

## First run
1. Turn the TV on. Phone and TV must be on the same Wi-Fi.
2. In the app, tap **Settings**, then **Find TVs on this Wi-Fi**, pick your TV, and tap **Save and connect**.
3. Choose **Allow** on the TV prompt. This happens only once.

## Turning the TV on from the app
Enable **Power On with Mobile** on the TV (usually Settings, General, Network, Expert Settings). The TV must have been connected at least once while on, so the app knows its MAC address.

## Troubleshooting
- **"The TV refused access"**: on the TV go to Settings, General, External Device Manager, Device Connection Manager, and set this phone to Allow (or remove it from the list and reconnect).
- **TV not found**: some routers block discovery or isolate Wi-Fi clients. Enter the TV's IP manually (TV Settings, General, Network, Network Status). Giving the TV a fixed IP in your router avoids it changing.
- **Keyboard text does nothing**: a text box must be open on the TV first.
