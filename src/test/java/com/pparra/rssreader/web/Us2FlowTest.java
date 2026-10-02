package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.FetchStatus;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.HttpFetcher;
import com.pparra.rssreader.fetch.Pages;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import com.pparra.rssreader.scheduler.DailyFetchJob;
import java.io.IOException;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Us2FlowTest {

    private static final String FEED = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Ana</title>
              <item><title>One</title><link>https://ana.com/1</link></item>
              <item><title>Two</title><link>https://ana.com/2</link></item>
            </channel></rss>""";

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @Autowired DailyFetchJob dailyJob;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    @Test
    void fetchNowButtonIngestsArticlesAndSecondClickAddsNothing() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", FEED, "https://ana.com/feed"));

        mvc.perform(post("/sources/fetch-now"))
                .andExpect(redirectedUrl("/sources"))
                .andExpect(flash().attribute("message", "Fetched 1 source: 2 new articles"));
        assertThat(articles.count()).isEqualTo(2);
        assertThat(articles.findAll()).allSatisfy(a -> assertThat(a.isRead()).isFalse());

        mvc.perform(post("/sources/fetch-now"))
                .andExpect(flash().attribute("message", "Fetched 1 source: 0 new articles"));
        assertThat(articles.count()).isEqualTo(2);
    }

    @Test
    void summaryMentionsFailuresAndSkippedSources() throws Exception {
        sources.save(new Source("https://down.com/feed", "Down", SourceType.RSS, null, Instant.now()));
        sources.save(new Source("https://walled.com", "Walled", SourceType.UNSUPPORTED, null, Instant.now()));
        when(http.get(anyString())).thenThrow(new IOException("down"));

        mvc.perform(post("/sources/fetch-now"))
                .andExpect(flash().attribute("warning", "Fetched 0 sources: 0 new articles, 1 failed, 1 skipped (unsupported)"));
    }

    @Test
    void dailyJobUsesTheSameProcess() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", FEED, "https://ana.com/feed"));

        dailyJob.run();

        assertThat(articles.count()).isEqualTo(2);
        assertThat(sources.findAll().get(0).getLastFetchStatus()).isEqualTo(FetchStatus.OK);
    }

    @Test
    void sourcesPageShowsButtonAndLastFetch() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        when(http.get(anyString())).thenReturn(Pages.ok("application/rss+xml", FEED, "https://ana.com/feed"));
        mvc.perform(post("/sources/fetch-now"));

        mvc.perform(get("/sources"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Fetch now")))
                .andExpect(content().string(Matchers.containsString("OK")));
    }
}
