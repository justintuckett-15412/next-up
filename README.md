# Next Up

Upcoming episode air dates for the TV shows on your Plex server.

- **Web app** (`index.html`): sign in with Plex and see your shows sorted by when the next episode airs. Install it to your home screen like Final Tally.
- **Calendar feed** (`episodes.ics`): a GitHub Action rebuilds it twice a day. Subscribe in Google Calendar and the Calendar widget shows your episodes.

Air dates come from [TVmaze](https://www.tvmaze.com/api) (free, no key needed).

## Setup

1. Create a public repo named `next-up` and upload everything in this folder, **including the `.github` folder**.
2. Turn on GitHub Pages: **Settings → Pages → Deploy from a branch → main / (root)**.
3. Open `https://<your-username>.github.io/next-up/` on your phone, tap **Sign in with Plex**, then install it from the browser menu.

## Calendar feed

1. In the app, go to **Settings → Plex token → Copy**.
2. In the repo, go to **Settings → Secrets and variables → Actions → New repository secret**. Name it `PLEX_TOKEN` and paste the token.
3. Go to **Actions → Update TV calendar → Run workflow**. When it finishes, `episodes.ics` appears in the repo.
4. On a computer, open Google Calendar and go to **Other calendars → + → From URL**. Paste:
   `https://<your-username>.github.io/next-up/episodes.ics`
5. On your phone, add the **Google Calendar widget** to your home screen. Google refreshes subscribed calendars every several hours.
   - On iPad: **Settings → Calendar → Accounts → Add Account → Other → Add Subscribed Calendar**, using the same URL.

**If the job can't reach Plex** (for example, Remote Access is off), it uses `shows.json` instead. In the app, go to **Settings → Calendar backup list → Save**, upload the file to the repo, and re-upload it whenever you add shows.

Optional repo variables: `PLEX_SERVER` (server name, if you have more than one) and `CAL_FILE` (a different file name for the feed).

## Android app + widget

The `android/` folder is a native Android app with a scrollable home-screen widget. You don't need Android Studio, because GitHub builds it.

- The **Build Android app** action runs whenever something in `android/` changes, or when you run it from the Actions tab. It publishes the APK as a release.
- Download link (always the newest build):
  `https://github.com/<your-username>/next-up/releases/latest/download/next-up.apk`
- The app reads `episodes.json`, which the calendar job writes next to `episodes.ics`. It refreshes about every 3 hours and also when you tap ↻.
- Widget controls: ◀ ▶ move a week back or forward, ● jumps back to today, ↻ refreshes. Tapping an episode opens it in the app.
- "In Plex", "Watched" and "Not in Plex" labels come from your Plex server during the calendar job, so they need `PLEX_TOKEN` and Remote Access.
- When a new build is published, the app shows an "update ready" banner.

**Signing key:** add two repo secrets, `ANDROID_KEYSTORE_B64` (the key file, base64-encoded) and `ANDROID_KEYSTORE_PASSWORD`, so every build is signed with the same key and installs over the last one. Without them the build still works, but it uses a throwaway key and you'd have to uninstall before each update. Keep a private backup of the key; if you lose it, you'll need to uninstall and reinstall once with a new key.

## Notes

- Your Plex token stays in the GitHub secret and in the app's storage on your device. It never goes into the repo.
- The calendar file is public. Anyone with the URL can see which shows you have, and nothing else. To make the link hard to guess, set `CAL_FILE` to a random name such as `tv-8f3k2.ics`.
- Shows with no TVmaze match (home videos, some anime or foreign shows) are listed at the bottom of the app.
