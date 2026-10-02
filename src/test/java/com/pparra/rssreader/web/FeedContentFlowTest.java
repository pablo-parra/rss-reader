package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
import java.time.Instant;
import java.util.Map;
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
class FeedContentFlowTest {

    private static final String FEED_URL = "https://ana.com/feed";
    private static final String POST = "https://ana.com/post";
    private static final String BODY = "Full article text delivered inside the feed entry. ".repeat(20);

    private static String feed(String contentEncoded) {
        return """
                <?xml version="1.0"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Ana</title>
                  <item><title>Post</title><link>%s</link><description>teaser</description>%s</item>
                </channel></rss>""".formatted(POST, contentEncoded);
    }

    private static final FetchedPage CLOUDFLARE = new FetchedPage(403, "text/html", "<title>Just a moment...</title>", POST,
            Map.of("cf-mitigated", "challenge"));

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
        sources.save(new Source(FEED_URL, "Ana", SourceType.RSS, null, Instant.now()));
    }

    private Long fetchAndGetArticleId(String contentEncoded) throws Exception {
        when(http.get(FEED_URL)).thenReturn(Pages.ok("application/rss+xml", feed(contentEncoded), FEED_URL));
        mvc.perform(post("/sources/fetch-now"));
        return articles.findAll().get(0).getId();
    }

    @Test
    void articleBehindCloudflareIsReadFromTheFeedContentWithoutTouchingThePage() throws Exception {
        Long id = fetchAndGetArticleId("<content:encoded><![CDATA[<p>" + BODY + "</p><img src=\"/pic.jpg\" alt=\"pic\">]]></content:encoded>");
        when(http.get(POST)).thenReturn(CLOUDFLARE);

        String page = mvc.perform(get("/articles/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Full article text delivered inside the feed entry").contains("https://ana.com/pic.jpg");
        assertThat(page).doesNotContain("could not be loaded");
        verify(http, never()).get(POST);
        verify(http, never()).get(org.mockito.ArgumentMatchers.startsWith("https://archive.ph"));
        assertThat(articles.findById(id).orElseThrow().getContentOrigin()).isEqualTo(ContentOrigin.FEED);
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getContentAsString()).doesNotContain("failed-info");
    }

    @Test
    void feedWithOnlyAnExcerptStillFailsGracefullyWhenThePageIsBlocked() throws Exception {
        Long id = fetchAndGetArticleId("");
        when(http.get(POST)).thenReturn(CLOUDFLARE);
        when(http.get("https://archive.ph/newest/" + POST)).thenReturn(Pages.status(404, "No results", "https://archive.ph/newest/" + POST));

        String page = mvc.perform(get("/articles/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("could not be loaded").contains("bot challenge");
    }

    @Test
    void anAlreadyStoredFailedArticleIsFixedByTheNextFetchThatCarriesFullContent() throws Exception {
        Long id = fetchAndGetArticleId("");
        when(http.get(POST)).thenReturn(CLOUDFLARE);
        when(http.get(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (url.equals(FEED_URL)) {
                return Pages.ok("application/rss+xml", feed("<content:encoded><![CDATA[<p>" + BODY + "</p>]]></content:encoded>"), FEED_URL);
            }
            return url.equals(POST) ? CLOUDFLARE : Pages.status(404, "No results", url);
        });
        mvc.perform(get("/articles/" + id));
        assertThat(articles.findById(id).orElseThrow().getContentOrigin()).isEqualTo(ContentOrigin.UNAVAILABLE);

        mvc.perform(post("/sources/fetch-now"));
        String page = mvc.perform(get("/articles/" + id)).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Full article text delivered inside the feed entry");
        assertThat(articles.count()).isEqualTo(1);
        Article saved = articles.findById(id).orElseThrow();
        assertThat(saved.getContentOrigin()).isEqualTo(ContentOrigin.FEED);
        assertThat(saved.getContentFailureReason()).isNull();
    }
}
