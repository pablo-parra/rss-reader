package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** "Mark all as read" in a source view (US-6). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MarkAllReadTest {

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

    private Article article(Long sourceId, String title) {
        return articles.save(new Article(sourceId, "https://x.com/" + title.replace(' ', '-'), title,
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now()));
    }

    private String view(Long sourceId) throws Exception {
        var request = get("/");
        if (sourceId != null) {
            request = request.param("source", String.valueOf(sourceId));
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void marksEveryUnreadArticleOfThatSourceAsReadAndOnlyThatSource() throws Exception {
        Long ana = source("Ana");
        Long bea = source("Bea");
        Article a1 = article(ana, "Ana one");
        Article a2 = article(ana, "Ana two");
        Article b1 = article(bea, "Bea one");

        mvc.perform(post("/articles/read-all").param("source", String.valueOf(ana)))
                .andExpect(redirectedUrl("/?source=" + ana))
                .andExpect(flash().attribute("message", "Marked 2 articles of \"Ana\" as read"));

        assertThat(articles.findById(a1.getId()).orElseThrow()).satisfies(a -> {
            assertThat(a.isRead()).isTrue();
            assertThat(a.getReadAt()).isNotNull();
        });
        assertThat(articles.findById(a2.getId()).orElseThrow().isRead()).isTrue();
        assertThat(articles.findById(b1.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void articlesAlreadyReadKeepTheirOriginalReadTimestamp() throws Exception {
        Long ana = source("Ana");
        Article earlier = article(ana, "Read earlier");
        Instant readAt = Instant.parse("2026-03-01T00:00:00Z");
        earlier.markRead(readAt);
        articles.save(earlier);
        article(ana, "Still unread");

        mvc.perform(post("/articles/read-all").param("source", String.valueOf(ana)))
                .andExpect(flash().attribute("message", "Marked 1 article of \"Ana\" as read"));

        assertThat(articles.findById(earlier.getId()).orElseThrow().getReadAt()).isEqualTo(readAt);
    }

    @Test
    void theArticlesLeaveAllButStayInTheSourceViewAsReadAndTheCountsDropToZero() throws Exception {
        Long ana = source("Ana");
        article(ana, "Gone from all");
        mvc.perform(post("/articles/read-all").param("source", String.valueOf(ana)));

        assertThat(view(null)).doesNotContain("Gone from all").contains("all caught up");
        String sourceView = view(ana);
        assertThat(sourceView).contains("Gone from all").contains("(all read)");
        assertThat(sourceView).containsPattern("<li class=\"read\">[\\s\\S]*?Gone from all");
    }

    @Test
    void whenNothingIsUnreadItSaysSoAndChangesNothing() throws Exception {
        Long ana = source("Ana");
        Article read = article(ana, "Done");
        read.markRead(Instant.now());
        articles.save(read);

        mvc.perform(post("/articles/read-all").param("source", String.valueOf(ana)))
                .andExpect(flash().attribute("message", "Nothing to mark: every article of \"Ana\" was already read"));
    }

    @Test
    void unknownSourceIsNotFoundAndASourceIsRequired() throws Exception {
        mvc.perform(post("/articles/read-all").param("source", "9999")).andExpect(status().isNotFound());
        mvc.perform(post("/articles/read-all")).andExpect(status().isBadRequest());
    }

    @Test
    void itNeverHappensOnAGet() throws Exception {
        Long ana = source("Ana");
        Article article = article(ana, "Safe");

        mvc.perform(get("/articles/read-all").param("source", String.valueOf(ana))).andExpect(status().is4xxClientError());

        assertThat(articles.findById(article.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void theButtonIsOnlyInASourceViewWithUnreadArticlesAndAsksForConfirmation() throws Exception {
        Long ana = source("Ana");
        Long bea = source("Bea");
        article(ana, "One");
        article(ana, "Two");
        Article done = article(bea, "Done");
        done.markRead(Instant.now());
        articles.save(done);

        String withUnread = view(ana);
        assertThat(withUnread).contains("action=\"/articles/read-all\"").contains("Mark all as read (2)");
        assertThat(withUnread).contains("data-confirm=\"Mark all 2 unread articles of Ana as read?\"");
        assertThat(withUnread).contains("name=\"source\" value=\"" + ana + "\"");

        assertThat(view(null)).doesNotContain("/articles/read-all");
        assertThat(view(bea)).doesNotContain("/articles/read-all");
    }

    @Test
    void theScriptAsksForConfirmationAndCancelsTheSubmitWhenDeclined() throws Exception {
        mvc.perform(get("/app.js")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-confirm")))
                .andExpect(content().string(Matchers.containsString("window.confirm")))
                .andExpect(content().string(Matchers.containsString("preventDefault")));
    }

    @Test
    void theSourceTitleAndTheButtonShareTheHeaderOfTheTilesPanelAboveTheTiles() throws Exception {
        Long ana = source("Ana");
        article(ana, "One");

        String html = view(ana);

        int panel = html.indexOf("class=\"articles-pane\"");
        int button = html.indexOf("Mark all as read (1)");
        int tiles = html.indexOf("class=\"articles cards\"");
        int title = html.indexOf("<h1>");
        assertThat(panel).isNotNegative();
        assertThat(title).isGreaterThan(panel).isLessThan(button);
        assertThat(button).isGreaterThan(panel).isLessThan(tiles);
        assertThat(html.indexOf("class=\"pane-header\"")).isGreaterThan(panel).isLessThan(title);
        assertThat(html.indexOf("class=\"source-list\"")).isLessThan(button);
    }
}
