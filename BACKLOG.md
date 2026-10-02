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
- If a source is Cloudflare-protected/unsupported, it's added but clearly flagged as "unsupported" instead of failing silently
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
- Each fetched article is stored with title, URL, source, and an unread status defaulting to true
- The first fetch of a new source keeps only its 10 newest posts as unread (older ones are stored as already read), so a new author doesn't flood the list
- A "Fetch now" button runs the same process as the daily job on demand and shows a summary (new articles, failures, skipped sources); if a fetch is already running, it says so instead of starting another

---

### US-3: View and read the daily unread list
**As a** reader
**I want to** see my authors' latest articles grouped by author in a web dashboard, tell read from unread at a glance, and read each article as clean text and images
**So that** I can quickly catch up, keep track of what I've read, and read without distractions

**Acceptance Criteria:**
- Dashboard loads and lists articles grouped by author, newest first inside each group, with the author who published most recently at the top
- Unread articles show an unread marker and bold title; articles I have read stay on the dashboard in gray without the marker (for 7 days after reading), and the unread counts update
- Clicking an article opens it in the app's reader view and marks it as read, without moving it in the list
- The reader view shows only the title, the article text and the images that belong to the post; it leaves out related articles, share/social buttons, newsletter and subscribe boxes, comments, ads, author boxes, tags, menus and other page furniture
- The reader view has a link (at the top and at the bottom) to the original post, so I can open it on the blog with all its extra content
- The dashboard has the same "Fetch now" button as the sources page and returns to the dashboard with the fetch summary

---

### US-4: Import and export subscriptions (OPML)
**As a** reader
**I want to** import my old subscriptions from an OPML file and export my current ones to OPML
**So that** I can bring my existing list in and move to any other RSS provider

**Acceptance Criteria:**
- Uploading an OPML file creates a source for each feed entry, skipping duplicates and invalid entries, and shows how many were added and skipped
- Exporting downloads a valid OPML file with all my RSS sources, and states how many non-feed sources were left out
- A malformed or oversized file is rejected with a clear error and changes nothing
