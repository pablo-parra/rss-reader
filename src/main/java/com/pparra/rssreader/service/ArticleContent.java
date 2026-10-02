package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.ContentOrigin;

public record ArticleContent(String html, ContentOrigin origin, String archiveLookupUrl) {
}
