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

class ScrapeFetcherTest {

    private static final String URL = "https://www.news.com/author/ana";

    private final HttpFetcher http = mock(HttpFetcher.class);
    private final ScrapeFetcher fetcher = new ScrapeFetcher(http);
    private final Source source = new Source(URL, "Ana", SourceType.SCRAPE, null, Instant.now());

    private void page(String body) throws IOException {
        when(http.get(URL)).thenReturn(Pages.html(body, URL));
    }

    @Test
    void extractsSameHostHeadlineLinksAndIgnoresNoise() throws IOException {
        page("""
                <html><body>
                <nav><h2><a href="/politics/section-front-page">Politics section front page</a></h2></nav>
                <article><time datetime="2026-03-10T09:30:00+01:00">x</time>
                  <h2><a href="/2026/03/first-long-article-title">First long article title</a></h2></article>
                <article><h3><a href="https://www.news.com/second-article-here#comments">Second article headline here</a></h3></article>
                <article><h2><a href="https://other.com/external-story-title">External story title here</a></h2></article>
                <article><h2><a href="/short">Short</a></h2></article>
                <article><h2><a href="/">Home page link text long</a></h2></article>
                <article><h2><a href="/2026/03/first-long-article-title">First long article title</a></h2></article>
                </body></html>""");

        var items = fetcher.fetch(source);

        assertThat(items).extracting(FeedItem::url).containsExactly(
                "https://www.news.com/2026/03/first-long-article-title",
                "https://www.news.com/second-article-here");
        assertThat(items.get(0).title()).isEqualTo("First long article title");
        assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-03-10T08:30:00Z"));
        assertThat(items.get(1).publishedAt()).isNull();
    }

    @Test
    void keepsHeadlineLinksInsideArticleHeadersButNotPageHeaders() throws IOException {
        page("""
                <html><body>
                <header><h1><a href="/site-title-page-link">The Site Title Page Link</a></h1></header>
                <article><header><h2><a href="/card-with-header-markup">Card with header markup</a></h2></header></article>
                <footer><h3><a href="/footer-promo-article-link">Footer promo article link</a></h3></footer>
                </body></html>""");

        assertThat(fetcher.fetch(source)).extracting(FeedItem::url)
                .containsExactly("https://www.news.com/card-with-header-markup");
    }

    @Test
    void treatsWwwAndBareHostAsSameSite() throws IOException {
        page("<html><body><article><h2><a href=\"https://news.com/a-story-on-the-bare-host\">A story on the bare host</a></h2></article></body></html>");

        assertThat(fetcher.fetch(source)).hasSize(1);
    }

    @Test
    void handlesAnchorsWrappingHeadings() throws IOException {
        page("<html><body><article><a href=\"/wrapped-card-article\"><h2>Wrapped card article title</h2></a></article></body></html>");

        assertThat(fetcher.fetch(source)).singleElement()
                .satisfies(item -> assertThat(item.url()).isEqualTo("https://www.news.com/wrapped-card-article"));
    }

    @Test
    void fallsBackToLongestLinkPerArticleWhenNoHeadings() throws IOException {
        page("""
                <html><body>
                <article><a href="/tag/x">tag</a><a href="/real-article-without-heading">A real article without any heading</a></article>
                </body></html>""");

        assertThat(fetcher.fetch(source)).singleElement()
                .satisfies(item -> assertThat(item.url()).isEqualTo("https://www.news.com/real-article-without-heading"));
    }

    @Test
    void parsesDateOnlyDatetime() throws IOException {
        page("<html><body><article><time datetime=\"2026-04-01\">x</time><h2><a href=\"/dated-article-page\">Dated article headline</a></h2></article></body></html>");

        assertThat(fetcher.fetch(source).get(0).publishedAt()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    }

    @Test
    void challengeIsReportedAsBlocked() throws IOException {
        when(http.get(URL)).thenReturn(new FetchedPage(403, "text/html", "<title>Just a moment...</title>", URL, Map.of()));

        assertThatThrownBy(() -> fetcher.fetch(source)).isInstanceOf(SourceBlockedException.class);
    }

    @Test
    void notFoundIsAnIoError() throws IOException {
        when(http.get(URL)).thenReturn(Pages.status(404, "nope", URL));

        assertThatThrownBy(() -> fetcher.fetch(source)).isInstanceOf(IOException.class).hasMessageContaining("HTTP 404");
    }
}
