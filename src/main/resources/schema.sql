CREATE TABLE IF NOT EXISTS source (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    url               TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    type              TEXT NOT NULL,
    feed_url          TEXT,
    last_fetched_at   TIMESTAMP,
    last_fetch_status TEXT,
    created_at        TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS article (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id          INTEGER NOT NULL REFERENCES source (id),
    url                TEXT NOT NULL,
    title              TEXT NOT NULL,
    published_at       TIMESTAMP,
    fetched_at         TIMESTAMP NOT NULL,
    is_read            BOOLEAN NOT NULL DEFAULT 0,
    read_at            TIMESTAMP,
    content_html       TEXT,
    content_origin     TEXT,
    content_fetched_at TIMESTAMP,
    content_version    INTEGER,
    content_failure_reason TEXT,
    feed_content_html  TEXT,
    image_url          TEXT,
    UNIQUE (source_id, url)
);
