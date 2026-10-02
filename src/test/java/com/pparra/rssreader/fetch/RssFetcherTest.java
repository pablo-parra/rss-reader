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

    private static final String RSS_WITH_FULL_CONTENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Ana</title>
              <item><title>Full</title><link>https://a.com/full</link>
                <description>Short excerpt only</description>
                <content:encoded><![CDATA[<p>Full body paragraph.</p><img src="/i.jpg">]]></content:encoded></item>
              <item><title>Excerpt</title><link>https://a.com/excerpt</link><description>Just a teaser</description></item>
              <item><title>Blank</title><link>https://a.com/blank</link><content:encoded>   </content:encoded></item>
            </channel></rss>""";

    private static final String ATOM_WITH_CONTENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Ana</title>
              <entry><title>Atom full</title><link href="https://a.com/atom-full"/><updated>2026-02-01T08:00:00Z</updated>
                <summary>Teaser</summary><content type="html">&lt;p&gt;Atom body paragraph.&lt;/p&gt;</content></entry>
            </feed>""";

    private static final String RSS_WITH_IMAGES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Ana</title>
              <item><title>Thumb</title><link>https://a.com/thumb</link><media:thumbnail url="https://a.com/media-thumb.jpg"/></item>
              <item><title>Enclosure</title><link>https://a.com/enc</link><enclosure url="https://a.com/enclosure.jpg" type="image/jpeg" length="1"/></item>
              <item><title>Audio</title><link>https://a.com/audio</link><enclosure url="https://a.com/episode.mp3" type="audio/mpeg" length="1"/></item>
              <item><title>Inline</title><link>https://a.com/inline</link><content:encoded><![CDATA[<p>text</p><img src="/inline.jpg" width="800">]]></content:encoded></item>
              <item><title>Desc</title><link>https://a.com/desc</link><description><![CDATA[<p><img src="https://a.com/desc.jpg" width="600"> teaser</p>]]></description></item>
              <item><title>None</title><link>https://a.com/none</link><description>just text</description></item>
            </channel></rss>""";

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

    @Test
    void rssContentEncodedIsCarriedAsTheFullArticleContent() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS_WITH_FULL_CONTENT, "https://a.com/feed"));

        var items = fetcher.fetch(source("https://a.com/feed", null));

        assertThat(items.get(0).contentHtml()).contains("Full body paragraph.").contains("<img");
    }

    @Test
    void itemsWithOnlyAnExcerptOrBlankContentCarryNoFullContent() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS_WITH_FULL_CONTENT, "https://a.com/feed"));

        var items = fetcher.fetch(source("https://a.com/feed", null));

        assertThat(items.get(1).contentHtml()).isNull();
        assertThat(items.get(2).contentHtml()).isNull();
    }

    @Test
    void atomContentIsCarriedAsTheFullArticleContent() throws IOException {
        when(http.get("https://a.com/atom.xml")).thenReturn(Pages.ok("application/atom+xml", ATOM_WITH_CONTENT, "https://a.com/atom.xml"));

        var items = fetcher.fetch(source("https://a.com/atom.xml", null));

        assertThat(items).singleElement().satisfies(item -> assertThat(item.contentHtml()).contains("Atom body paragraph."));
    }

    @Test
    void feedsWithoutAnyContentFieldStillParse() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS, "https://a.com/feed"));

        assertThat(fetcher.fetch(source("https://a.com/feed", null))).allSatisfy(item -> assertThat(item.contentHtml()).isNull());
    }

    @Test
    void entryImageComesFromMediaThumbnailEnclosureOrImagesInTheEntryHtml() throws IOException {
        when(http.get("https://a.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS_WITH_IMAGES, "https://a.com/feed"));

        var items = fetcher.fetch(source("https://a.com/feed", null));

        assertThat(items).extracting(FeedItem::imageUrl).containsExactly(
                "https://a.com/media-thumb.jpg", "https://a.com/enclosure.jpg", null,
                "https://a.com/inline.jpg", "https://a.com/desc.jpg", null);
    }

    @Test
    void aFullSizeImageInTheEntryHtmlIsPreferredOverSmallMediaImages() throws IOException {
        String feed = """
                <?xml version="1.0"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Ana</title>
                  <item><title>Both</title><link>https://a.com/both</link><media:thumbnail url="https://a.com/small-150x150.jpg"/><media:content medium="image" url="https://a.com/small-content-150x150.jpg" width="150" height="150"/>
                    <content:encoded><![CDATA[<p>text</p><img src="https://a.com/full.jpg" width="1200">]]></content:encoded></item>
                </channel></rss>""";
        when(http.get("https://a.com/feed")).thenReturn(Pages.ok("application/rss+xml", feed, "https://a.com/feed"));

        assertThat(fetcher.fetch(source("https://a.com/feed", null))).singleElement()
                .satisfies(item -> assertThat(item.imageUrl()).isEqualTo("https://a.com/full.jpg"));
    }
}
