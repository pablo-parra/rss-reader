package com.pparra.rssreader.fetch;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ArticleContentFetcher {

    private static final int MIN_USABLE_TEXT = 100;

    private final HttpFetcher http;
    private final ContentExtractor extractor;
    private final int minTextLength;

    public ArticleContentFetcher(
            HttpFetcher http, ContentExtractor extractor, @Value("${app.content.min-text-length}") int minTextLength) {
        this.http = http;
        this.extractor = extractor;
        this.minTextLength = minTextLength;
    }

    public ContentAttempt fetchReadable(String url) {
        FetchedPage page;
        try {
            page = http.get(url);
        } catch (IOException e) {
            return ContentAttempt.failed("request failed (" + e.getMessage() + ")");
        }
        if (ChallengeDetector.isChallenge(page)) {
            return ContentAttempt.failed("blocked by a bot challenge such as Cloudflare (HTTP " + page.status() + ")");
        }
        if (!page.isSuccess()) {
            return ContentAttempt.failed("HTTP " + page.status());
        }
        ExtractedContent content = extractor.extract(page.body(), page.finalUrl());
        if (content.declaredPaywalled()) {
            return ContentAttempt.failed("the page declares a paywall");
        }
        if (content.textLength() < MIN_USABLE_TEXT) {
            return ContentAttempt.failed("no readable article text found (" + content.textLength() + " characters)");
        }
        if (content.textLength() < minTextLength && content.paywallMarkers()) {
            return ContentAttempt.failed("paywall detected, only a teaser is visible (" + content.textLength() + " characters)");
        }
        return ContentAttempt.success(content.html());
    }
}
