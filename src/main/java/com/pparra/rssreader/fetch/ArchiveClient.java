package com.pparra.rssreader.fetch;

import java.io.IOException;
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

    public ContentAttempt fetchSnapshot(String originalUrl) {
        FetchedPage page;
        try {
            page = http.get(lookupUrl(originalUrl));
        } catch (IOException e) {
            return ContentAttempt.failed("request failed (" + e.getMessage() + ")");
        }
        if (ChallengeDetector.isChallenge(page)) {
            return ContentAttempt.failed("archive.ph showed a check (HTTP " + page.status() + ")");
        }
        if (page.finalUrl().startsWith(baseUrl + "/newest/")) {
            return ContentAttempt.failed("no snapshot exists (HTTP " + page.status() + ")");
        }
        if (!page.isSuccess()) {
            return ContentAttempt.failed("HTTP " + page.status());
        }
        ExtractedContent content = extractor.extract(page.body(), page.finalUrl());
        if (content.textLength() < minTextLength) {
            return ContentAttempt.failed("snapshot too short (" + content.textLength() + " characters)");
        }
        return ContentAttempt.success(content.html());
    }
}
