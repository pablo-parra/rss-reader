# Technical Specification — RSS Reader MVP

Reference document for implementation. Scope is strictly the backlog user stories in [BACKLOG.md](BACKLOG.md) (US-1 manage sources incl. paywall fallback, US-2 daily + on-demand fetch, US-3 view/read unread list, US-4 OPML import/export). No functional code included here.

## 1. High-Level Architecture

Single-process monolith, local-only, no auth.

```
┌─────────────────────────────────────────────┐
│              Spring Boot App                 │
│                                               │
│  ┌───────────┐   ┌────────────────────────┐ │
│  │  Web layer │   │      Scheduler          │ │
│  │ (Thymeleaf │   │  @Scheduled daily job   │ │
│  │  + MVC     │   │  -> FetchService         │ │
│  │  controllers)│  └───────────┬────────────┘ │
│  └─────┬─────┘                │              │
│        │                      ▼              │
│        │            ┌──────────────────┐     │
│        └───────────▶│   Service layer   │     │
│                      │ SourceService     │     │
│                      │ ArticleService    │     │
│                      │ FetchService      │     │
│                      │  - RssFetcher     │     │
│                      │  - ScrapeFetcher  │     │
│                      └─────────┬────────┘     │
│                                ▼               │
│                      ┌──────────────────┐     │
│                      │ Repository layer  │     │
│                      │ (Spring Data JPA) │     │
│                      └─────────┬────────┘     │
└────────────────────────────────┼──────────────┘
                                  ▼
                          SQLite file (app.db)
```

**Stack choices:**
- **Runtime:** Java 21, Spring Boot 3.x (Web MVC, Spring Data JPA, Spring Scheduling)
- **DB:** SQLite via `org.xerial:sqlite-jdbc` + Hibernate community dialect for SQLite
- **Frontend:** Server-rendered Thymeleaf templates + plain CSS (no JS build step, no SPA framework) — matches "lightweight, easy to maintain" requirement
- **RSS parsing:** Rome (`com.rometools:rome`)
- **HTML scraping / feed discovery:** Jsoup
- **Build:** Maven

**Fetch strategy per source (`Source.type`):**
- `RSS`: Rome parses the feed URL directly
- `SCRAPE`: Jsoup fetches the page, extracts candidate article links/titles heuristically (`<article>`, heading + anchor patterns)
- `UNSUPPORTED`: skipped by the fetch job entirely; surfaced in UI as needing manual attention (e.g. Cloudflare-challenge detected — HTTP 403/503 with challenge markers)

Source type is determined once at creation time by probing the URL (look for `<link rel="alternate" type="application/rss+xml">`; if none, and page fetch fails/returns a challenge page, mark `UNSUPPORTED`; otherwise `SCRAPE`).

## 2. Data Models

### `Source`
| Field | Type | Notes |
|---|---|---|
| `id` | Long (PK) | auto-generated |
| `url` | String | unique, the feed or page URL as entered |
| `name` | String | display name (author/site), user-editable |
| `type` | Enum: `RSS`, `SCRAPE`, `UNSUPPORTED` | set at creation, re-checkable |
| `feedUrl` | String, nullable | resolved feed URL if different from `url` (discovered `<link rel="alternate">`) |
| `lastFetchedAt` | Instant, nullable | updated after each fetch attempt |
| `lastFetchStatus` | Enum: `OK`, `ERROR`, `UNSUPPORTED` | last run outcome |
| `createdAt` | Instant | |

### `Article`
| Field | Type | Notes |
|---|---|---|
| `id` | Long (PK) | auto-generated |
| `sourceId` | Long (FK → Source) | |
| `url` | String | unique per source, used for dedupe |
| `title` | String | |
| `publishedAt` | Instant, nullable | from feed/page if available |
| `fetchedAt` | Instant | when we first saw it |
| `read` | boolean | default `false` |
| `readAt` | Instant, nullable | when the user first opened the article; null for articles stored as read by the first-fetch cap (so they never show on the dashboard) |
| `contentHtml` | String (TEXT), nullable | sanitized article body cached on first open (US-1); null until opened |
| `contentOrigin` | Enum: `ORIGINAL`, `ARCHIVE`, `UNAVAILABLE`, nullable | where `contentHtml` came from, or that neither source could be read |
| `contentFetchedAt` | Instant, nullable | when the content was cached |
| `contentVersion` | Integer, nullable | version of the extraction rules that produced `contentHtml`; content with an older or missing version is re-extracted on next open (`ArticleContentService.CONTENT_VERSION`, bump it whenever the extractor changes) |

**Constraints:** unique index on (`sourceId`, `url`) to prevent duplicate ingestion on repeated fetch runs.

## 3. Target Folder Structure

```
rss-reader/
├── pom.xml
├── TECH_SPEC.md
├── src/
│   ├── main/
│   │   ├── java/com/pparra/rssreader/
│   │   │   ├── RssReaderApplication.java
│   │   │   ├── domain/
│   │   │   │   ├── Source.java
│   │   │   │   ├── SourceType.java
│   │   │   │   ├── FetchStatus.java
│   │   │   │   └── Article.java
│   │   │   ├── config/
│   │   │   │   └── SchemaMigration.java
│   │   │   ├── repository/
│   │   │   │   ├── SourceRepository.java
│   │   │   │   └── ArticleRepository.java
│   │   │   ├── service/
│   │   │   │   ├── SourceService.java
│   │   │   │   ├── ArticleService.java
│   │   │   │   ├── FetchService.java
│   │   │   │   ├── FetchSummary.java
│   │   │   │   ├── ArticleGroup.java
│   │   │   │   ├── ArticleContentService.java
│   │   │   │   └── OpmlService.java
│   │   │   ├── fetch/
│   │   │   │   ├── SourceTypeDetector.java
│   │   │   │   ├── RssFetcher.java
│   │   │   │   ├── ScrapeFetcher.java
│   │   │   │   ├── FeedItem.java
│   │   │   │   ├── SourceBlockedException.java
│   │   │   │   ├── ArticleContentFetcher.java
│   │   │   │   └── ArchiveClient.java
│   │   │   ├── scheduler/
│   │   │   │   └── DailyFetchJob.java
│   │   │   └── web/
│   │   │       ├── DashboardController.java
│   │   │       └── SourceController.java
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── templates/
│   │       │   ├── dashboard.html
│   │       │   ├── article.html
│   │       │   ├── fragments.html
│   │       │   └── sources.html
│   │       └── static/
│   │           └── style.css
│   └── test/
│       └── java/com/pparra/rssreader/
│           ├── fetch/          # RssFetcher / ScrapeFetcher unit tests
│           └── service/        # ArticleService / SourceService unit tests
└── data/
    └── app.db                  # SQLite file, gitignored
```

## 4. Core Interfaces (server-rendered MVC — no JSON API needed for MVP)

All endpoints are Thymeleaf-rendered pages / form-post actions handled by MVC controllers; no separate REST/JSON API layer for the MVP.

| Method | Path | Maps to Story | Behavior |
|---|---|---|---|
| `GET` | `/` | US-3 | Dashboard: articles grouped by source (all unread, plus articles read in the last 7 days), newest first inside each group, groups ordered by their most recent article; unread marked, read grayed; includes the "Fetch now" button |
| `GET` | `/articles/{id}` | US-1, US-3 | In-app reader: marks the article read (first time sets `readAt`) and renders its cleaned content via `ArticleContentService` (original page, or archive.ph snapshot when blocked) with links to the original post |
| `GET` | `/sources` | US-1 | List all sources with type/status |
| `POST` | `/sources` | US-1 | Add a new source (body: `url`, `name`); triggers type detection |
| `POST` | `/sources/{id}/delete` | US-1 | Removes source (and cascade-deletes its articles) |
| `POST` | `/sources/fetch-now` | US-2 | "Fetch now" button on `/sources`: runs the same `FetchService.fetchAll()` as the daily job, synchronously, then redirects back with a summary flash. Optional `from=dashboard` returns to `/`; any other value returns to `/sources` (whitelisted, never a user-supplied URL). If a run is already in progress it does nothing and says so |
| `POST` | `/sources/import` | US-4 | Multipart upload (`file`, `.opml`); bulk-creates sources, then redirects to `/sources` with an import summary |
| `GET` | `/sources/export` | US-4 | Downloads current sources as `subscriptions.opml` (`Content-Disposition: attachment`) |

### US-3 — Dashboard and reader

**Dashboard**
- `ArticleService.dashboardGroups()` loads every unread article plus those read within `app.dashboard.read-visible-days` (default 7, based on `readAt`), sorts them by `publishedAt` (fallback `fetchedAt`) descending, and groups them by source. Groups are ordered by their newest article. Articles whose source no longer exists are ignored.
- Order never depends on read state, so opening an article leaves it in place; it only changes appearance (`li.unread`: dot flag and bold title; `li.read`: gray title, no flag). `ArticleGroup.unreadCount()` feeds the per-author and total unread counts.
- `Article.markRead(Instant)` sets `read`/`readAt` the first time only. Articles stored as read by the first-fetch cap use `markReadSilently()` (no `readAt`), so they are never shown.
- `dashboard.html` links each article to `/articles/{id}`; empty state links to the sources page. `fragments.html` holds the `nav`, `flash` and `fetchForm(from)` fragments shared with the sources page, so the "Fetch now" button exists once. No pagination.
- Schema change: `read_at` and `content_version` columns. `schema.sql` creates them for new databases and `SchemaMigration` (`@PostConstruct`, runs after `schema.sql`) adds them to existing databases via `PRAGMA table_info` + `ALTER TABLE`, idempotently.

**Reader content (`ContentExtractor`)** — the reader shows only title, text and the post's own images, plus links to the original post (top and bottom). Rules, in order:
1. Pick the container: a known body selector (`[itemprop=articleBody]`, `.entry-content`, `.post-content`, `.article-body`, ...) with at least 200 chars; else the longest `<article>`; else `<main>`; else the `div`/`section` with the most paragraph text.
2. Strip non-content tags (`script`, `style`, `iframe`, `form`, `button`, `svg`, `nav`, `aside`, `footer`, `video`, ...), hidden elements and ARIA landmarks (`complementary`, `navigation`, `banner`, `contentinfo`, `search`), and every `h1` (the reader renders the stored title once).
3. Remove elements whose `class`/`id` words (split on non-alphanumerics and camelCase) include a noise word: related, recommended, trending, popular, share/sharing, social, follow, newsletter, subscribe, signup, comment(s), promo, sponsor, ad(s), sidebar, widget, breadcrumb, nav, menu, tags, byline, bio, meta, footer, cookie, modal, popup, ...
4. Cut an element and everything after it in the same parent when it is a short heading-like element matching a noise phrase ("Related articles", "Read more", "You may also like", "Share this", "Comments", "Noticias relacionadas", "Lee también", ...; English and Spanish).
5. Remove link-heavy blocks (`ul`, `ol`, `div`, `section`, `table` with 2+ links, link text over 60% of the block's text, under 1500 chars).
6. Safety guard for 3-5: never remove the container itself, nor an element holding more than 50% of the container's text, so a wrapper with an unlucky class name cannot wipe out the post.
7. Images: resolve lazy-loading (`data-src`, `data-lazy-src`, `data-original`, `srcset`/`data-srcset`, `<picture><source>`); drop images with no usable URL, under 50 px, tracking pixels/avatars/icons/logos (URL patterns, class words) or inside removed blocks. Output images get `loading=lazy` and `referrerpolicy=no-referrer`; relative URLs are made absolute.
8. Remove empty leftovers, then sanitize with the Jsoup `Safelist` (no scripts, styles, forms or event handlers; links get `rel="nofollow noopener noreferrer"` and `target="_blank"`).
- `ExtractedContent.textLength` is measured after cleaning (real article text); paywall markers are looked for before cleaning, because paywall prompts live in blocks that step 3 removes.
- Cached content is versioned (`Article.contentVersion`); bumping `ArticleContentService.CONTENT_VERSION` makes already-cached articles re-extract the next time they are opened.
- Heuristics cannot be perfect for every site; leftovers are fixed by extending the noise word/phrase lists or the body selectors.

### US-2 — Fetching (daily job and manual button)

- One code path: `DailyFetchJob` and the `/sources/fetch-now` button both call `FetchService.fetchAll()`. A `ReentrantLock.tryLock()` guards it, so overlapping runs (job + click, or a double click) are rejected rather than queued.
- Sources run sequentially. A failure in one source is caught, logged and recorded (`lastFetchStatus = ERROR`) and never aborts the run.
- `RSS`: fetch the feed (`feedUrl`, falling back to `url`) through `HttpFetcher`, parse with Rome (DOCTYPE disabled), resolve relative links against the feed URL, skip entries without an http(s) link.
- `SCRAPE`: fetch the page and take heading links (`h1`-`h3` anchors, or anchors wrapping a heading); if none, the longest link in each `<article>`. Keep only same-host links (ignoring `www.`) outside page-level `nav`/`header`/`footer`/`aside` (a `<header>` inside an `<article>` is fine), with a title of at least 15 characters; date from the nearest `<article>`'s `<time datetime>` when present.
- Dedupe by (`sourceId`, `url`); new rows are `read = false`. The first fetch of a source (no stored articles yet) keeps only the 10 newest items unread (`FIRST_FETCH_UNREAD_LIMIT`, newest by `publishedAt`, feed order for undated items) and stores the rest with `read = true`, so a new source doesn't flood the dashboard.
- A bot challenge or CAPTCHA on a source (`SourceBlockedException`) marks it `UNSUPPORTED` and it is skipped from then on; it is never retried or bypassed. Other errors (timeouts, 5xx, invalid feed) set `ERROR` and are retried at the next run.
- `FetchSummary`: sources fetched OK, new articles, failed, skipped (already or newly `UNSUPPORTED`). The sources page also shows each source's last fetch time and status.
- The daily job only runs while the app is running; it does not catch up on missed runs. The button covers that.

### US-1 (paywall part) — Transparent paywall fallback via archive.ph

The user clicks an article and reads it in-app; whether it was paywalled is not their concern. Flow in `ArticleContentService.load(articleId)`:

1. If `contentHtml` is cached, render it.
2. `ArticleContentFetcher` GETs the original URL and extracts the main content with Jsoup (`<article>`, `og`/JSON-LD hints, largest text block).
3. Treat the result as blocked when any of these hold: HTTP 401/402/403, a challenge page, JSON-LD `isAccessibleForFree: false`, or extracted text below a minimum length (configurable, e.g. 600 chars) alongside paywall markers.
4. If blocked, `ArchiveClient` GETs `https://archive.ph/newest/<original URL appended as-is, fragment removed>`, follows redirects to the snapshot, and extracts content the same way. Snapshot pages are checked for challenge/CAPTCHA markers and for a "no results" page.
5. Sanitize the extracted HTML with a Jsoup `Safelist` (no scripts, styles, forms or event handlers), cache it with `contentOrigin` (`ORIGINAL` or `ARCHIVE`), and render it in `article.html` with a link to the original.
6. If both attempts fail, store `UNAVAILABLE`, and show the reader page with a clear notice plus links to the original URL and to `archive.ph/newest/...` for the user to open manually.

Constraints:
- The backend only performs plain GET lookups of existing snapshots. It never submits new URLs to archive.ph and never attempts to solve or evade a CAPTCHA or bot challenge; a challenge response counts as a failed attempt.
- Fresh articles often have no snapshot yet, so transparent success cannot be guaranteed. Failures must degrade to step 6, never to an error page.
- Fetching is on demand (when the user opens an article), never in the daily job, and results are cached, so archive.ph traffic stays minimal. Use a normal, identifiable `User-Agent`, a short timeout, and no retries in a loop.
- `UNAVAILABLE` results can be retried manually from the reader page (re-run the flow).

### US-4 — OPML import/export

**Import**
- Accepts OPML 1.0/2.0. Walk all `<outline>` elements recursively (folders/categories are flattened); only outlines with an `xmlUrl` attribute become sources, the rest are ignored.
- Mapping: `xmlUrl` → `Source.url` and `feedUrl`; `title` (fallback `text`, then host name) → `name`; `type` = `RSS` directly, so no type detection probe is run on import.
- Duplicates (same `url` or `feedUrl` as an existing source, or repeated within the file) are skipped, not overwritten. Entries with a missing or malformed URL are counted as invalid.
- The result page shows counts: added, skipped duplicates, skipped invalid. One bad entry never aborts the whole import.
- Security: configure the XML parser to disable DTDs and external entities (XXE), and enforce a maximum upload size (e.g. 2 MB via Spring multipart config).

**Export**
- One `<outline type="rss" text/title=name xmlUrl=feedUrl-or-url htmlUrl=url>` per `RSS` source, flat under `<body>`, so any reader can import the file.
- `SCRAPE` and `UNSUPPORTED` sources have no feed URL and are not exported; the UI states how many were left out.
- The `paywalled` flag is app-specific and is not exported.

**Internal service interfaces** (called by controllers and the scheduled job — signatures only):

- `SourceService`: `create(url, name)`, `list()`, `delete(id)`
- `OpmlService`: `importOpml(InputStream)` returns an import summary (added / skipped duplicates / skipped invalid); `exportOpml()` returns the OPML document
- `ArticleService`: `unreadGroupedBySource()`, `markRead(id)`
- `ArticleContentService`: `load(articleId)` returns the cached or freshly fetched content plus its origin; `retry(articleId)` clears an `UNAVAILABLE` result and re-runs the flow
- `FetchService`: `fetchAll()` returns `Optional<FetchSummary>` (empty when a run is already in progress) — iterates non-`UNSUPPORTED` sources, delegates to `RssFetcher`/`ScrapeFetcher`, inserts new `Article` rows, updates `Source.lastFetchedAt`/`lastFetchStatus`
- `DailyFetchJob`: `@Scheduled(cron = "${app.fetch.cron}")` wrapper (default `0 0 7 * * *`) that calls `FetchService.fetchAll()` and logs the summary
