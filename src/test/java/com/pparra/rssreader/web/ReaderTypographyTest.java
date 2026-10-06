package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.HttpFetcher;
import com.pparra.rssreader.fetch.Pages;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The reading font applies to the post's text only, not to the title or the metadata line. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReaderTypographyTest {

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
    void thePostTextIsWrappedInTheReadingFontContainerAndTheTitleAndMetadataAreOutsideIt() throws Exception {
        Long sourceId = sources.save(new Source("https://blog.com", "Blog", SourceType.RSS, null, Instant.now())).getId();
        Article article = articles.save(new Article(sourceId, "https://blog.com/p", "The title", Instant.now(), Instant.now()));
        when(http.get(anyString())).thenReturn(Pages.html(Pages.articleHtml("A readable paragraph of the post text.", 40), "https://blog.com/p"));

        String page = mvc.perform(get("/articles/" + article.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int container = page.indexOf("class=\"reader-content\"");
        assertThat(container).isNotNegative();
        assertThat(page.indexOf("A readable paragraph of the post text.")).isGreaterThan(container);
        assertThat(page.indexOf("<h1")).isLessThan(container);
        assertThat(page.indexOf("class=\"reader-meta\"")).isLessThan(container);
        assertThat(page.indexOf("The title")).isLessThan(container);
    }

    @Test
    void theStylesheetGivesTheReadingContainerASerifStackAndComfortableSpacing() throws Exception {
        String css = mvc.perform(get("/style.css")).andReturn().getResponse().getContentAsString();

        assertThat(css).containsPattern("\\.reader-content\\s*\\{[^}]*font-family:\\s*Charter[^}]*Georgia[^}]*serif");
        assertThat(css).containsPattern("\\.reader-content\\s*\\{[^}]*line-height:\\s*1\\.6");
        assertThat(css).containsPattern("\\.reader-content pre[^{]*\\{[^}]*monospace");
    }
}
