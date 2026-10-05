# RSS Reader

A single-user, local-only web app that shows a daily digest of unread articles from your favourite authors. Sources can be RSS/Atom feeds or plain web pages (scraped), and paywalled articles fall back to an existing archive.ph snapshot.

## Features

- Add and remove sources; the app detects whether a URL is a feed or a scrape target, and flags Cloudflare-protected sites as unsupported
- Daily scheduled fetch (default 07:00) plus a "Fetch now" button with a summary
- Dashboard grouped by author as image cards (thumbnail from the feed or the article), with unread/read state
- Hover a tile to mark an article as read or unread without opening it
- Loading spinner while articles open or sources are fetched
- Clean in-app reader (text and the post's own images, featured image first) with links to the original post
- Reads the full text carried by the feed when available (works for sites that challenge page requests), then the original page, then an existing archive.ph snapshot (plain lookups only, never bypasses a check)
- Articles that cannot be loaded are logged and marked on the dashboard with a hover explanation
- OPML import/export from the Sources page (RSS sources only; non-feed sources are reported as left out)

## Stack

Java 21, Spring Boot 3.3, Thymeleaf, Spring Data JPA with SQLite, Rome (RSS), Jsoup (scraping and content extraction), Maven.

## Run

Requires Java 21 and Maven.

```bash
mvn spring-boot:run
```

Then open http://localhost:8080. Data is stored in `data/app.db` (created on first run, gitignored).

With Docker:

```bash
docker compose up --build
```

## Test

```bash
mvn test
```

## Configuration

Set in `src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `app.fetch.cron` | `0 0 7 * * *` | Daily fetch schedule |
| `app.fetch.block-internal-hosts` | `true` | Refuse loopback and link-local addresses when fetching (set `false` to read feeds served from the same machine) |
| `app.dashboard.read-visible-days` | `7` | Days a read article stays on the dashboard |
| `app.content.min-text-length` | `600` | Minimum text length before content is treated as paywalled |
| `app.archive.base-url` | `https://archive.ph` | Snapshot lookup service |

## Project docs

- [BACKLOG.md](BACKLOG.md): user stories and acceptance criteria
- [TECH_SPEC.md](TECH_SPEC.md): architecture and technical decisions
