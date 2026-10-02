package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.ContentOrigin;
import com.pparra.rssreader.fetch.ArchiveClient;
import com.pparra.rssreader.fetch.ArticleContentFetcher;
import com.pparra.rssreader.repository.ArticleRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ArticleContentService {

    static final int CONTENT_VERSION = 2;

    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final ArticleContentFetcher contentFetcher;
    private final ArchiveClient archiveClient;

    public ArticleContentService(
            ArticleService articleService,
            ArticleRepository articleRepository,
            ArticleContentFetcher contentFetcher,
            ArchiveClient archiveClient) {
        this.articleService = articleService;
        this.articleRepository = articleRepository;
        this.contentFetcher = contentFetcher;
        this.archiveClient = archiveClient;
    }

    public ArticleContent load(Long articleId) {
        Article article = articleService.get(articleId);
        if (article.getContentOrigin() == null || !Integer.valueOf(CONTENT_VERSION).equals(article.getContentVersion())) {
            resolve(article);
        }
        return toContent(article);
    }

    public ArticleContent retry(Long articleId) {
        Article article = articleService.get(articleId);
        if (article.getContentOrigin() == ContentOrigin.UNAVAILABLE) {
            resolve(article);
        }
        return toContent(article);
    }

    private void resolve(Article article) {
        Optional<String> original = contentFetcher.fetchReadable(article.getUrl());
        if (original.isPresent()) {
            article.cacheContent(original.get(), ContentOrigin.ORIGINAL, Instant.now(), CONTENT_VERSION);
        } else {
            Optional<String> snapshot = archiveClient.fetchSnapshot(article.getUrl());
            if (snapshot.isPresent()) {
                article.cacheContent(snapshot.get(), ContentOrigin.ARCHIVE, Instant.now(), CONTENT_VERSION);
            } else {
                article.cacheContent(null, ContentOrigin.UNAVAILABLE, Instant.now(), CONTENT_VERSION);
            }
        }
        articleRepository.save(article);
    }

    private ArticleContent toContent(Article article) {
        return new ArticleContent(
                article.getContentHtml(), article.getContentOrigin(), archiveClient.lookupUrl(article.getUrl()));
    }
}
