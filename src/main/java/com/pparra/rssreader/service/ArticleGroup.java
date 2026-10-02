package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import java.util.List;

public record ArticleGroup(Source source, List<Article> articles) {

    public long unreadCount() {
        return articles.stream().filter(article -> !article.isRead()).count();
    }
}
