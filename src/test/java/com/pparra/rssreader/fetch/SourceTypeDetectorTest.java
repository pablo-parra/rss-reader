package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.SourceTypeDetector.Detection;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceTypeDetectorTest {

    private final HttpFetcher http = mock(HttpFetcher.class);
    private final SourceTypeDetector detector = new SourceTypeDetector(http);

    @Test
    void directFeedIsRssWithoutFeedUrl() throws IOException {
        when(http.get("https://a.com/feed"))
                .thenReturn(Pages.ok("application/rss+xml", "<?xml version=\"1.0\"?><rss version=\"2.0\"></rss>", "https://a.com/feed"));

        assertThat(detector.detect("https://a.com/feed")).isEqualTo(new Detection(SourceType.RSS, null));
    }

    @Test
    void xmlServedAsTextXmlIsStillAFeed() throws IOException {
        when(http.get("https://a.com/atom"))
                .thenReturn(Pages.ok("text/xml", "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>", "https://a.com/atom"));

        assertThat(detector.detect("https://a.com/atom").type()).isEqualTo(SourceType.RSS);
    }

    @Test
    void htmlWithAlternateLinkResolvesAbsoluteFeedUrl() throws IOException {
        String body = "<html><head><link rel=\"alternate\" type=\"application/atom+xml\" href=\"/feed.xml\"></head></html>";
        when(http.get("https://a.com/blog")).thenReturn(Pages.html(body, "https://a.com/blog"));

        assertThat(detector.detect("https://a.com/blog"))
                .isEqualTo(new Detection(SourceType.RSS, "https://a.com/feed.xml"));
    }

    @Test
    void plainHtmlIsScrape() throws IOException {
        when(http.get("https://a.com")).thenReturn(Pages.html("<html><body><p>hi</p></body></html>", "https://a.com"));

        assertThat(detector.detect("https://a.com").type()).isEqualTo(SourceType.SCRAPE);
    }

    @Test
    void cloudflareChallengeIsUnsupported() throws IOException {
        when(http.get("https://a.com"))
                .thenReturn(new FetchedPage(403, "text/html", "<title>Just a moment...</title>", "https://a.com",
                        Map.of("cf-mitigated", "challenge")));

        assertThat(detector.detect("https://a.com").type()).isEqualTo(SourceType.UNSUPPORTED);
    }

    @Test
    void unreachableIsUnsupported() throws IOException {
        when(http.get(anyString())).thenThrow(new IOException("timeout"));

        assertThat(detector.detect("https://a.com").type()).isEqualTo(SourceType.UNSUPPORTED);
    }
}
