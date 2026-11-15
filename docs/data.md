# Data and storage

## PC app

Everything lives in `%APPDATA%\SokoulPC` (or the folder in the `sokoul.data` system property). On Windows the folder is
restricted to its owner. Reinstalling the program keeps it.

| File | Content |
| --- | --- |
| `sokoul.db` | SQLite database (`desktop/data/DesktopDb.kt`) |
| `recordings/` | Live TV recordings (default storage) |
| logs | Redacted logs; keys are masked by `Redact` |

### Database tables

| Table | Holds |
| --- | --- |
| `playlists`, `categories`, `channels`, `programs` | IPTV providers, their groups, channels and guide (XMLTV) |
| `favorites` | Favourite channels |
| `movies`, `series`, `provider_categories` | Provider VOD catalogue |
| `progress`, `resume` | Watch progress; `resume.profile` separates local profiles |
| `settings` | Preferences (`DesktopPrefs`), keys typed in Settings, layout of the home rows |
| `tmdb_cache` | Cached catalogue answers |
| `recordings` | Scheduled and finished recordings |

Profiles (`data/Profiles.kt`), My List, title alerts and the home layout are stored as settings, keyed by profile.

## TV and phone app

The Android app uses the same `DesktopDb` schema in the app's private storage (`Context.filesDir`). Recordings go to
internal storage or to a removable USB drive (`Platform.recordingStorages`). On Android TV, the *Continue watching* row of
the home screen is kept in sync through `WatchNext`.

## Backend

| Store | Content |
| --- | --- |
| MySQL | Accounts, profiles, lists, comments, links (`API/init-local-schema.js`, `Mainapi/wrapped_tables.sql`) |
| Redis | Short-lived caches and rate limits |
| `Mainapi/cache/` | Provider answers cached on disk (ignored by Git) |

`node API/backup-backend.js` dumps the MySQL database and the service configuration.
