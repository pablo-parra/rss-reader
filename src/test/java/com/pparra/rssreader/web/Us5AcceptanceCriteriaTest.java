package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** One test per acceptance criterion of US-5 (Browse the dashboard by source). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Us5AcceptanceCriteriaTest {

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private Long source(String name) {
        return sources.save(new Source("https://" + name.toLowerCase() + ".com", name, SourceType.RSS, null, Instant.now())).getId();
    }

    private Article article(Long sourceId, String title, String published) {
        return articles.save(new Article(sourceId, "https://x.com/" + title.replace(' ', '-'), title,
                Instant.parse(published), Instant.parse("2026-02-01T00:00:00Z")));
    }

    private Article read(Article article) {
        article.markRead(Instant.now());
        return articles.save(article);
    }

    private String view(Long sourceId) throws Exception {
        var request = get("/");
        if (sourceId != null) {
            request = request.param("source", String.valueOf(sourceId));
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String panel(String html) {
        int start = html.indexOf("class=\"source-list\"");
        return html.substring(start, html.indexOf("</aside>", start));
    }

    @Test
    void thePanelStartsWithAllAndTheTotalThenListsEverySourceAlphabeticallyWithItsUnreadCount_keepingSourcesWithoutUnreadDimmed() throws Exception {
        Long zoe = source("Zoe");
        Long ana = source("ana");
        Long bea = source("Bea");
        article(zoe, "Zoe one", "2026-01-01T00:00:00Z");
        article(ana, "Ana one", "2026-01-01T00:00:00Z");
        article(ana, "Ana two", "2026-01-02T00:00:00Z");
        read(article(bea, "Bea read", "2026-01-03T00:00:00Z"));
        source("Nothing yet");

        String panel = panel(view(null));

        assertThat(panel.indexOf("All")).isLessThan(panel.indexOf("ana"));
        assertThat(panel).contains("(3)");
        assertThat(panel.indexOf("ana")).isLessThan(panel.indexOf("Bea"));
        assertThat(panel.indexOf("Bea")).isLessThan(panel.indexOf("Zoe"));
        assertThat(panel).containsPattern("ana</span>\\s*<span class=\"count\">\\(2\\)");
        assertThat(panel).containsPattern("Zoe</span>\\s*<span class=\"count\">\\(1\\)");
        assertThat(panel).containsPattern("<li class=\"no-unread\">\\s*<a[^>]*>\\s*<span class=\"name\">Bea</span>\\s*<span class=\"count\">\\(0\\)");
        assertThat(panel).doesNotContain("Nothing yet");
        assertThat(panel).contains("href=\"/?source=" + ana + "\"");
    }

    @Test
    void allIsTheDefaultViewAndListsOnlyUnreadArticlesOfEverySourceNewestFirstThenBySourceName() throws Exception {
        Long ana = source("Ana");
        Long bea = source("Bea");
        article(ana, "Old from Ana", "2026-01-01T00:00:00Z");
        article(bea, "Same day from Bea", "2026-01-05T00:00:00Z");
        article(ana, "Same day from Ana", "2026-01-05T00:00:00Z");
        article(bea, "Newest from Bea", "2026-01-09T00:00:00Z");
        read(article(ana, "Already read", "2026-01-10T00:00:00Z"));

        String html = view(null);

        assertThat(html).contains("Latest articles").contains("(4 unread)").doesNotContain("Already read");
        assertThat(html.indexOf("Newest from Bea")).isLessThan(html.indexOf("Same day from Ana"));
        assertThat(html.indexOf("Same day from Ana")).isLessThan(html.indexOf("Same day from Bea"));
        assertThat(html.indexOf("Same day from Bea")).isLessThan(html.indexOf("Old from Ana"));
        assertThat(html).containsPattern("class=\"source-name\"[^>]*>Bea</small>").containsPattern("class=\"source-name\"[^>]*>Ana</small>");
    }

    @Test
    void clickingASourceShowsItsTenNewestArticlesReadOrUnreadAndNothingFromOtherSources() throws Exception {
        Long ana = source("Ana");
        Long bea = source("Bea");
        article(bea, "Only Bea", "2026-01-30T00:00:00Z");
        for (int i = 1; i <= 12; i++) {
            Article article = article(ana, "Post " + String.format("%02d", i), "2026-01-" + String.format("%02d", i) + "T00:00:00Z");
            if (i <= 6) {
                read(article);
            }
        }
        article(ana, "Ancient unread", "2019-01-01T00:00:00Z");

        String html = view(ana);

        assertThat(html).contains("Post 12").contains("Post 03").doesNotContain("Post 02").doesNotContain("Post 01");
        assertThat(html).doesNotContain("Only Bea").contains("Ancient unread");
        assertThat(html.indexOf("Post 12")).isLessThan(html.indexOf("Post 03"));
        assertThat(html).containsPattern("<li class=\"read\">[\\s\\S]*?Post 06").containsPattern("<li class=\"unread\">[\\s\\S]*?Post 07");
    }

    @Test
    void theSelectedEntryIsHighlightedAndTheHeadingShowsTheSourceNameWithItsUnreadCount() throws Exception {
        Long ana = source("Ana");
        Long bea = source("Bea");
        article(ana, "Ana one", "2026-01-01T00:00:00Z");
        article(ana, "Ana two", "2026-01-02T00:00:00Z");
        article(bea, "Bea one", "2026-01-03T00:00:00Z");

        String selected = view(ana);
        assertThat(selected).containsPattern("<h1><span>Ana</span>\\s*<small class=\"count\">\\(2 unread\\)");
        assertThat(panel(selected)).containsPattern("<li class=\"active\">\\s*<a href=\"/\\?source=" + ana + "\"");
        assertThat(panel(selected)).doesNotContainPattern("<li class=\"active\">\\s*<a href=\"/\"");

        assertThat(panel(view(null))).containsPattern("<li class=\"active\">\\s*<a href=\"/\"");
    }

    @Test
    void markingReadOrUnreadAndFetchNowReturnToTheSameViewAndAllDropsTheReadTile() throws Exception {
        Long ana = source("Ana");
        Article article = article(ana, "Mark me", "2026-01-01T00:00:00Z");

        mvc.perform(post("/articles/" + article.getId() + "/read").param("source", String.valueOf(ana)))
                .andExpect(redirectedUrl("/?source=" + ana + "#article-" + article.getId()));
        assertThat(view(ana)).contains("Mark me");
        assertThat(view(null)).doesNotContain("Mark me");

        mvc.perform(post("/articles/" + article.getId() + "/unread").param("source", String.valueOf(ana)))
                .andExpect(redirectedUrl("/?source=" + ana + "#article-" + article.getId()));
        mvc.perform(post("/sources/fetch-now").param("from", "dashboard").param("source", String.valueOf(ana)))
                .andExpect(redirectedUrl("/?source=" + ana));
        assertThat(view(null)).contains("Mark me");

        assertThat(view(ana)).contains("name=\"source\" value=\"" + ana + "\"");
        assertThat(view(null)).doesNotContain("name=\"source\"");
    }

    @Test
    void anUnknownSourceFallsBackToAll() throws Exception {
        Long ana = source("Ana");
        article(ana, "Visible in all", "2026-01-01T00:00:00Z");

        assertThat(view(9999L)).contains("Latest articles").contains("Visible in all");
    }

    @Test
    void onANarrowScreenThePanelIsShownAboveTheTiles() throws Exception {
        mvc.perform(get("/style.css")).andExpect(status().isOk());
        String css = mvc.perform(get("/style.css")).andReturn().getResponse().getContentAsString();

        assertThat(css).containsPattern("@media \\(max-width: 48rem\\)\\s*\\{[^}]*\\.dashboard-layout\\s*\\{\\s*grid-template-columns: 1fr");
        String html = view(null);
        assertThat(html.indexOf("class=\"source-list\"")).isLessThan(html.indexOf("articles-pane"));
    }
}
