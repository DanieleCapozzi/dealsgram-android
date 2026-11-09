## DealsGram for Android

DealsGram is an unofficial Telegram client for Android, based on
[Telegram for Android](https://github.com/DrKLO/Telegram) 13.2.1. On top of Telegram it adds tools for people who run
deal channels with [AutoDeals](https://autodealsbot.it): send a product link and get a ready post, see the upcoming
posts of a channel, search deals with their price history and publish posts with several deals.

Download: [dealsgram.app](https://dealsgram.app)

The AutoDeals tools need an AutoDeals account; everything else works like Telegram. DealsGram uses the Telegram API
with its own api_id and is not affiliated with Telegram.

### Where the code is

- `TMessagesProj/src/main/java/org/telegram/dealsgram/`: the DealsGram features.
- Changes to Telegram's own files are short hooks marked with a `DealsGram:` comment. The parent commit is the
  unmodified Telegram release, so `git diff HEAD~1` shows every change.
- `TMessagesProj_AppStandalone/`: the app module (package `app.dealsgram`), including the updater that reads
  `https://dealsgram.app/update.json`.
- DealsGram texts live in `TMessagesProj_AppStandalone/src/main/res/values*/strings.xml` with `DealsGram_` keys.

### Building

You need the same tools as Telegram: Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the repository with its submodules:
   ```bash
   git clone --recursive --shallow-submodules <repository url> DealsGram
   ```
2. Get your own `api_id` and `api_hash` at [my.telegram.org](https://my.telegram.org) and add them to
   `local.properties` in the repository root (the file is ignored by git):
   ```properties
   DEALSGRAM_APP_ID=12345
   DEALSGRAM_APP_HASH=0123456789abcdef0123456789abcdef
   ```
   Keep them out of the repository: Telegram blocks an api_id that is abused by others.
3. Debug build, signed with the debug key of your Android SDK:
   ```bash
   ./gradlew :TMessagesProj_AppStandalone:assembleAfatDebug
   ```
4. Release build: add your own signing key to `local.properties` (`DEALSGRAM_KEYSTORE`, `DEALSGRAM_KEY_ALIAS`,
   `DEALSGRAM_STORE_PASSWORD`, `DEALSGRAM_KEY_PASSWORD`), then run `assembleAfatStandalone`. Without a key the
   release build stops instead of producing an unsigned APK.
5. Push notifications use the Firebase project of the official app, which accepts only the official signature.
   For notifications in your own builds, replace `TMessagesProj_AppStandalone/google-services.json` with one from
   your Firebase project.

If you publish your own build, use a different name, package name and icon, as Telegram asks for its own code.

### License

DealsGram is released under the GNU General Public License v2, like Telegram for Android. See [LICENSE](LICENSE).
