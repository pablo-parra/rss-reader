package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.HttpFetcher;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import com.pparra.rssreader.service.ArticleContentService;
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
class DashboardCardsTest {

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private Article article(String title, String image) {
        Long sourceId = sources.findAll().stream().findFirst()
                .orElseGet(() -> sources.save(new Source("https://ana.com", "Ana", SourceType.RSS, null, Instant.now()))).getId();
        Article article = new Article(sourceId, "https://ana.com/" + title.replace(' ', '-'), title, Instant.now(), Instant.now());
        if (image != null) {
            article.useImageIfMissing(image);
        }
        return articles.save(article);
    }

    private String dashboard() throws Exception {
        return mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void articlesWithAnImageAreShownAsCardsWithTheThumbnail() throws Exception {
        article("With picture", "https://ana.com/pic.jpg");

        String html = dashboard();

        assertThat(html).containsPattern("<li class=\"unread\"[\\s\\S]*?<img[^>]*src=\"https://ana.com/pic.jpg\"[^>]*>");
        assertThat(html).contains("referrerpolicy=\"no-referrer\"").contains("loading=\"lazy\"");
        assertThat(html).contains("articles cards");
    }

    @Test
    void articlesWithoutAnImageGetAPlaceholderTileInsteadOfABrokenImage() throws Exception {
        article("No picture", null);

        String html = dashboard();

        assertThat(html).contains("thumb placeholder").doesNotContain("<img");
        assertThat(html).contains("No picture");
    }

    @Test
    void cardsKeepTheTitleLinkToTheReaderTheDateAndTheReadState() throws Exception {
        Article read = article("Already read", "https://ana.com/r.jpg");
        read.markRead(Instant.now());
        articles.save(read);
        Article unread = article("Still unread", "https://ana.com/u.jpg");

        String html = dashboard();

        assertThat(html).contains("href=\"/articles/" + unread.getId() + "\"");
        assertThat(html).containsPattern("<li class=\"read\"[\\s\\S]*?Already read");
        assertThat(html).containsPattern("<li class=\"unread\"[\\s\\S]*?Still unread");
    }

    @Test
    void openingAnArticleAndFetchingShowALoadingIndicator() throws Exception {
        Article article = article("Slow one", null);

        String html = dashboard();

        assertThat(html).containsPattern("<a[^>]*data-loading=\"[^\"]+\"[^>]*href=\"/articles/" + article.getId() + "\"|<a[^>]*href=\"/articles/"
                + article.getId() + "\"[^>]*data-loading=\"[^\"]+\"");
        assertThat(html).containsPattern("<form[^>]*fetch-now[^>]*data-loading=\"[^\"]+\"|<form[^>]*data-loading=\"[^\"]+\"[^>]*fetch-now");
        assertThat(html).contains("/app.js");
    }

    @Test
    void theLoadingScriptAndStylesAreServed() throws Exception {
        mvc.perform(get("/app.js")).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("data-loading")));
        mvc.perform(get("/style.css")).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString(".loading-overlay")));
    }

    @Test
    void theReaderRetryButtonAlsoShowsTheLoadingIndicator() throws Exception {
        Article article = article("Blocked", null);
        article.cacheFailure("Original page: blocked", Instant.now(), ArticleContentService.CONTENT_VERSION);
        articles.save(article);

        mvc.perform(get("/articles/" + article.getId()))
                .andExpect(content().string(Matchers.containsString("retry")))
                .andExpect(content().string(Matchers.matchesRegex("(?s).*<form[^>]*retry[^>]*data-loading=.*|(?s).*<form[^>]*data-loading=[^>]*retry.*")));
    }
}
