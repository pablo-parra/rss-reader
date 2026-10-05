package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.ContentOrigin;
import com.pparra.rssreader.fetch.ArchiveClient;
import com.pparra.rssreader.fetch.ArticleContentFetcher;
import com.pparra.rssreader.fetch.ContentAttempt;
import com.pparra.rssreader.fetch.ContentExtractor;
import com.pparra.rssreader.fetch.ExtractedContent;
import com.pparra.rssreader.repository.ArticleRepository;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ArticleContentService {

    private static final Logger log = LoggerFactory.getLogger(ArticleContentService.class);

    public static final int CONTENT_VERSION = 4;

    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final ArticleContentFetcher contentFetcher;
    private final ArchiveClient archiveClient;
    private final ContentExtractor extractor;
    private final int minTextLength;

    public ArticleContentService(
            ArticleService articleService,
            ArticleRepository articleRepository,
            ArticleContentFetcher contentFetcher,
            ArchiveClient archiveClient,
            ContentExtractor extractor,
            @Value("${app.content.min-text-length}") int minTextLength) {
        this.articleService = articleService;
        this.articleRepository = articleRepository;
        this.contentFetcher = contentFetcher;
        this.archiveClient = archiveClient;
        this.extractor = extractor;
        this.minTextLength = minTextLength;
    }

    public ArticleContent load(Long articleId) {
        Article article = articleService.get(articleId);
        if (article.getContentOrigin() == null || !Integer.valueOf(CONTENT_VERSION).equals(article.getContentVersion())) {
            resolve(articleId, article);
        }
        return toContent(article);
    }

    public ArticleContent retry(Long articleId) {
        Article article = articleService.get(articleId);
        if (article.getContentOrigin() == ContentOrigin.UNAVAILABLE) {
            resolve(articleId, article);
        }
        return toContent(article);
    }

    private void resolve(Long articleId, Article article) {
        String fromFeed = readableFeedContent(article);
        if (fromFeed != null) {
            store(articleId, article, fresh -> fresh.cacheContent(fromFeed, ContentOrigin.FEED, Instant.now(), CONTENT_VERSION));
            return;
        }
        ContentAttempt original = contentFetcher.fetchReadable(article.getUrl());
        if (original.html().isPresent()) {
            store(articleId, article, fresh -> {
                fresh.cacheContent(original.html().get(), ContentOrigin.ORIGINAL, Instant.now(), CONTENT_VERSION);
                fresh.replaceImage(original.imageUrl());
            });
            return;
        }
        ContentAttempt snapshot = archiveClient.fetchSnapshot(article.getUrl());
        if (snapshot.html().isPresent()) {
            log.info("Article {} ({}): original not readable ({}), using archive.ph snapshot",
                    article.getId(), article.getUrl(), original.failure());
            store(articleId, article, fresh -> {
                fresh.cacheContent(snapshot.html().get(), ContentOrigin.ARCHIVE, Instant.now(), CONTENT_VERSION);
                fresh.replaceImage(original.imageUrl());
            });
        } else {
            String reason = "Original page: " + original.failure() + ". archive.ph: " + snapshot.failure() + ".";
            log.warn("Article {} ({}) could not be loaded. {}", article.getId(), article.getUrl(), reason);
            store(articleId, article, fresh -> {
                fresh.cacheFailure(reason, Instant.now(), CONTENT_VERSION);
                fresh.replaceImage(original.imageUrl());
            });
        }
    }

    /**
     * Network loading can take many seconds; the article is re-read before saving so that read state or feed content
     * changed meanwhile is not overwritten by the stale copy. The caller's instance is updated too.
     */
    private void store(Long articleId, Article loaded, Consumer<Article> change) {
        Article fresh = articleService.get(articleId);
        change.accept(fresh);
        saveWithThumbnail(fresh);
        if (fresh != loaded) {
            change.accept(loaded);
            loaded.useImageIfMissing(fresh.getImageUrl());
        }
    }

    private void saveWithThumbnail(Article article) {
        article.useImageIfMissing(ContentExtractor.firstImageUrl(article.getContentHtml(), article.getUrl()));
        articleRepository.save(article);
    }

    /** The feed's own content counts as the full article only when it is long enough not to be an excerpt. */
    private String readableFeedContent(Article article) {
        String raw = article.getFeedContentHtml();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ExtractedContent content = extractor.extract("<html><body><article>" + raw + "</article></body></html>", article.getUrl());
        if (content.declaredPaywalled() || content.textLength() < minTextLength) {
            log.debug("Article {}: feed content is only an excerpt ({} characters)", article.getId(), content.textLength());
            return null;
        }
        return content.html();
    }

    private ArticleContent toContent(Article article) {
        return new ArticleContent(
                article.getContentHtml(), article.getContentOrigin(), archiveClient.lookupUrl(article.getUrl()),
                article.getContentFailureReason());
    }
}
