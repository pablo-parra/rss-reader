package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class FailedArticleVisibilityTest {

    private static final String STORY = "https://blog.example.com/story";
    private static final String LOOKUP = "https://archive.ph/newest/" + STORY;

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private Article article() {
        Long sourceId = sources.save(new Source("https://blog.example.com", "Blog", SourceType.RSS, null, Instant.now())).getId();
        return articles.save(new Article(sourceId, STORY, "Blocked story", Instant.now(), Instant.now()));
    }

    private void cloudflareBlocksOriginalAndArchiveHasNoSnapshot() throws Exception {
        when(http.get(STORY)).thenReturn(new FetchedPage(403, "text/html", "<title>Just a moment...</title>", STORY,
                Map.of("cf-mitigated", "challenge")));
        when(http.get(LOOKUP)).thenReturn(Pages.status(404, "No results", LOOKUP));
    }

    @Test
    void failureReasonIsStoredAndLoggedWhenAnArticleCannotBeLoaded(CapturedOutput output) throws Exception {
        Article article = article();
        cloudflareBlocksOriginalAndArchiveHasNoSnapshot();

        mvc.perform(get("/articles/" + article.getId())).andExpect(status().isOk());

        Article saved = articles.findById(article.getId()).orElseThrow();
        assertThat(saved.getContentOrigin()).isEqualTo(ContentOrigin.UNAVAILABLE);
        assertThat(saved.getContentFailureReason())
                .contains("bot challenge").contains("HTTP 403").contains("no snapshot exists");
        assertThat(output.getOut()).contains("could not be loaded").contains(STORY).contains("bot challenge");
    }

    @Test
    void readerNoticeExplainsWhyTheArticleFailed() throws Exception {
        Article article = article();
        cloudflareBlocksOriginalAndArchiveHasNoSnapshot();

        String page = mvc.perform(get("/articles/" + article.getId())).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("blocked by a bot challenge");
    }

    @Test
    void dashboardMarksFailedArticlesWithAnInfoIconWhoseTooltipHoldsTheReason() throws Exception {
        Article article = article();
        cloudflareBlocksOriginalAndArchiveHasNoSnapshot();
        mvc.perform(get("/articles/" + article.getId()));

        String dashboard = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        assertThat(dashboard).containsPattern("class=\"failed-info\"[\\s\\S]*?title=\"Could not be loaded\\. Original page: blocked by a bot challenge");
    }

    @Test
    void dashboardShowsNoMarkerForArticlesThatLoadedOrWereNeverOpened() throws Exception {
        Article opened = article();
        articles.save(new Article(opened.getSourceId(), "https://blog.example.com/other", "Never opened", Instant.now(), Instant.now()));
        when(http.get(STORY)).thenReturn(Pages.html(Pages.articleHtml("Readable paragraph of text here.", 40), STORY));
        mvc.perform(get("/articles/" + opened.getId()));

        assertThat(mvc.perform(get("/")).andReturn().getResponse().getContentAsString()).doesNotContain("failed-info");
    }

    @Test
    void markerDisappearsOnceARetrySucceeds() throws Exception {
        Article article = article();
        cloudflareBlocksOriginalAndArchiveHasNoSnapshot();
        mvc.perform(get("/articles/" + article.getId()));
        when(http.get(STORY)).thenReturn(Pages.html(Pages.articleHtml("Readable paragraph of text here.", 40), STORY));

        mvc.perform(post("/articles/" + article.getId() + "/retry"));

        assertThat(mvc.perform(get("/")).andReturn().getResponse().getContentAsString()).doesNotContain("failed-info");
        assertThat(articles.findById(article.getId()).orElseThrow().getContentFailureReason()).isNull();
    }
}
