package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchiveClientTest {

    private static final String ORIGINAL = "https://news.com/story?id=1#top";
    private static final String LOOKUP = "https://archive.ph/newest/https://news.com/story?id=1";

    private final HttpFetcher http = mock(HttpFetcher.class);
    private final ArchiveClient client = new ArchiveClient(http, new ContentExtractor(), "https://archive.ph/", 600);

    @Test
    void lookupUrlAppendsRawOriginalWithoutFragment() {
        assertThat(client.lookupUrl(ORIGINAL)).isEqualTo(LOOKUP);
    }

    @Test
    void returnsSnapshotContentAfterRedirect() throws IOException {
        when(http.get(LOOKUP)).thenReturn(Pages.html(Pages.articleHtml("Full archived paragraph text.", 40), "https://archive.ph/AbCdE"));

        assertThat(client.fetchSnapshot(ORIGINAL).html()).hasValueSatisfying(html -> assertThat(html).contains("archived paragraph"));
    }

    @Test
    void noSnapshotWhenStillOnLookupUrl() throws IOException {
        when(http.get(LOOKUP)).thenReturn(Pages.status(404, "No results", LOOKUP));

        assertThat(client.fetchSnapshot(ORIGINAL).html()).isEmpty();
    }

    @Test
    void captchaCountsAsFailure() throws IOException {
        when(http.get(LOOKUP)).thenReturn(Pages.html("<html><div class=\"g-recaptcha\"></div></html>", "https://archive.ph/submit/"));

        assertThat(client.fetchSnapshot(ORIGINAL).html()).isEmpty();
    }

    @Test
    void cloudflareChallengeCountsAsFailure() throws IOException {
        when(http.get(LOOKUP)).thenReturn(new FetchedPage(403, "text/html", "x", "https://archive.ph/", Map.of("cf-mitigated", "challenge")));

        assertThat(client.fetchSnapshot(ORIGINAL).html()).isEmpty();
    }

    @Test
    void tooShortSnapshotIsRejected() throws IOException {
        when(http.get(LOOKUP)).thenReturn(Pages.html("<html><body><article><p>tiny</p></article></body></html>", "https://archive.ph/AbCdE"));

        assertThat(client.fetchSnapshot(ORIGINAL).html()).isEmpty();
    }
}
