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
import com.pparra.rssreader.repository.ArticleRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ArticleContentServiceTest {

    private final ArticleService articleService = mock(ArticleService.class);
    private final ArticleRepository repository = mock(ArticleRepository.class);
    private final ArticleContentFetcher contentFetcher = mock(ArticleContentFetcher.class);
    private final ArchiveClient archiveClient = mock(ArchiveClient.class);
    private final ArticleContentService service =
            new ArticleContentService(articleService, repository, contentFetcher, archiveClient);

    private Article article;

    @BeforeEach
    void setUp() {
        article = new Article(1L, "https://news.com/a", "Title", null, Instant.now());
        when(articleService.get(10L)).thenReturn(article);
        when(archiveClient.lookupUrl(anyString())).thenReturn("https://archive.ph/newest/https://news.com/a");
    }

    @Test
    void usesOriginalWhenReadable() {
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(Optional.of("<p>original</p>"));

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.ORIGINAL);
        assertThat(content.html()).isEqualTo("<p>original</p>");
        verify(archiveClient, never()).fetchSnapshot(anyString());
        verify(repository).save(article);
    }

    @Test
    void fallsBackToArchiveWhenOriginalBlocked() {
        when(contentFetcher.fetchReadable(anyString())).thenReturn(Optional.empty());
        when(archiveClient.fetchSnapshot("https://news.com/a")).thenReturn(Optional.of("<p>snapshot</p>"));

        ArticleContent content = service.load(10L);

        assertThat(content.origin()).isEqualTo(ContentOrigin.ARCHIVE);
        assertThat(content.html()).isEqualTo("<p>snapshot</p>");
    }

    @Test
    void marksUnavailableWhenBothFail() {
        when(contentFetcher.fetchReadable(anyString())).thenReturn(Optional.empty());
        when(archiveClient.fetchSnapshot(anyString())).thenReturn(Optional.empty());

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
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(Optional.of("<p>clean</p>"));

        ArticleContent content = service.load(10L);

        assertThat(content.html()).isEqualTo("<p>clean</p>");
        assertThat(article.getContentVersion()).isEqualTo(ArticleContentService.CONTENT_VERSION);
    }

    @Test
    void contentWithoutAVersionIsRefetched() {
        ReflectionTestUtils.setField(article, "contentOrigin", ContentOrigin.ORIGINAL);
        ReflectionTestUtils.setField(article, "contentHtml", "<p>legacy</p>");
        when(contentFetcher.fetchReadable("https://news.com/a")).thenReturn(Optional.of("<p>fresh</p>"));

        assertThat(service.load(10L).html()).isEqualTo("<p>fresh</p>");
    }

    @Test
    void retryReRunsOnlyWhenUnavailable() {
        article.cacheContent(null, ContentOrigin.UNAVAILABLE, Instant.now(), ArticleContentService.CONTENT_VERSION);
        when(contentFetcher.fetchReadable(anyString())).thenReturn(Optional.of("<p>now ok</p>"));

        assertThat(service.retry(10L).origin()).isEqualTo(ContentOrigin.ORIGINAL);

        verify(contentFetcher).fetchReadable("https://news.com/a");
        service.retry(10L);
        verify(contentFetcher).fetchReadable("https://news.com/a");
    }
}
