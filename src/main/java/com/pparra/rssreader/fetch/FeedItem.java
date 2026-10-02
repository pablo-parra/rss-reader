package com.pparra.rssreader.fetch;

import java.time.Instant;

public record FeedItem(String url, String title, Instant publishedAt) {
}
