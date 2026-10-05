package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentExtractorTest {

    private final ContentExtractor extractor = new ContentExtractor();

    @Test
    void extractsArticleAndDropsChrome() {
        ExtractedContent content = extractor.extract(Pages.articleHtml("Some long paragraph of text here.", 20), "https://a.com/x");

        assertThat(content.html()).contains("Some long paragraph").doesNotContain("menu").doesNotContain("footer");
        assertThat(content.textLength()).isGreaterThan(500);
        assertThat(content.declaredPaywalled()).isFalse();
    }

    @Test
    void sanitizesScriptsAndEventHandlers() {
        String html = "<html><body><article><p onclick=\"evil()\">Hello " + "x".repeat(300)
                + "</p><script>alert(1)</script><a href=\"javascript:alert(1)\">bad</a></article></body></html>";

        String clean = extractor.extract(html, "https://a.com").html();

        assertThat(clean).doesNotContain("script").doesNotContain("onclick").doesNotContain("javascript:");
    }

    @Test
    void forcesSafeLinkAttributes() {
        String html = "<html><body><article><p>" + "x".repeat(300) + "<a href=\"/rel\">link</a></p></article></body></html>";

        String clean = extractor.extract(html, "https://a.com").html();

        assertThat(clean).contains("href=\"https://a.com/rel\"").contains("noopener");
    }

    @Test
    void detectsDeclaredPaywallInJsonLd() {
        String html = "<html><head><script type=\"application/ld+json\">{\"isAccessibleForFree\": \"False\"}</script></head>"
                + "<body><article><p>" + "x".repeat(300) + "</p></article></body></html>";

        assertThat(extractor.extract(html, "https://a.com").declaredPaywalled()).isTrue();
    }

    @Test
    void detectsPaywallMarkers() {
        String html = "<html><body><article><p>Teaser. Subscribe to continue reading this story. "
                + "x".repeat(250) + "</p></article></body></html>";

        assertThat(extractor.extract(html, "https://a.com").paywallMarkers()).isTrue();
    }

    @Test
    void fallsBackToDenseParagraphContainerWithoutArticleTag() {
        String html = "<html><body><div id=\"menu\"><a>x</a></div><div id=\"story\">"
                + "<p>" + "real story text ".repeat(30) + "</p></div></body></html>";

        assertThat(extractor.extract(html, "https://a.com").html()).contains("real story text");
    }

    private static final String BODY = "This is a genuinely long paragraph of the post written by the author. ".repeat(6);

    private static final String NOISY_POST = """
            <html><head><title>Post</title></head><body>
            <header class="site-header"><a href="/">My Blog</a><a href="/about">About</a></header>
            <div class="layout">
              <article class="post">
                <h1>The Post Title</h1>
                <div class="byline">By Ana Perez <time>2026-01-01</time></div>
                <div class="share-buttons"><a href="https://twitter.com/share">Tweet</a><a href="https://facebook.com/sharer">Facebook</a></div>
                <div class="post-content">
                  <p>%1$s</p>
                  <figure><img src="/img/photo-1.jpg" alt="First photo" width="800" height="600"><figcaption>A caption</figcaption></figure>
                  <p>%1$s</p>
                  <img data-src="/img/lazy-2.jpg" src="data:image/gif;base64,R0lGOD" alt="Lazy photo">
                  <img src="https://stats.example.com/pixel.gif" alt="">
                  <img class="avatar" src="/img/ana.png" alt="Ana">
                  <img src="/img/tiny.png" width="16" height="16" alt="">
                  <p>%1$s</p>
                </div>
                <div class="post-tags">Tags: <a href="/t/a">alpha</a> <a href="/t/b">beta</a></div>
                <div class="author-bio"><img src="/img/ana-big.png" alt="Ana"><p>Ana writes about many things.</p></div>
                <section class="related-posts">
                  <h3>Related articles</h3>
                  <ul><li><a href="/p/1"><img src="/img/rel1.jpg" alt="rel1">Another post worth reading</a></li>
                      <li><a href="/p/2"><img src="/img/rel2.jpg" alt="rel2">Yet another interesting post</a></li></ul>
                </section>
                <form class="newsletter"><input type="email"><button>Subscribe to our newsletter</button></form>
                <section id="comments"><h3>3 comments</h3><p>Great post, thanks!</p></section>
              </article>
              <aside class="sidebar"><h3>Popular</h3><a href="/p/9">Sidebar story</a></aside>
            </div>
            <footer><a href="/privacy">Privacy</a></footer>
            </body></html>""".formatted(BODY);

    private String extractNoisyPost() {
        return extractor.extract(NOISY_POST, "https://blog.com/post").html();
    }

    @Test
    void keepsOnlyTheTextAndImagesOfThePost() {
        String html = extractNoisyPost();

        assertThat(html).contains("genuinely long paragraph").contains("A caption");
        assertThat(html).contains("https://blog.com/img/photo-1.jpg").contains("alt=\"First photo\"");
        assertThat(html).contains("https://blog.com/img/lazy-2.jpg");
    }

    @Test
    void dropsShareBarRelatedPostsNewsletterCommentsBioTagsAndChrome() {
        String html = extractNoisyPost();

        assertThat(html).doesNotContain("Tweet").doesNotContain("Facebook")
                .doesNotContain("Related articles").doesNotContain("Another post worth reading")
                .doesNotContain("rel1.jpg").doesNotContain("Subscribe to our newsletter")
                .doesNotContain("comments").doesNotContain("Great post")
                .doesNotContain("Ana writes").doesNotContain("ana-big.png")
                .doesNotContain("alpha").doesNotContain("Tags:")
                .doesNotContain("Sidebar story").doesNotContain("Privacy").doesNotContain("My Blog")
                .doesNotContain("By Ana Perez");
    }

    @Test
    void dropsTitleHeadingBecauseTheReaderShowsItOnce() {
        assertThat(extractNoisyPost()).doesNotContain("The Post Title").doesNotContain("<h1");
    }

    @Test
    void dropsTrackingPixelsAvatarsAndTinyIcons() {
        String html = extractNoisyPost();

        assertThat(html).doesNotContain("pixel.gif").doesNotContain("ana.png").doesNotContain("tiny.png");
        assertThat(html.split("<img", -1).length - 1).isEqualTo(2);
    }

    @Test
    void outputImagesAreSafeToHotlink() {
        String html = extractNoisyPost();

        assertThat(html).contains("referrerpolicy=\"no-referrer\"").contains("loading=\"lazy\"");
    }

    @Test
    void resolvesLazyImagesFromSrcsetAndPictureSources() {
        String html = "<html><body><article><p>" + BODY + "</p>"
                + "<img src=\"data:image/gif;base64,AAAA\" srcset=\"/small.jpg 400w, /large.jpg 1200w\" alt=\"a\">"
                + "<picture><source srcset=\"/pic-wide.jpg 1x\"><img alt=\"b\"></picture>"
                + "</article></body></html>";

        String clean = extractor.extract(html, "https://blog.com/p").html();

        assertThat(clean).contains("https://blog.com/large.jpg").contains("https://blog.com/pic-wide.jpg");
    }

    @Test
    void cutsEverythingAfterARelatedHeadingEvenWithoutTellTaleClasses() {
        String html = "<html><body><article><p>" + BODY + "</p><p>Closing words of the post.</p>"
                + "<h3>Noticias relacionadas</h3><p>Otro titular</p><p>Y otro titular más</p></article></body></html>";

        String clean = extractor.extract(html, "https://blog.com/p").html();

        assertThat(clean).contains("Closing words").doesNotContain("Noticias relacionadas").doesNotContain("Otro titular");
    }

    @Test
    void removesLinkHeavyBlocksSuchAsRecommendationLists() {
        String html = "<html><body><article><p>" + BODY + "</p>"
                + "<div><a href=\"/a\">Story about one thing</a> <a href=\"/b\">Story about another</a> <a href=\"/c\">Story about a third</a></div>"
                + "</article></body></html>";

        assertThat(extractor.extract(html, "https://blog.com/p").html()).doesNotContain("Story about");
    }

    @Test
    void keepsInlineLinksInsideNormalParagraphs() {
        String html = "<html><body><article><p>" + BODY + " See <a href=\"/ref\">this reference</a> and <a href=\"/ref2\">another one</a>.</p></article></body></html>";

        assertThat(extractor.extract(html, "https://blog.com/p").html()).contains("this reference").contains("another one");
    }

    @Test
    void neverStripsTheWholePostBecauseAWrapperHasANoisyClassName() {
        String html = "<html><body><article class=\"social-article\"><div class=\"comment-able-body\"><p>" + BODY + "</p><p>" + BODY + "</p></div></article></body></html>";

        assertThat(extractor.extract(html, "https://blog.com/p").html()).contains("genuinely long paragraph");
    }

    @Test
    void prefersTheArticleBodyContainerOverTheSurroundingArticleFurniture() {
        String html = "<html><body><article><div class=\"deck\">A standfirst that is not the body</div>"
                + "<div class=\"entry-content\"><p>" + BODY + "</p></div>"
                + "<div class=\"more\">Stuff after the body</div></article></body></html>";

        String clean = extractor.extract(html, "https://blog.com/p").html();

        assertThat(clean).contains("genuinely long paragraph").doesNotContain("standfirst").doesNotContain("Stuff after");
    }

    @Test
    void paywallMarkersInsideRemovedBlocksStillCountAndTextLengthIsMeasuredAfterCleaning() {
        String teaser = "Short teaser of the story that continues behind a wall of payment. ".repeat(3);
        String html = "<html><body><article><p>" + teaser + "</p>"
                + "<div class=\"subscribe-wall\">Subscribe to continue reading this story</div></article></body></html>";

        ExtractedContent content = extractor.extract(html, "https://blog.com/p");

        assertThat(content.paywallMarkers()).isTrue();
        assertThat(content.html()).doesNotContain("Subscribe to continue");
        assertThat(content.textLength()).isLessThan(teaser.length() + 5);
    }

    @Test
    void removesEmptyLeftoverContainers() {
        String clean = extractNoisyPost();

        assertThat(clean).doesNotContain("<div></div>").doesNotContain("<ul></ul>").doesNotContain("<section></section>");
    }

    private static final String SVG_PLACEHOLDER =
            "data:image/svg+xml,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20width%3D%27529%27%20height%3D%27359%27%3E%3C%2Fsvg%3E";

    @Test
    void resolvesLazyImagesWhoseRealUrlIsInDataOrigSrcBehindASvgPlaceholder() {
        String html = "<html><body><article><p>" + BODY + "</p>"
                + "<img class=\"lazyload alignleft\" src=\"" + SVG_PLACEHOLDER + "\" "
                + "data-orig-src=\"http://blog.com/wp-content/uploads/photo.jpg\" alt=\"Lazy photo\" width=\"529\" height=\"359\">"
                + "</article></body></html>";

        String clean = extractor.extract(html, "https://blog.com/p").html();

        assertThat(clean).contains("http://blog.com/wp-content/uploads/photo.jpg").doesNotContain("data:image");
    }

    @Test
    void aPlaceholderDataUriInSrcsetIsNeverUsedAsTheImageUrl() {
        String html = "<html><body><article><p>" + BODY + "</p>"
                + "<img src=\"" + SVG_PLACEHOLDER + "\" srcset=\"" + SVG_PLACEHOLDER + "\" alt=\"placeholder only\">"
                + "<img src=\"" + SVG_PLACEHOLDER + "\" srcset=\"" + SVG_PLACEHOLDER + "\" "
                + "data-srcset=\"/img/small.jpg 400w, /img/big.jpg 1200w\" alt=\"real\">"
                + "</article></body></html>";

        String clean = extractor.extract(html, "https://blog.com/p").html();

        assertThat(clean).doesNotContain("placeholder only").doesNotContain("%3Csvg").doesNotContain("data:image");
        assertThat(clean).contains("https://blog.com/img/big.jpg");
    }

    private static final String FEATURED_PAGE = "<html><body><header><img src=\"/logo.png\" width=\"300\" height=\"80\" alt=\"Site\"></header>"
            + "<article><div class=\"hero\"><img class=\"attachment-full wp-post-image lazyload\" width=\"1512\" height=\"1030\" "
            + "src=\"data:image/svg+xml,%3Csvg%3E%3C%2Fsvg%3E\" data-orig-src=\"https://blog.com/uploads/featured.jpg\" alt=\"Featured\"></div>"
            + "<div class=\"post-content\"><p>{BODY}</p><p>{BODY}</p></div>"
            + "<section class=\"related-posts\"><img class=\"wp-post-image\" src=\"/uploads/related-thumb.jpg\" width=\"500\" height=\"383\"></section>"
            + "</article></body></html>";

    @Test
    void featuredImageAboveTheBodyIsShownAtTheTopOfTheReaderContent() {
        String clean = extractor.extract(FEATURED_PAGE.replace("{BODY}", BODY), "https://blog.com/p").html();

        assertThat(clean).contains("https://blog.com/uploads/featured.jpg").doesNotContain("data:image");
        assertThat(clean.indexOf("featured.jpg")).isLessThan(clean.indexOf("genuinely long paragraph"));
    }

    @Test
    void siteLogosAndRelatedPostThumbnailsAreNotTreatedAsTheFeaturedImage() {
        String clean = extractor.extract(FEATURED_PAGE.replace("{BODY}", BODY), "https://blog.com/p").html();

        assertThat(clean).doesNotContain("logo.png").doesNotContain("related-thumb.jpg");
    }

    @Test
    void featuredImageIsNotRepeatedWhenTheBodyAlreadyContainsIt() {
        String html = "<html><body><article><img class=\"wp-post-image\" src=\"/f.jpg\" width=\"800\" height=\"500\">"
                + "<div class=\"post-content\"><p>" + BODY + "</p><img src=\"/f.jpg\" width=\"800\" height=\"500\"><p>" + BODY + "</p></div></article></body></html>";

        String clean = extractor.extract(html, "https://blog.com/p").html();

        assertThat(clean.split("f\\.jpg", -1).length - 1).isEqualTo(1);
    }

    @Test
    void pagesWithoutAFeaturedImageStillExtractNormally() {
        String html = "<html><body><article><div class=\"post-content\"><p>" + BODY + "</p></div></article></body></html>";

        assertThat(extractor.extract(html, "https://blog.com/p").html()).doesNotContain("<img").contains("genuinely long paragraph");
    }

    @Test
    void firstImageUrlPicksTheFirstRealContentImage() {
        String html = "<p>x</p><img src=\"https://stats.example.com/pixel.gif\"><img src=\"data:image/gif;base64,AAAA\">"
                + "<img class=\"avatar\" src=\"/a.png\"><img data-orig-src=\"/real.jpg\" src=\"data:image/svg+xml,%3Csvg%3E\" width=\"600\">";

        assertThat(ContentExtractor.firstImageUrl(html, "https://blog.com/p")).isEqualTo("https://blog.com/real.jpg");
    }

    @Test
    void firstImageUrlIsNullWhenThereIsNoUsableImage() {
        assertThat(ContentExtractor.firstImageUrl("<p>text only</p><img src=\"/tiny.png\" width=\"8\">", "https://blog.com/p")).isNull();
        assertThat(ContentExtractor.firstImageUrl(null, "https://blog.com/p")).isNull();
    }

    @Test
    void featuredImageIsFoundEvenWhenPageWrappersOutsideTheArticleHaveNoiseWords() {
        String html = "<html><body class=\"menu-text-align-center has-sidebar\"><div id=\"wrapper\" class=\"side-header social-icons\">"
                + "<article><div class=\"slideshow\"><ul class=\"slides\"><li><a href=\"/big.jpg\"><img class=\"wp-post-image\" "
                + "width=\"1512\" height=\"1030\" src=\"/uploads/hero.jpg\" alt=\"\"></a></li></ul></div>"
                + "<div class=\"post-content\"><p>" + BODY + "</p></div></article></div></body></html>";

        assertThat(extractor.extract(html, "https://blog.com/p").html()).contains("https://blog.com/uploads/hero.jpg");
    }

    @Test
    void featuredImageInsideARelatedPostsBlockInsideTheArticleIsStillIgnored() {
        String html = "<html><body><article><div class=\"related-posts\"><img class=\"wp-post-image\" width=\"500\" height=\"383\" "
                + "src=\"/uploads/rel.jpg\"></div><div class=\"post-content\"><p>" + BODY + "</p></div></article></body></html>";

        assertThat(extractor.extract(html, "https://blog.com/p").html()).doesNotContain("rel.jpg");
    }

    @Test
    void exposesTheMainImageThePageDeclares() {
        String html = "<html><head><meta property=\"og:image\" content=\"/img/main.jpg\"></head><body><article><p>"
                + BODY + "</p></article></body></html>";

        assertThat(extractor.extract(html, "https://blog.com/p").pageImage()).isEqualTo("https://blog.com/img/main.jpg");
    }

    @Test
    void fallsBackToTheTwitterCardImageAndIsNullWithoutAny() {
        String twitter = "<html><head><meta name=\"twitter:image\" content=\"https://cdn.com/t.jpg\"></head><body><article><p>"
                + BODY + "</p></article></body></html>";
        String none = "<html><body><article><p>" + BODY + "</p></article></body></html>";

        assertThat(extractor.extract(twitter, "https://blog.com/p").pageImage()).isEqualTo("https://cdn.com/t.jpg");
        assertThat(extractor.extract(none, "https://blog.com/p").pageImage()).isNull();
    }

    @Test
    void firstImageUrlSkipsAuthorPhotosAndLazyPlaceholders() {
        String html = "<img class=\"journalistInfo__photo\" width=\"74\" src=\"/author.jpg\">"
                + "<img class=\"opening__placeholder\" data-src=\"/tiny.jpg\">"
                + "<img class=\"opening__img\" data-src=\"/post.jpg\">";

        assertThat(ContentExtractor.firstImageUrl(html, "https://blog.com/p")).isEqualTo("https://blog.com/post.jpg");
    }
}
