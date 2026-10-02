package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.ContentOrigin;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.FetchedPage;
import com.pparra.rssreader.fetch.HttpFetcher;
import com.pparra.rssreader.fetch.Pages;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One test per acceptance criterion of US-1. Criteria already covered adequately by
 * Us1FlowTest (view/remove list, transparent archive.ph fallback) are not repeated here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Us1AcceptanceCriteriaTest {

    private static final String STORY = "https://news.com/story";
    private static final String ARCHIVE_LOOKUP = "https://archive.ph/newest/" + STORY;

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private Article storedArticle() {
        Long sourceId = sources.save(new Source("https://news.com", "News", SourceType.SCRAPE, null, Instant.now())).getId();
        return articles.save(new Article(sourceId, STORY, "Story", null, Instant.now()));
    }

    private String readerPage(Article article) throws Exception {
        return mvc.perform(get("/articles/" + article.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void givenAUrl_theSystemDetectsAndStoresWhetherItIsATrueFeedOrAScrapeTarget() throws Exception {
        when(http.get("https://feed.example.com/rss")).thenReturn(
                Pages.ok("application/rss+xml", "<?xml version=\"1.0\"?><rss version=\"2.0\"></rss>", "https://feed.example.com/rss"));
        when(http.get("https://blog.example.com")).thenReturn(
                Pages.html("<html><body><p>just a page</p></body></html>", "https://blog.example.com"));

        mvc.perform(post("/sources").param("url", "https://feed.example.com/rss").param("name", "Feed"));
        mvc.perform(post("/sources").param("url", "https://blog.example.com").param("name", "Blog"));

        assertThat(sources.findAll()).extracting(Source::getName, Source::getType)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Feed", SourceType.RSS),
                        org.assertj.core.groups.Tuple.tuple("Blog", SourceType.SCRAPE));
    }

    @Test
    void givenAPageThatLinksToAFeed_theDiscoveredFeedIsStoredAsRss() throws Exception {
        when(http.get("https://blog.example.com")).thenReturn(Pages.html(
                "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed.xml\"></head></html>",
                "https://blog.example.com"));

        mvc.perform(post("/sources").param("url", "https://blog.example.com").param("name", "Blog"));

        assertThat(sources.findAll()).singleElement().satisfies(s -> {
            assertThat(s.getType()).isEqualTo(SourceType.RSS);
            assertThat(s.getFeedUrl()).isEqualTo("https://blog.example.com/feed.xml");
        });
    }

    @Test
    void ifASourceIsCloudflareProtected_itIsAddedButClearlyFlaggedAsUnsupportedInsteadOfFailingSilently() throws Exception {
        when(http.get("https://walled.example.com")).thenReturn(new FetchedPage(
                403, "text/html", "<title>Just a moment...</title>", "https://walled.example.com",
                Map.of("cf-mitigated", "challenge")));

        mvc.perform(post("/sources").param("url", "https://walled.example.com").param("name", "Walled"))
                .andExpect(status().is3xxRedirection());

        assertThat(sources.findAll()).singleElement()
                .satisfies(s -> assertThat(s.getType()).isEqualTo(SourceType.UNSUPPORTED));
        String page = mvc.perform(get("/sources")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Walled").containsIgnoringCase("unsupported");
    }

    @Test
    void whenNoSnapshotCanBeRead_theAppSaysSoAndOffersLinksToTheOriginalAndToArchivePh() throws Exception {
        Article article = storedArticle();
        when(http.get(STORY)).thenReturn(Pages.status(403, "paywall", STORY));
        when(http.get(ARCHIVE_LOOKUP)).thenReturn(Pages.status(404, "No results", ARCHIVE_LOOKUP));

        String page = readerPage(article);

        assertThat(page).contains("could not be loaded");
        assertThat(page).contains("href=\"" + STORY + "\"");
        assertThat(page).contains("href=\"" + ARCHIVE_LOOKUP + "\"");
        assertThat(articles.findById(article.getId()).orElseThrow().getContentOrigin()).isEqualTo(ContentOrigin.UNAVAILABLE);
    }

    @Test
    void whenArchivePhShowsACheck_theAppDegradesToLinksAndNeverSubmitsNewPagesOrTriesToBypassIt() throws Exception {
        Article article = storedArticle();
        when(http.get(STORY)).thenReturn(Pages.status(402, "Payment required", STORY));
        when(http.get(ARCHIVE_LOOKUP)).thenReturn(
                Pages.html("<html><div class=\"g-recaptcha\"></div></html>", "https://archive.ph/submit/"));

        String page = readerPage(article);

        assertThat(page).contains("could not be loaded").contains("href=\"" + STORY + "\"").contains("href=\"" + ARCHIVE_LOOKUP + "\"");
        ArgumentCaptor<String> requested = ArgumentCaptor.forClass(String.class);
        verify(http, atLeastOnce()).get(requested.capture());
        assertThat(requested.getAllValues()).isSubsetOf(List.of(STORY, ARCHIVE_LOOKUP));
    }

    @Test
    void whenTheOriginalIsUnreachableAndArchiveHasNoAnswerEither_theReaderStillRendersInsteadOfAnErrorPage() throws Exception {
        Article article = storedArticle();
        when(http.get(anyString())).thenThrow(new IOException("down"));

        assertThat(readerPage(article)).contains("could not be loaded");
    }
}
