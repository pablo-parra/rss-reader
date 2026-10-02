package com.pparra.rssreader.fetch;

import java.io.IOException;
import java.util.Optional;
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

    public Optional<String> fetchReadable(String url) {
        FetchedPage page;
        try {
            page = http.get(url);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (!page.isSuccess() || ChallengeDetector.isChallenge(page)) {
            return Optional.empty();
        }
        ExtractedContent content = extractor.extract(page.body(), page.finalUrl());
        boolean blocked = content.declaredPaywalled()
                || content.textLength() < MIN_USABLE_TEXT
                || (content.textLength() < minTextLength && content.paywallMarkers());
        return blocked ? Optional.empty() : Optional.of(content.html());
    }
}
