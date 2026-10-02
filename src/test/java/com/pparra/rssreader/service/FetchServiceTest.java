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
import com.pparra.rssreader.fetch.SourceBlockedException;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FetchServiceTest {

    private final SourceRepository sources = mock(SourceRepository.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final RssFetcher rss = mock(RssFetcher.class);
    private final ScrapeFetcher scrape = mock(ScrapeFetcher.class);
    private final FetchService service = new FetchService(sources, articles, rss, scrape);

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
        when(articles.existsBySourceIdAndUrl(any(), org.mockito.ArgumentMatchers.eq("https://ana.com/old")))
                .thenReturn(true);

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
        when(articles.existsBySourceId(any())).thenReturn(false);

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
        when(articles.existsBySourceId(any())).thenReturn(true);

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
}
