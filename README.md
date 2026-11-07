# Own Access

*Access owned, not rented.*

A free, open-source Android client for browsing and watching
[Owncast](https://owncast.online) streams from the public
[owncast.directory](https://owncast.directory) listing — with live chat.

No accounts, no trackers, no analytics, no Google services. The app talks
to the directory feed and to the instances themselves, and to nothing
else.

## Features

- **Live directory grid** fed by the directory's public IPTV feed
- **Category browsing — Music, Chatting, Tech, Video Games.** The
  directory website groups streams into these four; this app rebuilds
  the same browsing on the phone. The feed carries no category field,
  so membership is derived from each stream's own tags — a heuristic
  that tracks the site's grouping closely but is not identical to it.
- **Full chat**: read, scroll back through history, and send, under a
  display name you choose. Chat connects to the instance's live socket
  (`/ws`, with the legacy `/api/chat` socket and history polling as
  fallbacks for older or proxied instances).
- **Add any instance by URL** — for servers the directory doesn't list.
  The app checks the address answers like an Owncast instance before
  saving it; custom instances pin to the top of the grid and can be
  removed with a long-press.
- **Theme**: System / Light / Dark, following your phone by default.

## Screenshots

<!-- TODO: add screenshots before the first public release -->

## Building

Requires JDK 21 and the Android SDK (platform 35, build-tools 35.0.0).

```sh
./gradlew assembleDebug     # debug build
./gradlew assembleRelease   # release build (see signing)
```

### Release signing

Release builds read signing credentials from a `keystore.properties`
file in the project root, which is **gitignored and must never be
committed**:

```properties
storeFile=odc-release.jks
storePassword=…
keyAlias=odc
keyPassword=…
```

Create a keystore with:

```sh
keytool -genkeypair -v -keystore odc-release.jks -alias odc \
    -keyalg RSA -keysize 2048 -validity 10000
```

Guard the keystore and its password like an identity document: every
future update of the app must be signed with the same key.

## Notes

- applicationId: `dev.phlux.ownaccess` (the internal code package
  remains `dev.phlux.owncastdirectory` — plumbing, not identity).
- Stream playback is HLS via Media3/ExoPlayer; chat uses each
  instance's own public chat API.

## License

GPL-3.0 — see [LICENSE](LICENSE).
