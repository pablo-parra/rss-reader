package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.HttpFetcher;
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
class MarkReadUnreadTest {

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private Article article(String title) {
        Long sourceId = sources.findAll().stream().findFirst()
                .orElseGet(() -> sources.save(new Source("https://ana.com", "Ana", SourceType.RSS, null, Instant.now()))).getId();
        return articles.save(new Article(sourceId, "https://ana.com/" + title.replace(' ', '-'), title,
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now()));
    }

    private String dashboard() throws Exception {
        return mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void markingAnUnreadArticleAsReadDoesNotOpenItAndReturnsToItsTileOnTheDashboard() throws Exception {
        Article article = article("Unread one");

        mvc.perform(post("/articles/" + article.getId() + "/read")).andExpect(redirectedUrl("/#article-" + article.getId()));

        Article saved = articles.findById(article.getId()).orElseThrow();
        assertThat(saved.isRead()).isTrue();
        assertThat(saved.getReadAt()).isNotNull();
        assertThat(saved.getContentOrigin()).isNull();
        verifyNoInteractions(http);
        assertThat(dashboard()).contains("id=\"article-" + article.getId() + "\"").contains("(all read)");
    }

    @Test
    void markingAReadArticleAsUnreadRestoresTheUnreadMarkerAndTheUnreadCount() throws Exception {
        Article article = article("Read one");
        article.markRead(Instant.now());
        articles.save(article);
        assertThat(dashboard()).contains("(all read)");

        mvc.perform(post("/articles/" + article.getId() + "/unread")).andExpect(redirectedUrl("/#article-" + article.getId()));

        Article saved = articles.findById(article.getId()).orElseThrow();
        assertThat(saved.isRead()).isFalse();
        assertThat(saved.getReadAt()).isNull();
        assertThat(dashboard()).contains("(1 unread)").containsPattern("<li class=\"unread\"[^>]*>[\\s\\S]*?Read one");
        verifyNoInteractions(http);
    }

    @Test
    void anArticleReadLongAgoComesBackToTheDashboardWhenMarkedUnread() throws Exception {
        Article article = article("Old read");
        article.markRead(Instant.now().minusSeconds(30 * 86_400L));
        articles.save(article);
        assertThat(dashboard()).doesNotContain("Old read");

        mvc.perform(post("/articles/" + article.getId() + "/unread"));

        assertThat(dashboard()).contains("Old read");
    }

    @Test
    void markingTheSameStateTwiceIsHarmless() throws Exception {
        Article article = article("Twice");

        mvc.perform(post("/articles/" + article.getId() + "/read"));
        Instant firstReadAt = articles.findById(article.getId()).orElseThrow().getReadAt();
        mvc.perform(post("/articles/" + article.getId() + "/read")).andExpect(status().is3xxRedirection());
        assertThat(articles.findById(article.getId()).orElseThrow().getReadAt()).isEqualTo(firstReadAt);

        mvc.perform(post("/articles/" + article.getId() + "/unread"));
        mvc.perform(post("/articles/" + article.getId() + "/unread")).andExpect(status().is3xxRedirection());
        assertThat(articles.findById(article.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void unknownArticlesGiveNotFoundForBothActions() throws Exception {
        mvc.perform(post("/articles/9999/read")).andExpect(status().isNotFound());
        mvc.perform(post("/articles/9999/unread")).andExpect(status().isNotFound());
    }

    @Test
    void changingTheReadStateRequiresAPostAndNeverHappensOnAGet() throws Exception {
        Article article = article("Safe");

        mvc.perform(get("/articles/" + article.getId() + "/read")).andExpect(status().is4xxClientError());

        assertThat(articles.findById(article.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void eachTileOffersOnlyTheActionThatMakesSense() throws Exception {
        Article unread = article("Is unread");
        Article read = article("Is read");
        read.markRead(Instant.now());
        articles.save(read);

        String html = dashboard();

        assertThat(html).contains("action=\"/articles/" + unread.getId() + "/read\"").contains("Mark as read")
                .doesNotContain("/articles/" + unread.getId() + "/unread");
        assertThat(html).contains("action=\"/articles/" + read.getId() + "/unread\"").contains("Mark as unread")
                .doesNotContain("/articles/" + read.getId() + "/read\"");
    }

    @Test
    void theTileMenuIsRevealedOnHoverAndOnKeyboardFocus() throws Exception {
        mvc.perform(get("/style.css")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(".card-menu")))
                .andExpect(content().string(Matchers.containsString(":hover")))
                .andExpect(content().string(Matchers.containsString(":focus-within")));
    }
}
