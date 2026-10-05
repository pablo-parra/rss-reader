package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;

/** A dashboard tile: the article and the source it belongs to. */
public record ArticleRow(Article article, Source source) {
}
