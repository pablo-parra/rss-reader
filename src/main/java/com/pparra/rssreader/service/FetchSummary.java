package com.pparra.rssreader.service;

public record FetchSummary(int sourcesOk, int newArticles, int failed, int skipped) {
}
