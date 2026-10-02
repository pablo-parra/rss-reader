package com.pparra.rssreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.ContentOrigin;
import com.pparra.rssreader.fetch.ArchiveClient;
import com.pparra.rssreader.fetch.ArticleContentFetcher;
import com.pparra.rssreader.fetch.ContentAttempt;
import com.pparra.rssreader.fetch.ContentExtractor;
import com.pparra.rssreader.repository.ArticleRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ArticleContentServiceTest {

    private final ArticleService articleService = mock(ArticleService.class);
    private final ArticleRepository repository = mock(ArticleRepository.class);
    private final ArticleContentFetcher contentFetcher = mock(ArticleContentFetcher.class);
    private final ArchiveClient archiveClient = mock(ArchiveClient.class);
    private final ArticleContentService service =
            new ArticleContentService(articleService, repository, contentFetcher, archiveClient, new ContentExtractor(), 600);

    private Article article;

    @BeforeEach
    void setUp() {
        article = new Article(1L, "https://news.com/a", "Title", null, Instant.now());
        when(articleService.get(10L)).thenReturn(article);
        when(archiveClient.lookupUrl(anyString())).thenReturn("https://archive.ph/newest/https://news.com/a");
    }

    @Test
    void usesOriginalWhenReadable() {
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(ContentAttempt.success("<p>original</p>"));

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.ORIGINAL);
        assertThat(content.html()).isEqualTo("<p>original</p>");
        verify(archiveClient, never()).fetchSnapshot(anyString());
        verify(repository).save(article);
    }

    @Test
    void fallsBackToArchiveWhenOriginalBlocked() {
        when(contentFetcher.fetchReadable(anyString())).thenReturn(ContentAttempt.failed("reason"));
        when(archiveClient.fetchSnapshot("https://news.com/a")).thenReturn(ContentAttempt.success("<p>snapshot</p>"));

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.ARCHIVE);
        assertThat(content.html()).isEqualTo("<p>snapshot</p>");
    }

    @Test
    void marksUnavailableWhenBothFail() {
        when(contentFetcher.fetchReadable(anyString())).thenReturn(ContentAttempt.failed("reason"));
        when(archiveClient.fetchSnapshot(anyString())).thenReturn(ContentAttempt.failed("reason"));

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.UNAVAILABLE);
        assertThat(content.html()).isNull();
        assertThat(content.archiveLookupUrl()).contains("archive.ph/newest/");
    }

    @Test
    void cachedContentIsNotRefetched() {
        article.cacheContent("<p>cached</p>", ContentOrigin.ORIGINAL, Instant.now(), ArticleContentService.CONTENT_VERSION);

        ArticleContent content = service.load(10L);

        assertThat(content.html()).isEqualTo("<p>cached</p>");
        verify(contentFetcher, never()).fetchReadable(anyString());
    }

    @Test
    void contentCachedByAnOlderExtractorIsRefetched() {
        article.cacheContent("<p>old noisy content</p>", ContentOrigin.ORIGINAL, Instant.now(), ArticleContentService.CONTENT_VERSION - 1);
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(ContentAttempt.success("<p>clean</p>"));

        ArticleContent content = service.load(10L);

        assertThat(content.html()).isEqualTo("<p>clean</p>");
        assertThat(article.getContentVersion()).isEqualTo(ArticleContentService.CONTENT_VERSION);
    }

    @Test
    void contentWithoutAVersionIsRefetched() {
        ReflectionTestUtils.setField(article, "contentOrigin", ContentOrigin.ORIGINAL);
        ReflectionTestUtils.setField(article, "contentHtml", "<p>legacy</p>");
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(ContentAttempt.success("<p>fresh</p>"));

        assertThat(service.load(10L).html()).isEqualTo("<p>fresh</p>");
    }

    @Test
    void retryReRunsOnlyWhenUnavailable() {
        article.cacheContent(null, ContentOrigin.UNAVAILABLE, Instant.now(), ArticleContentService.CONTENT_VERSION);
        when(contentFetcher.fetchReadable(anyString())).thenReturn(ContentAttempt.success("<p>now ok</p>"));

        assertThat(service.retry(10L).origin()).isEqualTo(ContentOrigin.ORIGINAL);

        verify(contentFetcher).fetchReadable("https://news.com/a");
        service.retry(10L);
        verify(contentFetcher).fetchReadable("https://news.com/a");
    }

    private static final String LONG_FEED_BODY = "<p>" + "A full paragraph delivered inside the feed. ".repeat(30) + "</p><img src=\"/photo.jpg\">";

    @Test
    void usesTheFullContentFromTheFeedWithoutRequestingThePage() {
        article.attachFeedContent(LONG_FEED_BODY);

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.FEED);
        assertThat(content.html()).contains("full paragraph delivered inside the feed").contains("https://news.com/photo.jpg");
        verify(contentFetcher, never()).fetchReadable(anyString());
        verify(archiveClient, never()).fetchSnapshot(anyString());
        verify(repository).save(article);
    }

    @Test
    void aFeedExcerptIsNotEnoughSoThePageIsRequestedInstead() {
        article.attachFeedContent("<p>Just a short teaser of the post.</p>");
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(ContentAttempt.success("<p>page</p>"));

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.ORIGINAL);
        assertThat(content.html()).isEqualTo("<p>page</p>");
    }

    @Test
    void feedContentIsSanitizedBeforeItIsShown() {
        article.attachFeedContent("<p onclick=\"evil()\">" + "Body text. ".repeat(80) + "</p><script>alert(1)</script>");

        assertThat(service.load(10L).html()).doesNotContain("script").doesNotContain("onclick");
    }

    @Test
    void whenBothFeedExcerptAndPageFailTheArticleIsUnavailableWithAReason() {
        article.attachFeedContent("<p>teaser</p>");
        when(contentFetcher.fetchReadable(anyString())).thenReturn(ContentAttempt.failed("blocked by a bot challenge"));
        when(archiveClient.fetchSnapshot(anyString())).thenReturn(ContentAttempt.failed("no snapshot exists"));

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.UNAVAILABLE);
        assertThat(content.failureReason()).contains("blocked by a bot challenge").contains("no snapshot exists");
    }

    @Test
    void theFirstImageOfTheLoadedContentBecomesTheThumbnailWhenTheArticleHasNone() {
        when(contentFetcher.fetchReadable("https://news.com/a"))
                .thenReturn(ContentAttempt.success("<p>text</p><img src=\"https://news.com/hero.jpg\">"));

        service.load(10L);

        assertThat(article.getImageUrl()).isEqualTo("https://news.com/hero.jpg");
    }

    @Test
    void anExistingThumbnailIsNotReplacedByTheContentImage() {
        article.useImageIfMissing("https://news.com/from-feed.jpg");
        when(contentFetcher.fetchReadable("https://news.com/a"))
                .thenReturn(ContentAttempt.success("<img src=\"https://news.com/hero.jpg\">"));

        service.load(10L);

        assertThat(article.getImageUrl()).isEqualTo("https://news.com/from-feed.jpg");
    }

    @Test
    void contentWithoutImagesLeavesTheThumbnailEmpty() {
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(ContentAttempt.success("<p>text only</p>"));

        service.load(10L);

        assertThat(article.getImageUrl()).isNull();
    }
}
