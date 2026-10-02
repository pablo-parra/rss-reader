package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * One test per acceptance criterion of US-3. Criteria already covered adequately by Us3FlowTest are not repeated:
 * - grouping / newest first / most recent author on top: listsUnreadArticlesGroupedByAuthorNewestFirst
 * - unread vs read look, 7-day window, counts: openedArticleStaysOnTheDashboardAsReadAndTheUnreadCountDrops,
 *   articlesReadLongAgoOrNeverOpenedAreHidden
 * - read without moving in the list: readingKeepsTheArticleInPlaceInsteadOfReordering
 * - dashboard "Fetch now" returning with summary: fetchNowFromTheDashboardReturnsToTheDashboardWithSummary,
 *   dashboardRendersTheFlashMessageAfterFetching
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Us3AcceptanceCriteriaTest {

    private static final String POST_URL = "https://blog.example.com/post";
    private static final String BODY = "The genuine paragraph written by the author about the topic of the post. ".repeat(6);

    private static final String NOISY_PAGE = """
            <html><head><title>Post</title></head><body>
            <header class="site-header"><a href="/">SITEHEADER-LOGO</a><a href="/about">SITEHEADER-ABOUT</a></header>
            <nav class="main-menu"><a href="/x">MENU-ENTRY</a></nav>
            <div class="layout">
              <article class="post">
                <h1>Heading inside the page</h1>
                <div class="byline">BYLINE-AUTHOR-NAME</div>
                <div class="share-buttons"><a href="https://twitter.com/share">SHARE-TWEET</a><a href="https://facebook.com/sharer">SHARE-FACEBOOK</a></div>
                <div class="ad-banner">ADVERT-BUY-NOW</div>
                <div class="post-content">
                  <p>%1$s</p>
                  <figure><img src="/img/post-photo.jpg" alt="Post photo" width="800" height="600"></figure>
                  <p>%1$s</p>
                </div>
                <div class="post-tags">TAGS-LABEL <a href="/t/a">TAG-ALPHA</a></div>
                <div class="author-bio"><img src="/img/author-big.png" alt="Author"><p>AUTHORBOX-TEXT</p></div>
                <section class="related-posts"><h3>Related articles</h3>
                  <ul><li><a href="/p/1"><img src="/img/related-1.jpg" alt="rel">RELATED-STORY-ONE</a></li>
                      <li><a href="/p/2">RELATED-STORY-TWO</a></li></ul></section>
                <form class="newsletter"><input type="email"><button>NEWSLETTER-SUBSCRIBE</button></form>
                <section id="comments"><h3>COMMENTS-HEADING</h3><p>COMMENT-BODY-TEXT</p></section>
              </article>
              <aside class="sidebar"><h3>Popular</h3><a href="/p/9">SIDEBAR-STORY</a></aside>
            </div>
            <footer><a href="/privacy">FOOTER-PRIVACY</a></footer>
            </body></html>""".formatted(BODY);

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;
    @MockBean HttpFetcher http;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private Article storedPost() {
        Long sourceId = sources.save(new Source("https://blog.example.com", "Blog", SourceType.SCRAPE, null, Instant.now())).getId();
        return articles.save(new Article(sourceId, POST_URL, "Stored Post Title", Instant.parse("2026-01-02T00:00:00Z"), Instant.now()));
    }

    private String reader(Article article) throws Exception {
        return mvc.perform(get("/articles/" + article.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void clickingAnArticleOpensItInTheAppsReaderViewAndMarksItAsRead() throws Exception {
        Article article = storedPost();
        when(http.get(POST_URL)).thenReturn(Pages.html(NOISY_PAGE, POST_URL));

        assertThat(mvc.perform(get("/")).andReturn().getResponse().getContentAsString())
                .contains("href=\"/articles/" + article.getId() + "\"");
        assertThat(reader(article)).contains("Stored Post Title");

        assertThat(articles.findById(article.getId()).orElseThrow()).satisfies(saved -> {
            assertThat(saved.isRead()).isTrue();
            assertThat(saved.getReadAt()).isNotNull();
        });
    }

    @Test
    void openingAnArticleThatDoesNotExistGivesNotFoundAndChangesNothing() throws Exception {
        Article existing = storedPost();

        mvc.perform(get("/articles/" + (existing.getId() + 1000))).andExpect(status().isNotFound());

        assertThat(articles.findById(existing.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void theReaderViewShowsOnlyTheTitleTheArticleTextAndTheImagesThatBelongToThePost() throws Exception {
        Article article = storedPost();
        when(http.get(POST_URL)).thenReturn(Pages.html(NOISY_PAGE, POST_URL));

        String page = reader(article);

        assertThat(page).contains("Stored Post Title");
        assertThat(page).contains("The genuine paragraph written by the author");
        assertThat(page).contains("https://blog.example.com/img/post-photo.jpg");
    }

    @Test
    void theReaderViewLeavesOutRelatedArticlesShareButtonsNewsletterBoxesCommentsAdsAuthorBoxesTagsAndMenus() throws Exception {
        Article article = storedPost();
        when(http.get(POST_URL)).thenReturn(Pages.html(NOISY_PAGE, POST_URL));

        String page = reader(article);

        assertThat(page)
                .doesNotContain("RELATED-STORY").doesNotContain("related-1.jpg")
                .doesNotContain("SHARE-TWEET").doesNotContain("SHARE-FACEBOOK")
                .doesNotContain("NEWSLETTER-SUBSCRIBE")
                .doesNotContain("COMMENTS-HEADING").doesNotContain("COMMENT-BODY-TEXT")
                .doesNotContain("ADVERT-BUY-NOW")
                .doesNotContain("AUTHORBOX-TEXT").doesNotContain("author-big.png")
                .doesNotContain("TAG-ALPHA").doesNotContain("TAGS-LABEL")
                .doesNotContain("MENU-ENTRY").doesNotContain("SITEHEADER-LOGO").doesNotContain("SIDEBAR-STORY")
                .doesNotContain("FOOTER-PRIVACY").doesNotContain("BYLINE-AUTHOR-NAME");
    }

    @Test
    void theReaderViewHasALinkToTheOriginalPostAtTheTopAndAtTheBottom() throws Exception {
        Article article = storedPost();
        when(http.get(POST_URL)).thenReturn(Pages.html(NOISY_PAGE, POST_URL));

        String page = reader(article);

        String link = "href=\"" + POST_URL + "\"";
        int firstLink = page.indexOf(link);
        int lastLink = page.lastIndexOf(link);
        int firstText = page.indexOf("The genuine paragraph");
        int lastText = page.lastIndexOf("The genuine paragraph");
        assertThat(firstLink).isNotNegative().isLessThan(firstText);
        assertThat(lastLink).isGreaterThan(lastText);
    }

    @Test
    void theReaderViewKeepsTheOriginalPostLinksEvenWhenTheContentCannotBeLoaded() throws Exception {
        Article article = storedPost();
        when(http.get(org.mockito.ArgumentMatchers.anyString())).thenThrow(new java.io.IOException("down"));

        assertThat(reader(article)).contains("href=\"" + POST_URL + "\"");
    }
}
