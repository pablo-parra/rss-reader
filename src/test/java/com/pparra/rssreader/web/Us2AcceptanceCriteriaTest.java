package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.HttpFetcher;
import com.pparra.rssreader.fetch.Pages;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import com.pparra.rssreader.scheduler.DailyFetchJob;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * One test per acceptance criterion of US-2. Criteria already covered adequately by existing tests:
 * - "Already-seen articles (by URL) are not re-added": FetchServiceTest.storesOnlyNewArticlesAsUnread,
 *   Us2FlowTest.fetchNowButtonIngestsArticlesAndSecondClickAddsNothing
 * - "First fetch keeps only 10 newest unread": FetchServiceTest.firstFetchKeepsOnlyTheNewestTenUnreadAndStoresTheRestAsRead
 *   (+ laterFetchesKeepEveryNewArticleUnread)
 * - "Fetch now" summary of new/failed/skipped: Us2FlowTest.fetchNowButtonIngests... and summaryMentionsFailuresAndSkippedSources
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Us2AcceptanceCriteriaTest {

    private static final String RSS_FEED = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Ana</title>
              <item><title>Rss post</title><link>https://ana.com/rss-post</link></item>
            </channel></rss>""";

    private static final String BLOG_PAGE = """
            <html><body><article><h2><a href="/scraped-post-with-long-title">Scraped post with a long title</a></h2></article></body></html>""";

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @Autowired DailyFetchJob dailyJob;
    @Autowired Environment environment;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    @Test
    void aScheduledJobRunsDaily() {
        var scheduled = java.util.Arrays.stream(DailyFetchJob.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Scheduled.class))
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(scheduled).hasSize(1);

        String cron = environment.resolvePlaceholders(scheduled.get(0).cron());
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime first = expression.next(ZonedDateTime.now(ZoneId.systemDefault()));
        ZonedDateTime second = expression.next(first);
        ZonedDateTime third = expression.next(second);
        assertThat(java.time.Duration.between(first, second)).isEqualTo(java.time.Duration.ofDays(1));
        assertThat(java.time.Duration.between(second, third)).isEqualTo(java.time.Duration.ofDays(1));
    }

    @Test
    void theScheduledJobFetchesNewItemsFromEveryNonUnsupportedSource_rssParseOrHtmlScrape() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        sources.save(new Source("https://blog.com/author", "Blog", SourceType.SCRAPE, null, Instant.now()));
        sources.save(new Source("https://walled.com", "Walled", SourceType.UNSUPPORTED, null, Instant.now()));
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS_FEED, "https://ana.com/feed"));
        when(http.get("https://blog.com/author")).thenReturn(Pages.html(BLOG_PAGE, "https://blog.com/author"));

        dailyJob.run();

        assertThat(articles.findAll()).extracting(Article::getUrl)
                .containsExactlyInAnyOrder("https://ana.com/rss-post", "https://blog.com/scraped-post-with-long-title");
        verify(http, never()).get("https://walled.com");
    }

    @Test
    void theScheduledJobSurvivesASourceThatFailsAndStillFetchesTheOthers() throws Exception {
        sources.save(new Source("https://down.com/feed", "Down", SourceType.RSS, null, Instant.now()));
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        when(http.get("https://down.com/feed")).thenThrow(new java.io.IOException("timeout"));
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS_FEED, "https://ana.com/feed"));

        dailyJob.run();

        assertThat(articles.findAll()).extracting(Article::getUrl).containsExactly("https://ana.com/rss-post");
    }

    @Test
    void eachFetchedArticleIsStoredWithTitleUrlSourceAndAnUnreadStatusDefaultingToTrue() throws Exception {
        Long ana = sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now())).getId();
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS_FEED, "https://ana.com/feed"));

        mvc.perform(post("/sources/fetch-now"));

        assertThat(articles.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getTitle()).isEqualTo("Rss post");
            assertThat(a.getUrl()).isEqualTo("https://ana.com/rss-post");
            assertThat(a.getSourceId()).isEqualTo(ana);
            assertThat(a.isRead()).isFalse();
            assertThat(a.getReadAt()).isNull();
        });
    }

    @Test
    void aFetchNowButtonRunsTheSameProcessAsTheDailyJobOnDemand() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        sources.save(new Source("https://blog.com/author", "Blog", SourceType.SCRAPE, null, Instant.now()));
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", RSS_FEED, "https://ana.com/feed"));
        when(http.get("https://blog.com/author")).thenReturn(Pages.html(BLOG_PAGE, "https://blog.com/author"));

        mvc.perform(post("/sources/fetch-now")).andExpect(redirectedUrl("/sources"));

        assertThat(articles.findAll()).extracting(Article::getUrl)
                .containsExactlyInAnyOrder("https://ana.com/rss-post", "https://blog.com/scraped-post-with-long-title");
    }

    @Test
    void ifAFetchIsAlreadyRunning_fetchNowSaysSoInsteadOfStartingAnotherOne() throws Exception {
        sources.save(new Source("https://slow.com/feed", "Slow", SourceType.RSS, null, Instant.now()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(http.get("https://slow.com/feed")).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return Pages.ok("application/rss+xml", RSS_FEED, "https://slow.com/feed");
        });

        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(post("/sources/fetch-now")).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            MvcResult second = mvc.perform(post("/sources/fetch-now")).andReturn();

            assertThat(second.getFlashMap().values()).anySatisfy(value ->
                    assertThat(String.valueOf(value)).matches("(?is).*(already|in progress|running).*"));
            assertThat(second.getFlashMap().values()).noneSatisfy(value ->
                    assertThat(String.valueOf(value)).containsIgnoringCase("new article"));
            verify(http, org.mockito.Mockito.times(1)).get("https://slow.com/feed");
        } finally {
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
        }
    }
}
