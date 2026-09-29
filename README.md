# DMs & Stories — Instagram without Reels

An Android app that wraps instagram.com in a WebView and only lets you reach **Direct Messages** and **Stories**. Reels, Explore, search, the home feed, posts and profiles are hidden or blocked.

## Install

1. Open the repo's **Actions** tab → latest **Build APK** run → download the `dms-and-stories-apk` artifact.
2. Unzip it and install `app-debug.apk` on your phone. You'll need to allow installs from unknown sources.
3. Log in with your Instagram account. The login is stored in the app's cookies.

Every build is signed with the same committed debug key (`app/debug.keystore`), so a newer APK installs over an older one and you stay logged in.

## How it works

The app has two tabs: **Stories** and **Messages** (`/direct/inbox/`, where the app opens). **Stories** (the home page with the feed hidden, so only the stories tray is left).

Blocking happens in three layers:

| Layer | Where | What it does |
|---|---|---|
| URL policy | `UrlPolicy.kt` | An allow-list of paths: `/`, `/direct/`, `/stories/`, plus the login and account paths. Any other instagram.com page is blocked. Links to other sites open in your browser. `instagram://` app links are dropped. |
| Navigation hooks | `MainActivity.kt` | `shouldOverrideUrlLoading` checks full page loads against the policy. `doUpdateVisitedHistory` catches Instagram's in-app navigation and goes back if it lands on a blocked page. |
| Injected script | `assets/guard.js` | Blocks taps on links to blocked pages before Instagram handles them. Hides the Reels, Explore, Search and Create buttons. On the home page it keeps only the stories tray, hiding everything after it (posts, suggested posts, the infinite-scroll loader) so the feed stops loading, and pauses videos. |

When something is blocked, you see a toast such as "Reels blocked — DMs & Stories only".

## Limitations

- The hiding in `guard.js` depends on Instagram's page structure and English labels, so an Instagram redesign can break it. The URL blocking doesn't depend on page structure and keeps working.
- Reels that people send you in DMs still show up as thumbnails, but tapping them is blocked.
- Voice and video calls aren't supported. You can still send photos from the gallery.

## Build locally

You need JDK 17+ and the Android SDK (platform 35):

```sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
