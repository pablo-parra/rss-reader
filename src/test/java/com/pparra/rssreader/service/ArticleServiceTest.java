package com.pparra.rssreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArticleServiceTest {

    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final SourceRepository sources = mock(SourceRepository.class);
    private final ArticleService service = new ArticleService(articles, sources, 7);

    private static <T> T withId(T entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Source source(long id, String name) {
        return withId(new Source("https://" + name + ".com", name, SourceType.RSS, null, Instant.now()), id);
    }

    private static Article article(long id, long sourceId, String title, String published, String fetched) {
        Instant publishedAt = published == null ? null : Instant.parse(published);
        return withId(new Article(sourceId, "https://x.com/" + id, title, publishedAt, Instant.parse(fetched)), id);
    }

    @Test
    void groupsByAuthorWithNewestArticleFirst() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.findVisibleOnDashboard(any())).thenReturn(List.of(
                article(1, 1, "old", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(2, 1, "new", "2026-01-05T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(3, 1, "mid", "2026-01-03T00:00:00Z", "2026-02-01T00:00:00Z")));

        List<ArticleGroup> groups = service.dashboardGroups();

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.source().getName()).isEqualTo("ana");
            assertThat(group.articles()).extracting(Article::getTitle).containsExactly("new", "mid", "old");
        });
    }

    @Test
    void usesFetchedAtWhenPublishedAtIsMissing() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.findVisibleOnDashboard(any())).thenReturn(List.of(
                article(1, 1, "dated", "2026-01-10T00:00:00Z", "2026-03-01T00:00:00Z"),
                article(2, 1, "undated-but-fetched-later", null, "2026-01-20T00:00:00Z")));

        assertThat(service.dashboardGroups().get(0).articles())
                .extracting(Article::getTitle).containsExactly("undated-but-fetched-later", "dated");
    }

    @Test
    void ordersGroupsByTheirMostRecentArticle() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana"), source(2, "bea"), source(3, "carl")));
        when(articles.findVisibleOnDashboard(any())).thenReturn(List.of(
                article(1, 1, "ana-1", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(2, 2, "bea-1", "2026-01-09T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(3, 3, "carl-1", "2026-01-05T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(4, 1, "ana-2", "2026-01-02T00:00:00Z", "2026-02-01T00:00:00Z")));

        assertThat(service.dashboardGroups()).extracting(group -> group.source().getName())
                .containsExactly("bea", "carl", "ana");
    }

    @Test
    void ignoresArticlesWhoseSourceNoLongerExists() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.findVisibleOnDashboard(any())).thenReturn(List.of(
                article(1, 1, "kept", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(2, 99, "orphan", "2026-01-02T00:00:00Z", "2026-02-01T00:00:00Z")));

        assertThat(service.dashboardGroups()).singleElement()
                .satisfies(group -> assertThat(group.articles()).extracting(Article::getTitle).containsExactly("kept"));
    }

    @Test
    void noUnreadArticlesGivesNoGroups() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.findVisibleOnDashboard(any())).thenReturn(List.of());

        assertThat(service.dashboardGroups()).isEmpty();
    }

    @Test
    void readArticlesStayVisibleAndAreCountedSeparately() {
        Article unread = article(1, 1, "unread", "2026-01-02T00:00:00Z", "2026-02-01T00:00:00Z");
        Article read = article(2, 1, "read", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        read.markRead(Instant.now());
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.findVisibleOnDashboard(any())).thenReturn(List.of(read, unread));

        ArticleGroup group = service.dashboardGroups().get(0);

        assertThat(group.articles()).extracting(Article::getTitle).containsExactly("unread", "read");
        assertThat(group.unreadCount()).isEqualTo(1);
    }

    @Test
    void readArticlesAreOnlyRequestedWithinTheVisibilityWindow() {
        when(sources.findAll()).thenReturn(List.of());
        when(articles.findVisibleOnDashboard(any())).thenReturn(List.of());
        Instant before = Instant.now();

        service.dashboardGroups();

        org.mockito.ArgumentCaptor<Instant> since = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(articles).findVisibleOnDashboard(since.capture());
        assertThat(since.getValue()).isBetween(before.minusSeconds(7 * 86_400L + 5), Instant.now().minusSeconds(7 * 86_400L - 5));
    }

    @Test
    void markReadRecordsWhenTheArticleWasFirstRead() {
        Article article = article(1, 1, "a", null, "2026-02-01T00:00:00Z");
        Instant first = Instant.parse("2026-03-01T00:00:00Z");

        article.markRead(first);
        article.markRead(first.plusSeconds(3600));

        assertThat(article.isRead()).isTrue();
        assertThat(article.getReadAt()).isEqualTo(first);
    }

    @Test
    void silentlyReadArticlesHaveNoReadTimestamp() {
        Article article = article(1, 1, "a", null, "2026-02-01T00:00:00Z");

        article.markReadSilently();

        assertThat(article.isRead()).isTrue();
        assertThat(article.getReadAt()).isNull();
    }

    @Test
    void markUnreadClearsTheReadStateAndTheReadTimestamp() {
        Article article = article(1, 1, "a", null, "2026-02-01T00:00:00Z");
        article.markRead(Instant.now());
        when(articles.findById(1L)).thenReturn(java.util.Optional.of(article));

        service.markUnread(1L);

        assertThat(article.isRead()).isFalse();
        assertThat(article.getReadAt()).isNull();
    }

    @Test
    void markUnreadOnAnUnreadArticleChangesNothing() {
        Article article = article(1, 1, "a", null, "2026-02-01T00:00:00Z");
        when(articles.findById(1L)).thenReturn(java.util.Optional.of(article));

        service.markUnread(1L);

        assertThat(article.isRead()).isFalse();
        assertThat(article.getReadAt()).isNull();
    }

    @Test
    void anArticleMarkedUnreadAndReadAgainGetsAFreshReadTimestamp() {
        Article article = article(1, 1, "a", null, "2026-02-01T00:00:00Z");
        Instant first = Instant.parse("2026-03-01T00:00:00Z");
        Instant second = Instant.parse("2026-03-09T00:00:00Z");

        article.markRead(first);
        article.markUnread();
        article.markRead(second);

        assertThat(article.getReadAt()).isEqualTo(second);
    }
}
