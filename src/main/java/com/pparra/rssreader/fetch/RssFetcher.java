package com.pparra.rssreader.fetch;

import com.pparra.rssreader.domain.Source;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class RssFetcher {

    private final HttpFetcher http;

    public RssFetcher(HttpFetcher http) {
        this.http = http;
    }

    public List<FeedItem> fetch(Source source) throws IOException {
        String feedUrl = source.getFeedUrl() != null ? source.getFeedUrl() : source.getUrl();
        FetchedPage page = http.get(feedUrl);
        if (ChallengeDetector.isChallenge(page)) {
            throw new SourceBlockedException(feedUrl);
        }
        if (!page.isSuccess()) {
            throw new IOException("HTTP " + page.status() + " for " + feedUrl);
        }

        SyndFeed feed = parse(page.body(), feedUrl);
        List<FeedItem> items = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            String link = resolve(page.finalUrl(), entry.getLink());
            if (link == null) {
                continue;
            }
            String title = entry.getTitle() == null || entry.getTitle().isBlank() ? link : entry.getTitle().trim();
            items.add(new FeedItem(link, title, toInstant(entry.getPublishedDate(), entry.getUpdatedDate())));
        }
        return items;
    }

    private static SyndFeed parse(String body, String feedUrl) throws IOException {
        SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(false);
        try {
            return input.build(new StringReader(body.replace("﻿", "").stripLeading()));
        } catch (FeedException | IllegalArgumentException e) {
            throw new IOException("Not a valid feed: " + feedUrl, e);
        }
    }

    private static String resolve(String base, String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(base).resolve(link.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https") ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant toInstant(Date published, Date updated) {
        Date date = published != null ? published : updated;
        return date == null ? null : date.toInstant();
    }
}
