# Product Backlog — RSS Reader MVP

## Epic
**EPIC-1: Daily Unread Digest for Favourite Authors**
Enable a single user to register author sources (RSS or scraped) and see a daily list of unread articles in a local web dashboard.

Technical details per story: see [TECH_SPEC.md](TECH_SPEC.md).

---

### US-1: Manage source list
**As a** reader
**I want to** add and remove author URLs from my source list, and read their articles even when paywalled
**So that** I control which authors' content gets tracked and can read everything in one place

**Acceptance Criteria:**
- Given a URL, the system detects and stores whether it's a true RSS/Atom feed or a scrape target
- I can view the current list of sources and remove any of them
- If a source is Cloudflare-protected/unsupported, it's added but clearly flagged as "unsupported" instead of failing silently, and I can ask the app to check it again later
- When a fetch of a source fails, the sources list shows the reason (for example "HTTP 400" or "the URL returns a web page, not a feed")
- If a source saved as a feed turns out to be a web page (for example an imported OPML entry that points to an author page), the app uses the feed that page links to, or scrapes the page, instead of failing on every run
- When I open a paywalled article, it is shown in the app like any other post: the backend detects the block and fetches an existing archive.ph snapshot, so I don't need to know it was paywalled
- If no snapshot can be read (none exists, or archive.ph shows a check), the app says so and offers links to the original and to archive.ph; it never tries to bypass a check or submit new pages

---

### US-2: Fetch new articles daily and on demand
**As a** reader
**I want to** have new articles automatically pulled from all my sources once a day, and be able to refresh them myself whenever I need
**So that** I don't have to manually check each site, and can catch new posts between the daily runs

**Acceptance Criteria:**
- A scheduled job runs daily and fetches new items from every non-unsupported source (RSS parse or HTML scrape)
- Already-seen articles (by URL) are not re-added as duplicates
- Each fetched article is stored with title, URL, source, and an unread status defaulting to true, plus its publication date and thumbnail image when the feed or the listing page provides them (articles stored earlier get them on a later fetch)
- The first fetch of a new source keeps only its 10 newest posts as unread (older ones are stored as already read), so a new author doesn't flood the list
- A "Fetch now" button runs the same process as the daily job on demand and shows a summary (new articles, failures, skipped sources); if a fetch is already running, it says so instead of starting another

---

### US-3: View and read the daily unread list
**As a** reader
**I want to** see my authors' latest articles in a web dashboard, tell read from unread at a glance, and read each article as clean text and images
**So that** I can quickly catch up, keep track of what I've read, and read without distractions

**Acceptance Criteria:**
- Dashboard loads and lists articles as tiles, newest first; which articles are listed depends on the selected view (see US-5)
- Unread articles show an unread marker and bold title; articles I have read are shown in gray without the marker in the source views (see US-5), and the unread counts update
- Clicking an article opens it in the app's reader view and marks it as read, without moving it in the list of its source view
- The reader view shows only the title, the article text and the images that belong to the post; it leaves out related articles, share/social buttons, newsletter and subscribe boxes, comments, ads, author boxes, tags, menus and other page furniture
- When I open an article, its tile picture becomes the post's own main image (never the author's photo or a site logo)
- The reader view has a link (at the top and at the bottom) to the original post, so I can open it on the blog with all its extra content
- Hovering a tile on the dashboard reveals a menu inside it to mark the article as read (if unread) or as unread (if read) without opening it; the unread counts update and the tile stays in place in a source view (in "All" it leaves the list, since "All" only lists unread articles)
- The dashboard has the same "Fetch now" button as the sources page and returns to the same dashboard view with the fetch summary

---

### US-4: Import and export subscriptions (OPML)
**As a** reader
**I want to** import my old subscriptions from an OPML file and export my current ones to OPML
**So that** I can bring my existing list in and move to any other RSS provider

**Acceptance Criteria:**
- Uploading an OPML file creates a source for each feed entry, skipping duplicates and invalid entries, and shows how many were added and skipped
- Exporting downloads a valid OPML file with all my RSS sources, and states how many non-feed sources were left out
- A malformed or oversized file is rejected with a clear error and changes nothing

---

### US-5: Browse the dashboard by source
**As a** reader
**I want to** see a list of my sources with their unread counts on the left of the dashboard, an "All" view with everything unread, and a view per source
**So that** a long list of sources does not bury me in articles and I can catch up one author at a time

**Acceptance Criteria:**
- The left panel starts with "All", showing the total number of unread articles, followed by one entry per source that has articles, in alphabetical order, each with its own unread count; a source with no unread articles stays in the list, dimmed, with (0)
- "All" is the default view. It lists the unread articles of every source as one set of tiles, newest first and, for articles with the same publication date, ordered by source name. Each tile shows the name of its source. Read articles are not shown
- Clicking a source shows its 10 newest articles, read or unread, newest first, as tiles like the ones in "All" (read ones in gray, without the unread marker). An older unread article is still shown, so the unread count of the source always matches what is on screen
- The selected entry of the panel is highlighted, and the heading shows the source name with its unread count
- Marking an article as read or unread, and using "Fetch now", return to the same view (the selected source stays selected). In "All", a tile that is marked as read leaves the list; in a source view it stays in place
- On a narrow screen the panel is shown above the tiles

---

### US-6: Mark all articles of a source as read
**As a** reader
**I want to** mark all the unread articles of a source as read with one click
**So that** when I start using the app and most of the fetched articles are ones I already read elsewhere, or do not care about, I do not have to mark them one by one

**Acceptance Criteria:**
- A "Mark all as read" button, showing how many articles it will affect, appears in a source view that has unread articles; it is not shown in "All" or in a source without unread articles
- Before anything changes the app asks for confirmation, naming the source and the number of articles; cancelling changes nothing
- Confirming marks every unread article of that source as read, including the ones older than the 10 shown in its view, and no article of any other source
- Articles that were already read keep their original read date
- I return to the same source view with a message saying how many articles were marked (or that there was nothing to mark); the unread counts of the panel and of "All" update, and the articles stay in the source view as read

