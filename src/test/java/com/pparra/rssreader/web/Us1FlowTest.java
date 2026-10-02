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
import com.pparra.rssreader.domain.ContentOrigin;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.HttpFetcher;
import com.pparra.rssreader.fetch.Pages;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.io.IOException;
import java.time.Instant;
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
class Us1FlowTest {

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    @Test
    void addListAndRemoveSource() throws Exception {
        when(http.get("https://blog.example.com/feed")).thenReturn(
                Pages.ok("application/rss+xml", "<rss version=\"2.0\"></rss>", "https://blog.example.com/feed"));

        mvc.perform(post("/sources").param("url", "blog.example.com/feed").param("name", "Ana"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Added \"Ana\" (RSS)"));

        mvc.perform(get("/sources")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Ana")));

        Long id = sources.findAll().get(0).getId();
        mvc.perform(post("/sources/" + id + "/delete")).andExpect(redirectedUrl("/sources"));
        assertThat(sources.count()).isZero();
    }

    @Test
    void blockedSourceIsAddedButFlaggedUnsupported() throws Exception {
        when(http.get(anyString())).thenThrow(new IOException("blocked"));

        mvc.perform(post("/sources").param("url", "https://walled.example.com"))
                .andExpect(flash().attributeExists("warning"));

        assertThat(sources.findAll()).singleElement().satisfies(s -> {
            assertThat(s.getType()).isEqualTo(SourceType.UNSUPPORTED);
            assertThat(s.getName()).isEqualTo("walled.example.com");
        });
    }

    @Test
    void duplicateAndInvalidUrlsAreRejected() throws Exception {
        when(http.get(anyString())).thenReturn(Pages.html("<html></html>", "https://a.com"));

        mvc.perform(post("/sources").param("url", "https://a.com"));
        mvc.perform(post("/sources").param("url", "https://a.com"))
                .andExpect(flash().attribute("error", "This source is already in your list"));
        mvc.perform(post("/sources").param("url", "ftp://a.com"))
                .andExpect(flash().attribute("error", "Only http and https URLs are supported"));

        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void deletingSourceRemovesItsArticles() throws Exception {
        when(http.get(anyString())).thenReturn(Pages.html("<html></html>", "https://a.com"));
        mvc.perform(post("/sources").param("url", "https://a.com"));
        Long sourceId = sources.findAll().get(0).getId();
        articles.save(new Article(sourceId, "https://a.com/p1", "P1", null, Instant.now()));

        mvc.perform(post("/sources/" + sourceId + "/delete"));

        assertThat(articles.count()).isZero();
    }

    @Test
    void readerShowsArchiveSnapshotTransparentlyAndMarksRead() throws Exception {
        Long sourceId = sources.save(new com.pparra.rssreader.domain.Source(
                "https://news.com", "News", SourceType.SCRAPE, null, Instant.now())).getId();
        Article article = articles.save(new Article(sourceId, "https://news.com/story", "Story", null, Instant.now()));
        when(http.get("https://news.com/story")).thenReturn(Pages.status(403, "paywall", "https://news.com/story"));
        when(http.get("https://archive.ph/newest/https://news.com/story")).thenReturn(
                Pages.html(Pages.articleHtml("Full archived text of the story.", 40), "https://archive.ph/Xy12z"));

        mvc.perform(get("/articles/" + article.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Full archived text")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("archive.ph snapshot")));

        Article saved = articles.findById(article.getId()).orElseThrow();
        assertThat(saved.isRead()).isTrue();
        assertThat(saved.getContentOrigin()).isEqualTo(ContentOrigin.ARCHIVE);
        assertThat(saved.getContentFetchedAt()).isNotNull();
    }

    @Test
    void readerDegradesToLinksWhenNothingReadable() throws Exception {
        Long sourceId = sources.save(new com.pparra.rssreader.domain.Source(
                "https://news.com", "News", SourceType.SCRAPE, null, Instant.now())).getId();
        Article article = articles.save(new Article(sourceId, "https://news.com/story", "Story", null, Instant.now()));
        when(http.get(anyString())).thenThrow(new IOException("down"));

        mvc.perform(get("/articles/" + article.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("could not be loaded")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("archive.ph/newest/https://news.com/story")));
    }

    @Test
    void unknownArticleIs404() throws Exception {
        mvc.perform(get("/articles/9999")).andExpect(status().isNotFound());
    }
}
