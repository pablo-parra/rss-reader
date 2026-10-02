package com.pparra.rssreader.fetch;

import com.pparra.rssreader.domain.Source;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
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
import org.jdom2.Element;
import org.springframework.stereotype.Component;

@Component
public class RssFetcher {

    private static final String MEDIA_NAMESPACE = "http://search.yahoo.com/mrss/";
    private static final String CONTENT_NAMESPACE = "http://purl.org/rss/1.0/modules/content/";

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
            items.add(new FeedItem(link, title, toInstant(entry.getPublishedDate(), entry.getUpdatedDate()), fullContent(entry), imageOf(entry, link)));
        }
        return items;
    }

    /** Full article HTML from RSS content:encoded or Atom content; excerpts (description/summary) are ignored. */
    private static String fullContent(SyndEntry entry) {
        for (Element markup : entry.getForeignMarkup()) {
            if ("encoded".equals(markup.getName()) && CONTENT_NAMESPACE.equals(markup.getNamespaceURI())
                    && !markup.getText().isBlank()) {
                return markup.getText();
            }
        }
        for (SyndContent content : entry.getContents()) {
            if (content.getValue() != null && !content.getValue().isBlank()) {
                return content.getValue();
            }
        }
        return null;
    }

    /**
     * Entry image, best first: the first image of the entry HTML (full size), an image enclosure, media:content, and
     * last media:thumbnail. WordPress feeds fill the media tags with a small 150x150 crop, so they rank low.
     */
    private static String imageOf(SyndEntry entry, String link) {
        String mediaContent = null;
        String thumbnail = null;
        for (Element markup : entry.getForeignMarkup()) {
            String url = MEDIA_NAMESPACE.equals(markup.getNamespaceURI()) ? markup.getAttributeValue("url") : null;
            if (url == null || url.isBlank()) {
                continue;
            }
            if (markup.getName().equals("thumbnail") && thumbnail == null) {
                thumbnail = resolve(link, url);
            } else if (markup.getName().equals("content") && mediaContent == null
                    && isImage(markup.getAttributeValue("medium"), markup.getAttributeValue("type"))) {
                mediaContent = resolve(link, url);
            }
        }
        String fromHtml = ContentExtractor.firstImageUrl(fullContent(entry), link);
        if (fromHtml == null && entry.getDescription() != null) {
            fromHtml = ContentExtractor.firstImageUrl(entry.getDescription().getValue(), link);
        }
        if (fromHtml != null) {
            return fromHtml;
        }
        for (SyndEnclosure enclosure : entry.getEnclosures()) {
            if (isImage(null, enclosure.getType()) && enclosure.getUrl() != null) {
                return resolve(link, enclosure.getUrl());
            }
        }
        return mediaContent != null ? mediaContent : thumbnail;
    }

    private static boolean isImage(String medium, String type) {
        return "image".equals(medium) || (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/"));
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
