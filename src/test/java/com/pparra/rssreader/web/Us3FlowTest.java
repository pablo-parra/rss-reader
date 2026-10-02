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

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.HttpFetcher;
import com.pparra.rssreader.fetch.Pages;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
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
class Us3FlowTest {

    private static final String FEED = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Ana</title>
              <item><title>Fresh from feed</title><link>https://ana.com/fresh</link></item>
            </channel></rss>""";

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private Long source(String name) {
        return sources.save(new Source("https://" + name + ".com", name, SourceType.RSS, null, Instant.now())).getId();
    }

    private Article article(Long sourceId, String title, String published) {
        return articles.save(new Article(sourceId, "https://x.com/" + title.replace(' ', '-'), title,
                Instant.parse(published), Instant.parse("2026-02-01T00:00:00Z")));
    }

    private String dashboard() throws Exception {
        return mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void listsUnreadArticlesGroupedByAuthorNewestFirst() throws Exception {
        Long ana = source("Ana");
        Long bea = source("Bea");
        article(ana, "Ana older post", "2026-01-01T00:00:00Z");
        article(ana, "Ana newer post", "2026-01-02T00:00:00Z");
        article(bea, "Bea latest post", "2026-01-09T00:00:00Z");

        String html = dashboard();

        assertThat(html).contains("Latest articles").contains("(3 unread)");
        assertThat(html.indexOf("Bea")).isLessThan(html.indexOf("Ana"));
        assertThat(html.indexOf("Ana newer post")).isLessThan(html.indexOf("Ana older post"));
    }

    @Test
    void openedArticleStaysOnTheDashboardAsReadAndTheUnreadCountDrops() throws Exception {
        Long ana = source("Ana");
        Article open = article(ana, "Open me", "2026-01-02T00:00:00Z");
        article(ana, "Leave me", "2026-01-01T00:00:00Z");
        when(http.get(anyString())).thenReturn(Pages.html(Pages.articleHtml("Full readable paragraph of text.", 40), "https://x.com/Open-me"));

        assertThat(dashboard()).contains("(2 unread)");
        mvc.perform(get("/articles/" + open.getId())).andExpect(status().isOk());

        String after = dashboard();
        assertThat(after).contains("(1 unread)").contains("Leave me");
        assertThat(after).containsPattern("<li class=\"read\">[\\s\\S]*?Open me");
        assertThat(after).containsPattern("<li class=\"unread\">[\\s\\S]*?Leave me");
        Article saved = articles.findById(open.getId()).orElseThrow();
        assertThat(saved.isRead()).isTrue();
        assertThat(saved.getReadAt()).isNotNull();
    }

    @Test
    void readingKeepsTheArticleInPlaceInsteadOfReordering() throws Exception {
        Long ana = source("Ana");
        Article newer = article(ana, "Newer post", "2026-01-02T00:00:00Z");
        article(ana, "Older post", "2026-01-01T00:00:00Z");
        when(http.get(anyString())).thenReturn(Pages.html(Pages.articleHtml("Full readable paragraph of text.", 40), "https://x.com/x"));

        mvc.perform(get("/articles/" + newer.getId())).andExpect(status().isOk());

        String html = dashboard();
        assertThat(html.indexOf("Newer post")).isLessThan(html.indexOf("Older post"));
    }

    @Test
    void articlesReadLongAgoOrNeverOpenedAreHidden() throws Exception {
        Long ana = source("Ana");
        Article recent = article(ana, "Read yesterday", "2026-01-03T00:00:00Z");
        recent.markRead(Instant.now().minusSeconds(86_400));
        articles.save(recent);
        Article old = article(ana, "Read last month", "2026-01-02T00:00:00Z");
        old.markRead(Instant.now().minusSeconds(30 * 86_400L));
        articles.save(old);
        Article backfilled = article(ana, "Backfilled on first fetch", "2026-01-01T00:00:00Z");
        backfilled.markReadSilently();
        articles.save(backfilled);

        String html = dashboard();

        assertThat(html).contains("Read yesterday").contains("(all read)");
        assertThat(html).doesNotContain("Read last month").doesNotContain("Backfilled on first fetch");
    }

    @Test
    void emptyDashboardPointsToSourcesAndFetch() throws Exception {
        assertThat(dashboard()).contains("all caught up").contains("Fetch now").contains("/sources");
    }

    @Test
    void fetchNowFromTheDashboardReturnsToTheDashboardWithSummary() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", FEED, "https://ana.com/feed"));

        mvc.perform(post("/sources/fetch-now").param("from", "dashboard"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("message", "Fetched 1 source: 1 new article"));

        assertThat(dashboard()).contains("Fresh from feed");
    }

    @Test
    void dashboardRendersTheFlashMessageAfterFetching() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        when(http.get("https://ana.com/feed")).thenReturn(Pages.ok("application/rss+xml", FEED, "https://ana.com/feed"));

        var result = mvc.perform(post("/sources/fetch-now").param("from", "dashboard")).andReturn();

        mvc.perform(get("/").flashAttrs(result.getFlashMap()))
                .andExpect(content().string(Matchers.containsString("Fetched 1 source: 1 new article")));
    }

    @Test
    void unknownFromValueFallsBackToSourcesPage() throws Exception {
        mvc.perform(post("/sources/fetch-now").param("from", "https://evil.example"))
                .andExpect(redirectedUrl("/sources"));
        mvc.perform(post("/sources/fetch-now")).andExpect(redirectedUrl("/sources"));
    }
}
