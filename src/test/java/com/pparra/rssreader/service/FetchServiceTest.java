package com.pparra.rssreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.FetchStatus;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.FeedItem;
import com.pparra.rssreader.fetch.RssFetcher;
import com.pparra.rssreader.fetch.ScrapeFetcher;
import com.pparra.rssreader.fetch.NotAFeedException;
import com.pparra.rssreader.fetch.SourceBlockedException;
import com.pparra.rssreader.fetch.SourceTypeDetector;
import com.pparra.rssreader.fetch.SourceTypeDetector.Detection;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FetchServiceTest {

    private final SourceRepository sources = mock(SourceRepository.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final RssFetcher rss = mock(RssFetcher.class);
    private final ScrapeFetcher scrape = mock(ScrapeFetcher.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final SourceTypeDetector detector = mock(SourceTypeDetector.class);
    private final FetchService service = new FetchService(
            sources, articles, rss, scrape, detector, new TransactionTemplate(txManager));

    @BeforeEach
    void sourcesExist() {
        when(sources.existsById(any())).thenReturn(true);
    }

    private static Source source(String name, SourceType type) {
        return new Source("https://" + name + ".com", name, type, null, Instant.now());
    }

    @Test
    void storesOnlyNewArticlesAsUnread() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of(
                new FeedItem("https://ana.com/old", "Old", null),
                new FeedItem("https://ana.com/new", "New", Instant.parse("2026-01-01T00:00:00Z"))));
        when(articles.findUrlsBySourceId(any())).thenReturn(List.of("https://ana.com/old"));

        FetchSummary summary = service.fetchAll().orElseThrow();

        ArgumentCaptor<Article> saved = ArgumentCaptor.forClass(Article.class);
        verify(articles).save(saved.capture());
        assertThat(saved.getValue().getUrl()).isEqualTo("https://ana.com/new");
        assertThat(saved.getValue().isRead()).isFalse();
        assertThat(summary).isEqualTo(new FetchSummary(1, 1, 0, 0));
        assertThat(ana.getLastFetchStatus()).isEqualTo(FetchStatus.OK);
        assertThat(ana.getLastFetchedAt()).isNotNull();
    }

    private static List<FeedItem> items(int count) {
        List<FeedItem> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(new FeedItem("https://ana.com/" + i, "Post " + i, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)));
        }
        return items;
    }

    @Test
    void firstFetchKeepsOnlyTheNewestTenUnreadAndStoresTheRestAsRead() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(items(15));

        FetchSummary summary = service.fetchAll().orElseThrow();

        ArgumentCaptor<Article> saved = ArgumentCaptor.forClass(Article.class);
        verify(articles, times(15)).save(saved.capture());
        List<Article> unread = saved.getAllValues().stream().filter(a -> !a.isRead()).toList();
        List<Article> read = saved.getAllValues().stream().filter(Article::isRead).toList();
        assertThat(unread).hasSize(10).extracting(Article::getTitle)
                .containsExactlyInAnyOrder("Post 15", "Post 14", "Post 13", "Post 12", "Post 11",
                        "Post 10", "Post 9", "Post 8", "Post 7", "Post 6");
        assertThat(read).hasSize(5);
        assertThat(summary.newArticles()).isEqualTo(15);
    }

    @Test
    void laterFetchesKeepEveryNewArticleUnread() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(items(15));
        when(articles.findUrlsBySourceId(any())).thenReturn(List.of("https://ana.com/seed"));

        service.fetchAll();

        ArgumentCaptor<Article> saved = ArgumentCaptor.forClass(Article.class);
        verify(articles, times(15)).save(saved.capture());
        assertThat(saved.getAllValues()).noneMatch(Article::isRead);
    }

    @Test
    void usesTheFetcherMatchingTheSourceType() throws IOException {
        Source blog = source("blog", SourceType.SCRAPE);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(blog));
        when(scrape.fetch(blog)).thenReturn(List.of());

        service.fetchAll();

        verify(scrape).fetch(blog);
        verify(rss, never()).fetch(any());
    }

    @Test
    void skipsUnsupportedSourcesWithoutFetching() throws IOException {
        Source walled = source("walled", SourceType.UNSUPPORTED);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(walled));

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary).isEqualTo(new FetchSummary(0, 0, 0, 1));
        verify(rss, never()).fetch(any());
        verify(scrape, never()).fetch(any());
    }

    @Test
    void oneFailingSourceDoesNotAbortTheRun() throws IOException {
        Source bad = source("bad", SourceType.RSS);
        Source good = source("good", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(bad, good));
        when(rss.fetch(bad)).thenThrow(new IOException("timeout"));
        when(rss.fetch(good)).thenReturn(List.of(new FeedItem("https://good.com/p", "P", null)));

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary).isEqualTo(new FetchSummary(1, 1, 1, 0));
        assertThat(bad.getLastFetchStatus()).isEqualTo(FetchStatus.ERROR);
        assertThat(bad.getType()).isEqualTo(SourceType.RSS);
        assertThat(good.getLastFetchStatus()).isEqualTo(FetchStatus.OK);
    }

    @Test
    void unexpectedRuntimeErrorIsIsolatedToo() throws IOException {
        Source bad = source("bad", SourceType.SCRAPE);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(bad));
        when(scrape.fetch(bad)).thenThrow(new IllegalStateException("parser bug"));

        assertThat(service.fetchAll().orElseThrow().failed()).isEqualTo(1);
        assertThat(bad.getLastFetchStatus()).isEqualTo(FetchStatus.ERROR);
    }

    @Test
    void botCheckMarksSourceUnsupportedAndCountsAsSkipped() throws IOException {
        Source walled = source("walled", SourceType.SCRAPE);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(walled));
        when(scrape.fetch(walled)).thenThrow(new SourceBlockedException("https://walled.com"));

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary).isEqualTo(new FetchSummary(0, 0, 0, 1));
        assertThat(walled.getType()).isEqualTo(SourceType.UNSUPPORTED);
        assertThat(walled.getLastFetchStatus()).isEqualTo(FetchStatus.UNSUPPORTED);
        verify(sources, times(1)).save(walled);
    }

    @Test
    void secondRunIsRejectedWhileOneIsInProgress() throws Exception {
        Source slow = source("slow", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(slow));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(rss.fetch(slow)).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(service::fetchAll);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.fetchAll()).isEmpty();

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(service.fetchAll()).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void newArticlesKeepTheFullContentCarriedByTheFeed() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of(new FeedItem("https://ana.com/p", "P", null, "<p>full body</p>")));

        service.fetchAll();

        ArgumentCaptor<Article> saved = ArgumentCaptor.forClass(Article.class);
        verify(articles).save(saved.capture());
        assertThat(saved.getValue().getFeedContentHtml()).isEqualTo("<p>full body</p>");
    }

    @Test
    void existingArticleWithoutFeedContentGetsItBackfilledAndAFailedLoadIsReset() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        Article existing = new Article(1L, "https://ana.com/p", "P", null, Instant.now());
        existing.cacheFailure("Original page: blocked", Instant.now(), 2);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of(new FeedItem("https://ana.com/p", "P", null, "<p>full body</p>")));
        when(articles.findUrlsBySourceId(any())).thenReturn(List.of("https://ana.com/p"));
        when(articles.findBackfillCandidates(any())).thenReturn(List.of(existing));

        FetchSummary summary = service.fetchAll().orElseThrow();

        verify(articles).save(existing);
        assertThat(existing.getFeedContentHtml()).isEqualTo("<p>full body</p>");
        assertThat(existing.getContentOrigin()).isNull();
        assertThat(existing.getContentFailureReason()).isNull();
        assertThat(summary.newArticles()).isZero();
    }

    @Test
    void backfillKeepsContentAlreadyLoadedFromThePage() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        Article existing = new Article(1L, "https://ana.com/p", "P", null, Instant.now());
        existing.cacheContent("<p>from page</p>", com.pparra.rssreader.domain.ContentOrigin.ORIGINAL, Instant.now(), 2);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of(new FeedItem("https://ana.com/p", "P", null, "<p>full body</p>")));
        when(articles.findUrlsBySourceId(any())).thenReturn(List.of("https://ana.com/p"));
        when(articles.findBackfillCandidates(any())).thenReturn(List.of(existing));

        service.fetchAll();

        assertThat(existing.getContentHtml()).isEqualTo("<p>from page</p>");
    }

    @Test
    void existingArticleThatAlreadyHasFeedContentIsNotTouched() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        Article existing = new Article(1L, "https://ana.com/p", "P", null, Instant.now());
        existing.attachFeedContent("<p>first</p>");
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of(new FeedItem("https://ana.com/p", "P", null, "<p>second</p>")));
        when(articles.findUrlsBySourceId(any())).thenReturn(List.of("https://ana.com/p"));
        when(articles.findBackfillCandidates(any())).thenReturn(List.of(existing));

        service.fetchAll();

        assertThat(existing.getFeedContentHtml()).isEqualTo("<p>first</p>");
        verify(articles, never()).save(existing);
    }

    @Test
    void newArticlesStoreTheThumbnailImageFromTheFeed() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of(new FeedItem("https://ana.com/p", "P", null, null, "https://ana.com/t.jpg")));

        service.fetchAll();

        ArgumentCaptor<Article> saved = ArgumentCaptor.forClass(Article.class);
        verify(articles).save(saved.capture());
        assertThat(saved.getValue().getImageUrl()).isEqualTo("https://ana.com/t.jpg");
    }

    @Test
    void existingArticleWithoutImageGetsItBackfilledButAnExistingOneIsKept() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        Article without = new Article(1L, "https://ana.com/a", "A", null, Instant.now());
        Article with = new Article(1L, "https://ana.com/b", "B", null, Instant.now());
        with.useImageIfMissing("https://ana.com/old.jpg");
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of(
                new FeedItem("https://ana.com/a", "A", null, null, "https://ana.com/new-a.jpg"),
                new FeedItem("https://ana.com/b", "B", null, null, "https://ana.com/new-b.jpg")));
        when(articles.findUrlsBySourceId(any())).thenReturn(List.of("https://ana.com/a", "https://ana.com/b"));
        when(articles.findBackfillCandidates(any())).thenReturn(List.of(without, with));

        service.fetchAll();

        assertThat(without.getImageUrl()).isEqualTo("https://ana.com/new-a.jpg");
        assertThat(with.getImageUrl()).isEqualTo("https://ana.com/old.jpg");
        verify(articles).save(without);
        verify(articles, never()).save(with);
    }

    @Test
    void aSourceRemovedDuringTheFetchIsNotRecreatedNorGivenArticles() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(items(3));
        when(sources.existsById(any())).thenReturn(false);

        service.fetchAll();

        verify(articles, never()).save(any());
        verify(sources, never()).save(any());
    }

    @Test
    void aFailureWhileStoringRollsTheWholeSourceBackAndCountsAsFailed() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(items(5));
        when(articles.save(any(Article.class))).thenAnswer(call -> call.getArgument(0))
                .thenAnswer(call -> call.getArgument(0))
                .thenThrow(new IllegalStateException("database is locked"));

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(ana.getLastFetchStatus()).isEqualTo(FetchStatus.ERROR);
        verify(txManager).rollback(any());
    }

    @Test
    void sourcesAreDownloadedConcurrentlyButStoredInNameOrder() throws Exception {
        Source a = source("a", SourceType.RSS);
        Source b = source("b", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(a, b));
        CountDownLatch bothStarted = new CountDownLatch(2);
        when(rss.fetch(any())).thenAnswer(call -> {
            bothStarted.countDown();
            assertThat(bothStarted.await(5, TimeUnit.SECONDS)).as("both downloads overlap").isTrue();
            return List.of();
        });

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary.sourcesOk()).isEqualTo(2);
    }

    @Test
    void aFailedFetchKeepsTheReasonOnTheSource() throws IOException {
        Source bad = source("bad", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(bad));
        when(rss.fetch(bad)).thenThrow(new IOException("HTTP 400 for https://bad.com/feed"));

        service.fetchAll();

        assertThat(bad.getLastFetchStatus()).isEqualTo(FetchStatus.ERROR);
        assertThat(bad.getLastFetchError()).isEqualTo("HTTP 400 for https://bad.com/feed");
    }

    @Test
    void aLaterSuccessfulFetchClearsTheReason() throws IOException {
        Source ana = source("ana", SourceType.RSS);
        ana.recordError("HTTP 500", Instant.now());
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(rss.fetch(ana)).thenReturn(List.of());

        service.fetchAll();

        assertThat(ana.getLastFetchStatus()).isEqualTo(FetchStatus.OK);
        assertThat(ana.getLastFetchError()).isNull();
    }

    @Test
    void anRssSourceThatReturnsAWebPageIsScrapedWhenThePageHasNoFeed() throws IOException {
        Source author = source("author", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(author));
        when(rss.fetch(author)).thenThrow(new NotAFeedException(author.getUrl()));
        when(detector.detect(author.getUrl())).thenReturn(new Detection(SourceType.SCRAPE, null));
        when(scrape.fetch(author)).thenReturn(List.of(new FeedItem("https://author.com/post", "Post", null)));

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary).isEqualTo(new FetchSummary(1, 1, 0, 0));
        assertThat(author.getType()).isEqualTo(SourceType.SCRAPE);
        assertThat(author.getLastFetchStatus()).isEqualTo(FetchStatus.OK);
        verify(sources).save(author);
    }

    @Test
    void anRssSourceThatReturnsAWebPageUsesTheFeedLinkedFromIt() throws IOException {
        Source moved = source("moved", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(moved));
        when(rss.fetch(moved)).thenThrow(new NotAFeedException(moved.getUrl())).thenReturn(
                List.of(new FeedItem("https://moved.com/post", "Post", null)));
        when(detector.detect(moved.getUrl())).thenReturn(new Detection(SourceType.RSS, "https://moved.com/feed"));

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary.newArticles()).isEqualTo(1);
        assertThat(moved.getType()).isEqualTo(SourceType.RSS);
        assertThat(moved.getFeedUrl()).isEqualTo("https://moved.com/feed");
    }

    @Test
    void whenDetectionFindsNothingUsableTheOriginalErrorIsReported() throws IOException {
        Source gone = source("gone", SourceType.RSS);
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(gone));
        when(rss.fetch(gone)).thenThrow(new NotAFeedException(gone.getUrl()));
        when(detector.detect(gone.getUrl())).thenReturn(new Detection(SourceType.UNSUPPORTED, null));

        FetchSummary summary = service.fetchAll().orElseThrow();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(gone.getType()).isEqualTo(SourceType.RSS);
        assertThat(gone.getLastFetchError()).startsWith("The URL returns a web page, not a feed");
    }

    @Test
    void existingArticleWithoutADateGetsTheOneTheSourceNowProvides() throws IOException {
        Source ana = source("ana", SourceType.SCRAPE);
        Article existing = new Article(1L, "https://ana.com/p", "P", null, Instant.now());
        Article dated = new Article(1L, "https://ana.com/q", "Q", Instant.parse("2026-01-01T00:00:00Z"), Instant.now());
        when(sources.findAllByOrderByNameAsc()).thenReturn(List.of(ana));
        when(scrape.fetch(ana)).thenReturn(List.of(
                new FeedItem("https://ana.com/p", "P", Instant.parse("2026-10-01T12:00:00Z")),
                new FeedItem("https://ana.com/q", "Q", Instant.parse("2026-02-02T00:00:00Z"))));
        when(articles.findUrlsBySourceId(any())).thenReturn(List.of("https://ana.com/p", "https://ana.com/q"));
        when(articles.findBackfillCandidates(any())).thenReturn(List.of(existing, dated));

        service.fetchAll();

        assertThat(existing.getPublishedAt()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
        assertThat(dated.getPublishedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        verify(articles).save(existing);
        verify(articles, never()).save(dated);
    }
}
