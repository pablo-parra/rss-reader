package com.pparra.rssreader.fetch;

import java.time.Instant;

/**
 * @param contentHtml raw full-article HTML carried by the feed entry (content:encoded / Atom content), or null
 * @param imageUrl thumbnail found in the feed entry (media:thumbnail, image enclosure, first image of its HTML), or null
 */
public record FeedItem(String url, String title, Instant publishedAt, String contentHtml, String imageUrl) {

    public FeedItem(String url, String title, Instant publishedAt, String contentHtml) {
        this(url, title, publishedAt, contentHtml, null);
    }

    public FeedItem(String url, String title, Instant publishedAt) {
        this(url, title, publishedAt, null, null);
    }
}
