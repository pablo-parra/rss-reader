# Technical Specification — RSS Reader MVP

Reference document for implementation. Scope is strictly the backlog user stories in [BACKLOG.md](BACKLOG.md) (US-1 manage sources incl. paywall fallback, US-2 daily + on-demand fetch, US-3 view/read unread list, US-4 OPML import/export, US-5 dashboard by source, US-6 mark all as read). No functional code included here.

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

Source type is determined at creation time by probing the URL (look for `<link rel="alternate" type="application/rss+xml">`; if none, and page fetch fails/returns a challenge page, mark `UNSUPPORTED`; otherwise `SCRAPE`).

The type can change later: "Check again" on the sources page re-runs detection for an `UNSUPPORTED` source, and a fetch of an `RSS` source that returns a web page re-detects once (see US-2).

## 2. Data Models

### `Source`
| Field | Type | Notes |
|---|---|---|
| `id` | Long (PK) | auto-generated |
| `url` | String | unique, the feed or page URL as entered |
| `name` | String | display name (author/site), user-editable |
| `type` | Enum: `RSS`, `SCRAPE`, `UNSUPPORTED` | set at creation; re-detected by "Check again" or when an `RSS` source returns a web page |
| `feedUrl` | String, nullable | resolved feed URL if different from `url` (discovered `<link rel="alternate">`) |
| `lastFetchedAt` | Instant, nullable | updated after each fetch attempt |
| `lastFetchStatus` | Enum: `OK`, `ERROR`, `UNSUPPORTED` | last run outcome |
| `lastFetchError` | String (TEXT), nullable | why the last fetch failed or was blocked (shown under the status on the sources page); cleared by a successful fetch; the column is added to existing databases by `SchemaMigration` |
| `createdAt` | Instant | |

### `Article`
| Field | Type | Notes |
|---|---|---|
| `id` | Long (PK) | auto-generated |
| `sourceId` | Long (FK → Source) | |
| `url` | String | unique per source, used for dedupe |
| `title` | String | |
| `publishedAt` | Instant, nullable | from the feed, or for scraped sources from the card's `<time>`, the page's JSON-LD or a date in the URL; filled in later by a fetch when it was missing; the dashboard falls back to `fetchedAt` |
| `fetchedAt` | Instant | when we first saw it |
| `read` | boolean | default `false` |
| `readAt` | Instant, nullable | when the user first opened the article; null for articles stored as read by the first-fetch cap (they only appear in a source view if they are among its newest articles) |
| `contentHtml` | String (TEXT), nullable | sanitized article body cached on first open (US-1); null until opened |
| `contentOrigin` | Enum: `FEED`, `ORIGINAL`, `ARCHIVE`, `UNAVAILABLE`, nullable | where `contentHtml` came from, or that no source could be read |
| `contentFetchedAt` | Instant, nullable | when the content was cached |
| `feedContentHtml` | String (TEXT), nullable | raw full-article HTML carried by the feed entry (RSS `content:encoded` / Atom `<content>`); kept unsanitized so it can be re-extracted when `CONTENT_VERSION` changes; null when the feed only carries excerpts |
| `imageUrl` | String (TEXT), nullable | dashboard thumbnail: taken from the feed entry at fetch time (first image of its HTML, else image enclosure, `media:content`, `media:thumbnail`), for scraped sources, from the listing card (lazy-loaded images included). Opening an article replaces it with the page's declared main image (`og:image` / `twitter:image`, also for paywalled pages shown from the archive); only when there is none, the first content image fills a missing thumbnail (author photos, logos and lazy placeholders are skipped). Fetches only fill it when missing |
| `contentFailureReason` | String (TEXT), nullable | why the last load attempt failed (original page and archive.ph reasons); set only with `UNAVAILABLE`, cleared on success; shown as a tooltip on the dashboard and in the reader notice |
| `contentVersion` | Integer, nullable | version of the extraction rules that produced `contentHtml`; content with an older or missing version is re-extracted on next open (`ArticleContentService.CONTENT_VERSION`, currently 4; bump it whenever the extractor changes) |

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
│   │   │   │   ├── ContentOrigin.java
│   │   │   │   └── Article.java
│   │   │   ├── config/
│   │   │   │   └── SchemaMigration.java
│   │   │   ├── repository/
│   │   │   │   ├── SourceRepository.java
│   │   │   │   └── ArticleRepository.java
│   │   │   ├── service/
│   │   │   │   ├── SourceService.java
│   │   │   │   ├── ArticleService.java
│   │   │   │   ├── Dashboard.java / SourceUnread.java / ArticleRow.java
│   │   │   │   ├── FetchService.java
│   │   │   │   ├── FetchSummary.java
│   │   │   │   ├── ArticleContentService.java / ArticleContent.java
│   │   │   │   ├── ArticleNotFoundException.java / SourceNotFoundException.java
│   │   │   │   ├── OpmlService.java
│   │   │   │   └── OpmlImportSummary.java / OpmlExport.java / InvalidOpmlException.java
│   │   │   ├── fetch/
│   │   │   │   ├── HttpFetcher.java / JdkHttpFetcher.java / HostGuard.java / FetchedPage.java
│   │   │   │   ├── SourceTypeDetector.java / ChallengeDetector.java
│   │   │   │   ├── RssFetcher.java / ScrapeFetcher.java / FeedItem.java
│   │   │   │   ├── SourceBlockedException.java / NotAFeedException.java
│   │   │   │   ├── ArticleContentFetcher.java / ArchiveClient.java / ContentAttempt.java
│   │   │   │   └── ContentExtractor.java / ExtractedContent.java
│   │   │   ├── scheduler/
│   │   │   │   └── DailyFetchJob.java
│   │   │   └── web/
│   │   │       ├── DashboardController.java
│   │   │       ├── ArticleController.java
│   │   │       ├── SourceController.java
│   │   │       └── UploadErrorAdvice.java
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── templates/
│   │       │   ├── dashboard.html
│   │       │   ├── article.html
│   │       │   ├── fragments.html
│   │       │   └── sources.html
│   │       └── static/
│   │           ├── style.css
│   │           └── app.js
│   └── test/
│       └── java/com/pparra/rssreader/
│           ├── config/         # SchemaMigration
│           ├── fetch/          # fetchers, extractor, detector, HostGuard, JdkHttpFetcher (local HTTP server)
│           ├── service/        # service unit tests (Mockito)
│           └── web/            # MockMvc flow tests and one acceptance-criteria class per story (Us1..Us5)
└── data/
    └── app.db                  # SQLite file, gitignored
```

## 4. Core Interfaces (server-rendered MVC — no JSON API needed for MVP)

All endpoints are Thymeleaf-rendered pages / form-post actions handled by MVC controllers; no separate REST/JSON API layer for the MVP.

| Method | Path | Maps to Story | Behavior |
|---|---|---|---|
| `GET` | `/` | US-3, US-5 | Dashboard. Without parameters, the "All" view: the unread articles of every source, newest first, ties by source name. With `source={id}`, that source's 10 newest articles (read or not) plus any older unread ones; an unknown id falls back to "All". The left panel lists the sources with their unread counts; unread marked, read grayed; includes the "Fetch now" button |
| `GET` | `/articles/{id}` | US-1, US-3 | In-app reader: marks the article read (first time sets `readAt`) and renders its cleaned content via `ArticleContentService` (original page, or archive.ph snapshot when blocked) with links to the original post |
| `GET` | `/sources` | US-1 | List all sources with type/status |
| `POST` | `/articles/{id}/read` | US-3 | Marks the article read without loading its content (first time sets `readAt`), then redirects to `/#article-{id}` (or `/?source={id}#article-{id}` when the optional `source` parameter is sent) so the dashboard returns to the same view and tile |
| `POST` | `/articles/read-all` | US-6 | Marks every unread article of the source given in the required `source` parameter as read (one `update` statement, `readAt` = now; articles already read are untouched), then redirects to `/?source={id}` with a flash message with the count. Unknown source: 404; missing parameter: 400. Never on GET |
| `POST` | `/articles/{id}/unread` | US-3 | Marks the article unread (`read = false`, `readAt = null`), so it shows again as unread even if it had been read long ago; same redirect, `source` parameter included |
| `POST` | `/sources` | US-1 | Add a new source (body: `url`, `name`); triggers type detection |
| `POST` | `/sources/{id}/delete` | US-1 | Removes source (and cascade-deletes its articles) |
| `POST` | `/sources/fetch-now` | US-2 | "Fetch now" button on `/sources`: runs the same `FetchService.fetchAll()` as the daily job, synchronously, then redirects back with a summary flash. Optional `from=dashboard` returns to `/`; any other value returns to `/sources` (whitelisted, never a user-supplied URL). If a run is already in progress it does nothing and says so |
| `POST` | `/sources/import` | US-4 | Multipart upload (`file`, `.opml`); bulk-creates sources, then redirects to `/sources` with an import summary |
| `GET` | `/sources/export` | US-4 | Downloads current sources as `subscriptions.opml` (`Content-Disposition: attachment`) |

### US-3 / US-5 — Dashboard and reader

**Dashboard**
- `ArticleService.dashboard(sourceId)` returns a `Dashboard`: the source list (`SourceUnread` per source that has articles, alphabetical, from one `group by` query), the total unread count, the selected source (null for "All") and the tiles (`ArticleRow` = article + source). Articles whose source no longer exists are ignored.
- "All": every unread article, sorted by `publishedAt` (fallback `fetchedAt`) descending, then source name (case-insensitive), then id. A source view: the newest `app.dashboard.source-view-size` (default 10) articles of the source, read or not, plus every unread one, newest first. The unread count of a source therefore always matches what is shown. The old rule that kept read articles for 7 days (`app.dashboard.read-visible-days`) was removed.
- Dashboard queries use a JPQL constructor expression that fills a read-only `Article` without the large HTML columns; these instances are never saved.
- US-6: `dashboard.html` shows a `form.mark-all` ("Mark all as read (N)") only in a source view with unread articles, in the header of the tiles panel (`div.pane-header`: the page heading `h1` on the left, the button on the right), right above the tiles, so it is clearly scoped to the articles listed (not next to "Fetch now", which acts on every source). The form carries `data-confirm`; `app.js` asks with `window.confirm` on submit and cancels the submit (before the loading overlay appears) when declined.
- Order never depends on read state, so opening an article leaves it in place in a source view; it only changes appearance (`li.unread`: dot flag and bold title; `li.read`: gray title, no flag). In "All" a read article simply drops out on the next load.
- `Article.markRead(Instant)` sets `read`/`readAt` the first time only. Articles stored as read by the first-fetch cap use `markReadSilently()` (no `readAt`), so they are never shown.
- `dashboard.html` has the source list on the left, the heading ("Latest articles" or the source name with its unread count) at the top of the tiles panel, (`aside.source-list`, stacked above the tiles under 48rem) and shows the articles as a grid of cards (`ul.articles.cards`): thumbnail (`Article.imageUrl`, lazy-loaded with `referrerpolicy=no-referrer`) or a letter placeholder tile when there is none, bold title with unread dot (gray when read), date, and the failure marker; the whole card is the link to `/articles/{id}`. Each tile has a `.card-menu` inside it with one button: "Mark as read" for unread articles, "Mark as unread" for read ones (POST forms, never GET). The menu is revealed on `:hover` and `:focus-within`, and always visible on touch devices (`@media (hover: none)`). `Article.markUnread()` resets `read` and `readAt`; `ArticleService.markUnread(id)` is its transactional wrapper, mirroring `markRead(id)`. Existing articles get their thumbnail when a fetch backfills it or when they are first opened. Empty state links to the sources page. `fragments.html` holds the `nav`, `flash` and `fetchForm(from, source)` fragments shared with the sources page, so the "Fetch now" button exists once; the dashboard passes the selected source so the redirect returns to the same view (`POST /sources/fetch-now` accepts the optional numeric `source`). In "All" each card also shows the source name. No pagination.
- Schema change: `read_at` and `content_version` columns. `schema.sql` creates them for new databases and `SchemaMigration` (`@PostConstruct`, runs after `schema.sql`) adds them to existing databases via `PRAGMA table_info` + `ALTER TABLE`, idempotently.

**Reader typography:** `article.html` wraps the cleaned post HTML in `div.reader-content`; only that container uses the reading font stack `Charter, "Bitstream Charter", "Sitka Text", Cambria, Georgia, serif` (system fonts, nothing is downloaded) at 1.3rem (about 21px) with a 1.65 line height, inside the 48rem reader column (about 70 to 75 characters per line). The title and the `reader-meta` line stay in the interface font, which is the visual separation between the post's text and the app's own text. Code blocks keep a monospace font. Static files are served with `Cache-Control: no-cache` (`spring.web.resources.cache.cachecontrol.no-cache`), so browsers revalidate them on every load (a 304 when unchanged) and a new build's CSS/JS is never hidden by a heuristically cached copy. A bundled web font (for example Literata) could replace the stack with an `@font-face` rule and a font file under `static/`.

**Reader content (`ContentExtractor`)** — the reader shows only title, text and the post's own images, plus links to the original post (top and bottom). Rules, in order:
1. Pick the container: a known body selector (`[itemprop=articleBody]`, `.entry-content`, `.post-content`, `.article-body`, ...) with at least 200 chars; else the longest `<article>`; else `<main>`; else the `div`/`section` with the most paragraph text.
2. Strip non-content tags (`script`, `style`, `iframe`, `form`, `button`, `svg`, `nav`, `aside`, `footer`, `video`, ...), hidden elements and ARIA landmarks (`complementary`, `navigation`, `banner`, `contentinfo`, `search`), and every `h1` (the reader renders the stored title once).
3. Remove elements whose `class`/`id` words (split on non-alphanumerics and camelCase) include a noise word: related, recommended, trending, popular, share/sharing, social, follow, newsletter, subscribe, signup, comment(s), promo, sponsor, ad(s), sidebar, widget, breadcrumb, nav, menu, tags, byline, bio, meta, footer, cookie, modal, popup, ...
4. Cut an element and everything after it in the same parent when it is a short heading-like element matching a noise phrase ("Related articles", "Read more", "You may also like", "Share this", "Comments", "Noticias relacionadas", "Lee también", ...; English and Spanish).
5. Remove link-heavy blocks (`ul`, `ol`, `div`, `section`, `table` with 2+ links, link text over 60% of the block's text, under 1500 chars).
6. Safety guard for 3-5: never remove the container itself, nor an element holding more than 50% of the container's text, so a wrapper with an unlucky class name cannot wipe out the post.
7. Images: the article's featured image is shown first when the theme renders it above the body container (`img.wp-post-image`, `.post-thumbnail img`, `.featured-image img`, `[itemprop=image] img`, searched inside the enclosing `<article>` only, skipping noise blocks and images the body already contains). Resolve lazy-loading (`data-src`, `data-lazy-src`, `data-original`, `data-orig-src`, `srcset`/`data-srcset`, `<picture><source>`; `data:` placeholders are never used as the URL); drop images with no usable URL, under 50 px, tracking pixels/avatars/icons/logos (URL patterns, class words) or inside removed blocks. Output images get `loading=lazy` and `referrerpolicy=no-referrer`; relative URLs are made absolute.
8. Remove empty leftovers, then sanitize with the Jsoup `Safelist` (no scripts, styles, forms or event handlers; links get `rel="nofollow noopener noreferrer"` and `target="_blank"`).
- `ExtractedContent.textLength` is measured after cleaning (real article text); paywall markers are looked for before cleaning, because paywall prompts live in blocks that step 3 removes.
- Cached content is versioned (`Article.contentVersion`); bumping `ArticleContentService.CONTENT_VERSION` makes already-cached articles re-extract the next time they are opened.
- Heuristics cannot be perfect for every site; leftovers are fixed by extending the noise word/phrase lists or the body selectors.

**Loading indicator** — opening an article can take seconds (page fetch, archive.ph lookup), and so can "Fetch now". Links and forms with a `data-loading="message"` attribute (article links, both fetch buttons, the reader's Retry button) show a full-screen overlay with a spinner and that message while the browser waits; `static/app.js` (about 40 lines, no build step) adds it on click/submit, disables the submitted button, and removes it on `pageshow` so the back button never shows a stale overlay. The overlay fades in after 250 ms so fast navigations show no flash, and respects `prefers-reduced-motion`.

### US-2 — Fetching (daily job and manual button)

- One code path: `DailyFetchJob` and the `/sources/fetch-now` button both call `FetchService.fetchAll()`. A `ReentrantLock.tryLock()` guards it, so overlapping runs (job + click, or a double click) are rejected rather than queued.
- Downloads run concurrently (virtual threads, at most 4 at a time, network time dominates); results are then stored one source at a time in name order, so the run's behavior stays predictable. A failure in one source is caught, logged and recorded (`lastFetchStatus = ERROR` plus the reason in `lastFetchError`) and never aborts the run.
- Each source is stored in one transaction (`TransactionTemplate`): a failure part-way rolls the source back, so the next run is still its first fetch. If the source was removed while the run was in progress, its result is dropped and the source is not re-created.
- `RSS`: fetch the feed (`feedUrl`, falling back to `url`) through `HttpFetcher`, parse with Rome (DOCTYPE disabled), resolve relative links against the feed URL, skip entries without an http(s) link. Each `FeedItem` also carries a thumbnail `imageUrl` (the first image of the entry HTML wins over `media:content`/`media:thumbnail`, because WordPress feeds only provide 150x150 crops there) and the entry's full content when present (`content:encoded` via Rome foreign markup, or Atom `<content>`); `description`/`summary` are treated as excerpts and ignored.
- `SCRAPE`: fetch the page and take heading links (`h1`-`h3` anchors, or anchors wrapping a heading); if none, the longest link in each `<article>`. Keep only same-host links (ignoring `www.`) outside page-level `nav`/`header`/`footer`/`aside` (a `<header>` inside an `<article>` is fine), with a title of at least 15 characters; the thumbnail comes from the nearest `<article>` card (`ContentExtractor.firstImageUrl`, which resolves lazy-loaded images and skips placeholders and author photos). The date comes, in order, from the card's `<time datetime>`, the page's JSON-LD (any object with `url` and `datePublished`, at any nesting, as in profile pages with `hasPart`), or a date in the URL path (`/2026-10-05/`, `/2026/10/05/`, taken at noon UTC).
- Dedupe by (`sourceId`, `url`); new rows are `read = false`. New rows store the item's full content in `feedContentHtml`. Existing URLs and backfill candidates are loaded with one query each per source (no per-item lookups). An already stored article that has no `feedContentHtml`, `imageUrl` or `publishedAt` gets them backfilled when a later fetch carries it (a previously `UNAVAILABLE` result is reset so the reader retries with the feed content; content already loaded from the page is kept). The first fetch of a source (no stored articles yet) keeps only the 10 newest items unread (`FIRST_FETCH_UNREAD_LIMIT`, newest by `publishedAt`, feed order for undated items) and stores the rest with `read = true`, so a new source doesn't flood the dashboard.
- A bot challenge or CAPTCHA on a source (`SourceBlockedException`) marks it `UNSUPPORTED` and it is skipped by fetches from then on; the app never bypasses the challenge. The user can run "Check again" (`POST /sources/{id}/recheck`, `SourceService.recheck`), which repeats type detection and brings the source back if it works now. `ChallengeDetector` treats a captcha marker as a challenge only on pages with almost no text (under 1000 characters), so a real page that embeds a captcha widget is not blocked. Other errors (timeouts, 5xx, invalid feed) set `ERROR` with the reason and are retried at the next run.
- An `RSS` source whose URL returns a web page (`RssFetcher` throws `NotAFeedException`: HTML content type or `<!doctype html>`/`<html>` body) is re-detected once with `SourceTypeDetector`: a feed linked from the page becomes its `feedUrl`; otherwise the source becomes `SCRAPE`. The new type is saved with the fetch result. If detection finds nothing usable, the original error is reported. This repairs OPML entries that point to an author page and feeds that moved.
- `FetchSummary`: sources fetched OK, new articles, failed, skipped (already or newly `UNSUPPORTED`). The sources page also shows each source's last fetch time and status.
- The daily job only runs while the app is running; it does not catch up on missed runs. The button covers that.

**HTTP fetching (`JdkHttpFetcher`)**
- Redirects are followed by hand (at most 5), and `HostGuard` checks every hop: only http(s), and no loopback, link-local (cloud metadata at 169.254.169.254), unspecified or multicast addresses. Private LAN ranges stay allowed. `app.fetch.block-internal-hosts=false` disables the check. Limitation: the host name is resolved by the guard and again by the client, so DNS rebinding is not covered.
- At most 2 MB of the body is read (a larger feed is truncated). The charset comes from the `Content-Type` header, else from the XML prolog or HTML meta tag, else UTF-8.

### US-1 (paywall part) — Transparent paywall fallback via archive.ph

The user clicks an article and reads it in-app; whether it was paywalled is not their concern. Flow in `ArticleContentService.load(articleId)`:

1. If `contentHtml` is cached (current `contentVersion`), render it.
2. **Feed content first:** if the article has `feedContentHtml`, run it through `ContentExtractor` (same cleaning and sanitizing as pages). When the cleaned text reaches `app.content.min-text-length` and no paywall is declared, cache it with `contentOrigin = FEED` and stop: no request to the original page or archive.ph is made. Shorter content is treated as an excerpt and the flow continues. This is why sites that challenge page requests (Cloudflare) but publish full-text feeds still read fine.
3. `ArticleContentFetcher` GETs the original URL and extracts the main content with Jsoup (`<article>`, `og`/JSON-LD hints, largest text block). The extractor also reads the page's declared main image (`og:image`, else `twitter:image`) into `ExtractedContent.pageImage`; `ContentAttempt` carries it for successful and failed attempts alike (a paywalled page still declares it).
4. Treat the result as blocked when any of these hold: HTTP 401/402/403, a challenge page, JSON-LD `isAccessibleForFree: false`, or extracted text below a minimum length (configurable, e.g. 600 chars) alongside paywall markers.
5. If blocked, `ArchiveClient` GETs `https://archive.ph/newest/<original URL appended as-is, fragment removed>`, follows redirects to the snapshot, and extracts content the same way. Snapshot pages are checked for challenge/CAPTCHA markers and for a "no results" page.
6. Sanitize the extracted HTML with a Jsoup `Safelist` (no scripts, styles, forms or event handlers), cache it with `contentOrigin` (`FEED`, `ORIGINAL` or `ARCHIVE`), and render it in `article.html` with a link to the original. When the original page declared a main image, it replaces the article's thumbnail (whichever origin the text came from, except `FEED`); the save re-reads the article first, so a read/unread change made during the slow load is not overwritten.
7. If all attempts fail, store `UNAVAILABLE` together with `contentFailureReason`, log a warning (article id, URL, reason) and show the reader page with a clear notice plus links to the original URL and to `archive.ph/newest/...` for the user to open manually.

Constraints:
- The backend only performs plain GET lookups of existing snapshots. It never submits new URLs to archive.ph and never attempts to solve or evade a CAPTCHA or bot challenge; a challenge response counts as a failed attempt.
- Fresh articles often have no snapshot yet, so transparent success cannot be guaranteed. Failures must degrade to step 6, never to an error page.
- Page and archive.ph fetching is on demand (when the user opens an article), never in the daily job (only the feed itself is fetched then), and results are cached, so archive.ph traffic stays minimal. Use a normal, identifiable `User-Agent`, a short timeout, and no retries in a loop.
- `UNAVAILABLE` results can be retried manually from the reader page (re-run the flow).
- Failure reasons come from `ContentAttempt` (returned by `ArticleContentFetcher.fetchReadable` and `ArchiveClient.fetchSnapshot`): request failed, bot challenge (HTTP status), HTTP status, declared paywall, no readable text, teaser only, no snapshot, archive.ph check, snapshot too short. The dashboard marks each `UNAVAILABLE` article with a red info icon whose tooltip is "Could not be loaded. <reason>"; articles only show it after they have been opened once, because content is loaded on demand.

### US-4 — OPML import/export

**Import**
- Accepts OPML 1.0/2.0. Walk all `<outline>` elements recursively (folders/categories are flattened); only outlines with an `xmlUrl` attribute become sources, the rest are ignored.
- Mapping: `xmlUrl` → `Source.url` and `feedUrl`; `title` (fallback `text`, then host name) → `name`; `type` = `RSS` directly, so no type detection probe is run on import.
- Duplicates (same `url` or `feedUrl` as an existing source, or repeated within the file) are skipped, not overwritten. Entries with a missing or malformed URL are counted as invalid.
- The result page shows counts: added, skipped duplicates, skipped invalid. One bad entry never aborts the whole import.
- Security: configure the XML parser to disable DTDs and external entities (XXE), and enforce a maximum upload size of 2 MB, both via the Spring multipart config (rejected by `UploadErrorAdvice` with a flash error, since it fails before any controller runs) and in `OpmlService`. The `X-Sources-Left-Out` response header and the Sources page state how many non-feed sources are left out of an export.

**Export**
- One `<outline type="rss" text/title=name xmlUrl=feedUrl-or-url htmlUrl=url>` per `RSS` source, flat under `<body>`, so any reader can import the file.
- `SCRAPE` and `UNSUPPORTED` sources have no feed URL and are not exported; the UI states how many were left out.
- The `paywalled` flag is app-specific and is not exported.

**Internal service interfaces** (called by controllers and the scheduled job — signatures only):

- `SourceService`: `create(url, name)`, `list()`, `delete(id)`
- `OpmlService`: `importOpml(InputStream)` returns an `OpmlImportSummary` (added / skipped duplicates / skipped invalid) and throws `InvalidOpmlException` (empty, over 2 MB, malformed, not OPML, or any DOCTYPE) before storing anything; `exportOpml()` returns an `OpmlExport` (XML, exported count, left-out count)
- `ArticleService`: `dashboard(sourceId)`, `markRead(id)`, `markUnread(id)`, `markAllRead(sourceId)` (transactional bulk update through `ArticleRepository.markAllReadBySourceId`; throws `SourceNotFoundException` for an unknown source)
- `ArticleContentService`: `load(articleId)` returns the cached or freshly fetched content plus its origin and failure reason (feed content, then original page, then archive.ph); `retry(articleId)` clears an `UNAVAILABLE` result and re-runs the flow
- `FetchService`: `fetchAll()` returns `Optional<FetchSummary>` (empty when a run is already in progress) — iterates non-`UNSUPPORTED` sources, delegates to `RssFetcher`/`ScrapeFetcher`, inserts new `Article` rows, updates `Source.lastFetchedAt`/`lastFetchStatus`
- `DailyFetchJob`: `@Scheduled(cron = "${app.fetch.cron}")` wrapper (default `0 0 7 * * *`) that calls `FetchService.fetchAll()` and logs the summary
