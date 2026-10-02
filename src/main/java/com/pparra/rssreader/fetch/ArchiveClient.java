package com.pparra.rssreader.fetch;

import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ArchiveClient {

    private final HttpFetcher http;
    private final ContentExtractor extractor;
    private final String baseUrl;
    private final int minTextLength;

    public ArchiveClient(
            HttpFetcher http,
            ContentExtractor extractor,
            @Value("${app.archive.base-url}") String baseUrl,
            @Value("${app.content.min-text-length}") int minTextLength) {
        this.http = http;
        this.extractor = extractor;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.minTextLength = minTextLength;
    }

    public String lookupUrl(String originalUrl) {
        return baseUrl + "/newest/" + originalUrl.replaceFirst("#.*$", "");
    }

    public Optional<String> fetchSnapshot(String originalUrl) {
        FetchedPage page;
        try {
            page = http.get(lookupUrl(originalUrl));
        } catch (IOException e) {
            return Optional.empty();
        }
        boolean noSnapshot = page.finalUrl().startsWith(baseUrl + "/newest/");
        if (!page.isSuccess() || noSnapshot || ChallengeDetector.isChallenge(page)) {
            return Optional.empty();
        }
        ExtractedContent content = extractor.extract(page.body(), page.finalUrl());
        return content.textLength() < minTextLength ? Optional.empty() : Optional.of(content.html());
    }
}
