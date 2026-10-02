package com.pparra.rssreader.fetch;

import com.pparra.rssreader.domain.SourceType;
import java.io.IOException;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class SourceTypeDetector {

    public record Detection(SourceType type, String feedUrl) {
    }

    private static final Detection UNSUPPORTED = new Detection(SourceType.UNSUPPORTED, null);
    private static final String FEED_LINK_SELECTOR =
            "link[rel~=(?i)alternate][type~=(?i)application/(rss|atom)\\+xml][href]";

    private final HttpFetcher http;

    public SourceTypeDetector(HttpFetcher http) {
        this.http = http;
    }

    public Detection detect(String url) {
        FetchedPage page;
        try {
            page = http.get(url);
        } catch (IOException e) {
            return UNSUPPORTED;
        }
        if (!page.isSuccess() || ChallengeDetector.isChallenge(page)) {
            return UNSUPPORTED;
        }
        if (looksLikeFeed(page)) {
            return new Detection(SourceType.RSS, null);
        }
        Document doc = Jsoup.parse(page.body(), page.finalUrl());
        Element feedLink = doc.selectFirst(FEED_LINK_SELECTOR);
        if (feedLink != null) {
            String feedUrl = feedLink.absUrl("href");
            if (!feedUrl.isBlank()) {
                return new Detection(SourceType.RSS, feedUrl);
            }
        }
        return new Detection(SourceType.SCRAPE, null);
    }

    private static boolean looksLikeFeed(FetchedPage page) {
        String contentType = page.contentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("rss") || contentType.contains("atom")) {
            return true;
        }
        String head = page.body().substring(0, Math.min(page.body().length(), 1000)).toLowerCase(Locale.ROOT);
        boolean xmlLike = contentType.contains("xml") || head.stripLeading().startsWith("<?xml");
        return xmlLike && (head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf"));
    }
}
