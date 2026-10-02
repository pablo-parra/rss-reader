package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ArticleContentFetcherTest {

    private static final String URL = "https://a.com/post";

    private final HttpFetcher http = mock(HttpFetcher.class);
    private final ArticleContentFetcher fetcher = new ArticleContentFetcher(http, new ContentExtractor(), 600);

    @Test
    void returnsContentOfReadablePage() throws IOException {
        when(http.get(URL)).thenReturn(Pages.html(Pages.articleHtml("A readable full paragraph of text.", 40), URL));

        assertThat(fetcher.fetchReadable(URL).html()).hasValueSatisfying(html -> assertThat(html).contains("readable full"));
    }

    @Test
    void blockedStatusYieldsEmpty() throws IOException {
        when(http.get(URL)).thenReturn(Pages.status(402, "Payment required", URL));

        assertThat(fetcher.fetchReadable(URL).html()).isEmpty();
    }

    @Test
    void truncatedTeaserWithPaywallMarkerYieldsEmpty() throws IOException {
        String teaser = "<html><body><article><p>Teaser text only. Subscribe to continue reading. "
                + "x".repeat(150) + "</p></article></body></html>";
        when(http.get(URL)).thenReturn(Pages.html(teaser, URL));

        assertThat(fetcher.fetchReadable(URL).html()).isEmpty();
    }

    @Test
    void shortPageWithoutMarkersIsAccepted() throws IOException {
        String note = "<html><body><article><p>" + "A short but complete note. ".repeat(10) + "</p></article></body></html>";
        when(http.get(URL)).thenReturn(Pages.html(note, URL));

        assertThat(fetcher.fetchReadable(URL).html()).isPresent();
    }

    @Test
    void emptyShellYieldsEmpty() throws IOException {
        when(http.get(URL)).thenReturn(Pages.html("<html><body><div id=\"app\"></div></body></html>", URL));

        assertThat(fetcher.fetchReadable(URL).html()).isEmpty();
    }

    @Test
    void ioFailureYieldsEmpty() throws IOException {
        when(http.get(anyString())).thenThrow(new IOException("boom"));

        assertThat(fetcher.fetchReadable(URL).html()).isEmpty();
    }
}
