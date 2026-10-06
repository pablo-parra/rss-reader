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
    private final ArticleService service = new ArticleService(articles, sources, 10);

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

    private static ArticleRepository.SourceCounts counts(long sourceId, long unread) {
        return new ArticleRepository.SourceCounts() {
            @Override
            public Long getSourceId() {
                return sourceId;
            }

            @Override
            public long getUnread() {
                return unread;
            }
        };
    }

    private static Article read(Article article) {
        article.markRead(Instant.now());
        return article;
    }

    @Test
    void allListsTheUnreadArticlesOfEverySourceNewestFirst() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana"), source(2, "bea")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 2), counts(2, 1)));
        when(articles.findUnreadRows()).thenReturn(List.of(
                article(1, 1, "ana-old", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(2, 2, "bea-new", "2026-01-09T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(3, 1, "ana-mid", "2026-01-05T00:00:00Z", "2026-02-01T00:00:00Z")));

        Dashboard dashboard = service.dashboard(null);

        assertThat(dashboard.selected()).isNull();
        assertThat(dashboard.articles()).extracting(row -> row.article().getTitle())
                .containsExactly("bea-new", "ana-mid", "ana-old");
        assertThat(dashboard.articles()).extracting(row -> row.source().getName()).containsExactly("bea", "ana", "ana");
    }

    @Test
    void allBreaksTiesOnTheSamePublicationDateBySourceName() {
        when(sources.findAll()).thenReturn(List.of(source(1, "Zoe"), source(2, "ana"), source(3, "Bruno")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 1), counts(2, 1), counts(3, 1)));
        when(articles.findUnreadRows()).thenReturn(List.of(
                article(1, 1, "zoe", "2026-01-05T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(2, 2, "ana", "2026-01-05T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(3, 3, "bruno", "2026-01-05T00:00:00Z", "2026-02-01T00:00:00Z")));

        assertThat(service.dashboard(null).articles()).extracting(row -> row.article().getTitle())
                .containsExactly("ana", "bruno", "zoe");
    }

    @Test
    void usesFetchedAtWhenPublishedAtIsMissing() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 2)));
        when(articles.findUnreadRows()).thenReturn(List.of(
                article(1, 1, "dated", "2026-01-10T00:00:00Z", "2026-03-01T00:00:00Z"),
                article(2, 1, "undated-but-fetched-later", null, "2026-01-20T00:00:00Z")));

        assertThat(service.dashboard(null).articles()).extracting(row -> row.article().getTitle())
                .containsExactly("undated-but-fetched-later", "dated");
    }

    @Test
    void ignoresArticlesAndCountsOfSourcesThatNoLongerExist() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 1), counts(99, 5)));
        when(articles.findUnreadRows()).thenReturn(List.of(
                article(1, 1, "kept", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
                article(2, 99, "orphan", "2026-01-02T00:00:00Z", "2026-02-01T00:00:00Z")));

        Dashboard dashboard = service.dashboard(null);

        assertThat(dashboard.articles()).extracting(row -> row.article().getTitle()).containsExactly("kept");
        assertThat(dashboard.sources()).extracting(entry -> entry.source().getName()).containsExactly("ana");
        assertThat(dashboard.totalUnread()).isEqualTo(1);
    }

    @Test
    void theSourceListIsAlphabeticalWithUnreadCountsAndKeepsSourcesWithoutUnread() {
        when(sources.findAll()).thenReturn(List.of(source(1, "carl"), source(2, "Ana"), source(3, "bea"), source(4, "empty")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 4), counts(2, 0), counts(3, 7)));
        when(articles.findUnreadRows()).thenReturn(List.of());

        Dashboard dashboard = service.dashboard(null);

        assertThat(dashboard.sources()).extracting(entry -> entry.source().getName(), SourceUnread::unread)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Ana", 0L),
                        org.assertj.core.groups.Tuple.tuple("bea", 7L),
                        org.assertj.core.groups.Tuple.tuple("carl", 4L));
        assertThat(dashboard.totalUnread()).isEqualTo(11);
        assertThat(dashboard.articles()).isEmpty();
    }

    @Test
    void aSourceViewListsItsNewestArticlesReadOrNotNewestFirst() {
        Source ana = source(1, "ana");
        when(sources.findAll()).thenReturn(List.of(ana, source(2, "bea")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 1), counts(2, 3)));
        when(articles.findNewestRowsBySourceId(any(), any())).thenReturn(List.of(
                article(3, 1, "unread-newest", "2026-01-09T00:00:00Z", "2026-02-01T00:00:00Z"),
                read(article(2, 1, "read-mid", "2026-01-05T00:00:00Z", "2026-02-01T00:00:00Z")),
                read(article(1, 1, "read-old", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"))));
        when(articles.findUnreadRowsBySourceId(1L)).thenReturn(List.of(
                article(3, 1, "unread-newest", "2026-01-09T00:00:00Z", "2026-02-01T00:00:00Z")));

        Dashboard dashboard = service.dashboard(1L);

        assertThat(dashboard.selected()).isEqualTo(ana);
        assertThat(dashboard.articles()).extracting(row -> row.article().getTitle())
                .containsExactly("unread-newest", "read-mid", "read-old");
        assertThat(dashboard.selectedUnread()).isEqualTo(1);
    }

    @Test
    void aSourceViewAsksForTheConfiguredNumberOfNewestArticles() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 0)));

        service.dashboard(1L);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> limit =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(articles).findNewestRowsBySourceId(org.mockito.ArgumentMatchers.eq(1L), limit.capture());
        assertThat(limit.getValue().getPageSize()).isEqualTo(10);
        assertThat(limit.getValue().getPageNumber()).isZero();
    }

    @Test
    void olderUnreadArticlesAreKeptSoTheUnreadCountMatchesWhatIsShown() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 2)));
        when(articles.findNewestRowsBySourceId(any(), any())).thenReturn(List.of(
                read(article(5, 1, "read-newest", "2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z"))));
        when(articles.findUnreadRowsBySourceId(1L)).thenReturn(List.of(
                article(1, 1, "unread-ancient", "2025-01-01T00:00:00Z", "2026-04-01T00:00:00Z"),
                article(2, 1, "unread-old", "2025-06-01T00:00:00Z", "2026-04-01T00:00:00Z")));

        assertThat(service.dashboard(1L).articles()).extracting(row -> row.article().getTitle())
                .containsExactly("read-newest", "unread-old", "unread-ancient");
    }

    @Test
    void anUnknownSourceFallsBackToTheAllView() {
        when(sources.findAll()).thenReturn(List.of(source(1, "ana")));
        when(articles.countBySource()).thenReturn(List.of(counts(1, 1)));
        when(articles.findUnreadRows()).thenReturn(List.of(
                article(1, 1, "a", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")));

        Dashboard dashboard = service.dashboard(42L);

        assertThat(dashboard.selected()).isNull();
        assertThat(dashboard.articles()).hasSize(1);
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

    @Test
    void markAllReadUpdatesTheSourceInOneStatementAndReturnsTheCount() {
        when(sources.existsById(1L)).thenReturn(true);
        when(articles.markAllReadBySourceId(org.mockito.ArgumentMatchers.eq(1L), any())).thenReturn(7);

        assertThat(service.markAllRead(1L)).isEqualTo(7);

        verify(articles).markAllReadBySourceId(org.mockito.ArgumentMatchers.eq(1L), any(Instant.class));
    }

    @Test
    void markAllReadOfAnUnknownSourceChangesNothing() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.markAllRead(42L))
                .isInstanceOf(SourceNotFoundException.class);

        org.mockito.Mockito.verifyNoInteractions(articles);
    }
}
