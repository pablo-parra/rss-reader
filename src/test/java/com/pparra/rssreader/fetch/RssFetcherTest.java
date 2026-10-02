package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RssFetcherTest {

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Ana</title>
              <item><title>First post</title><link>https://a.com/p1</link><pubDate>Mon, 05 Jan 2026 10:00:00 GMT</pubDate></item>
              <item><title>Relative</title><link>/p2</link></item>
              <item><title>No link</title></item>
              <item><title>Mail</title><link>mailto:x@a.com</link></item>
              <item><link>https://a.com/p3</link></item>
            </channel></rss>""";

    private static final String ATOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Ana</title>
              <entry><title>Atom post</title><link href="https://a.com/atom1"/><updated>2026-02-01T08:00:00Z</updated></entry>
            </feed>""";

    private final HttpFetcher http = mock(HttpFetcher.class);
    private final RssFetcher fetcher = new RssFetcher(http);

    private static Source source(String url, String feedUrl) {
        return new Source(url, "Ana", SourceType.RSS, feedUrl, Instant.now());
    }

    @Test
    void parsesRssSkippingEntriesWithoutWebLink() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS, "https://a.com/feed"));

        var items = fetcher.fetch(source("https://a.com/feed", null));

        assertThat(items).extracting(FeedItem::url)
                .containsExactly("https://a.com/p1", "https://a.com/p2", "https://a.com/p3");
        assertThat(items.get(0).title()).isEqualTo("First post");
        assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-01-05T10:00:00Z"));
        assertThat(items.get(2).title()).isEqualTo("https://a.com/p3");
    }

    @Test
    void parsesAtomUsingDiscoveredFeedUrl() throws IOException {
        when(http.get("https://a.com/atom.xml")).thenReturn(Pages.ok("application/atom+xml", ATOM, "https://a.com/atom.xml"));

        var items = fetcher.fetch(source("https://a.com/blog", "https://a.com/atom.xml"));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.url()).isEqualTo("https://a.com/atom1");
            assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-02-01T08:00:00Z"));
        });
    }

    @Test
    void rejectsDoctypeDeclarations() throws IOException {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss version=\"2.0\"><channel><title>&x;</title></channel></rss>";
        when(http.get("https://a.com/feed")).thenReturn(Pages.ok("application/rss+xml", xxe, "https://a.com/feed"));

        assertThatThrownBy(() -> fetcher.fetch(source("https://a.com/feed", null))).isInstanceOf(IOException.class);
    }

    @Test
    void invalidFeedBodyIsAnIoError() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(Pages.html("<html>not a feed</html>", "https://a.com/feed"));

        assertThatThrownBy(() -> fetcher.fetch(source("https://a.com/feed", null)))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(SourceBlockedException.class);
    }

    @Test
    void challengeIsReportedAsBlocked() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(
                new FetchedPage(403, "text/html", "x", "https://a.com/feed", Map.of("cf-mitigated", "challenge")));

        assertThatThrownBy(() -> fetcher.fetch(source("https://a.com/feed", null)))
                .isInstanceOf(SourceBlockedException.class);
    }

    @Test
    void serverErrorIsAnIoError() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(Pages.status(500, "oops", "https://a.com/feed"));

        assertThatThrownBy(() -> fetcher.fetch(source("https://a.com/feed", null)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500");
    }
}
