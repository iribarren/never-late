# Articles catalog — canonical content

`articles.json` in this folder is the **canonical, human-edited source** for the app's article
catalog (title/content wording).

As of feature 13c, this file is **backend seed data only** — it is no longer fetched directly by
the Android app (that was feature 10's static GitHub-raw approach). The Ktor backend seeds its
`articles` table from a bundled copy of this file
(`backend/src/main/resources/seed/articles.json`) the first time it starts against an empty table
(idempotent, see `seedArticlesIfEmpty` in `backend/src/main/kotlin/com/neverlate/backend/db/Database.kt`).
The app now reads the catalog from the backend's paginated `GET /articles` endpoint (see
`docs/api/contract.md` §7).

To change article wording, edit **this** file, then copy it over
`backend/src/main/resources/seed/articles.json` so the two stay in sync (there is no automated
sync step — the backend only seeds an empty table, it never re-syncs an already-seeded one).
