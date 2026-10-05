package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.ArticleRepository.SourceCounts;
import com.pparra.rssreader.repository.SourceRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleService {

    private static final Comparator<Article> NEWEST_FIRST =
            Comparator.comparing(ArticleService::sortKey).reversed().thenComparing(Article::getId, Comparator.reverseOrder());

    private final ArticleRepository articleRepository;
    private final SourceRepository sourceRepository;
    private final int sourceViewSize;

    public ArticleService(
            ArticleRepository articleRepository,
            SourceRepository sourceRepository,
            @Value("${app.dashboard.source-view-size}") int sourceViewSize) {
        this.articleRepository = articleRepository;
        this.sourceRepository = sourceRepository;
        this.sourceViewSize = sourceViewSize;
    }

    public Article get(Long id) {
        return articleRepository.findById(id).orElseThrow(() -> new ArticleNotFoundException(id));
    }

    @Transactional
    public Article markRead(Long id) {
        Article article = get(id);
        article.markRead(Instant.now());
        return article;
    }

    @Transactional
    public Article markUnread(Long id) {
        Article article = get(id);
        article.markUnread();
        return article;
    }

    /**
     * The dashboard for one source, or for "All" when {@code sourceId} is null or unknown. "All" lists the unread
     * articles of every source, newest first and by source name on equal dates. A source lists its newest articles
     * (read or not) plus any older unread ones, so its unread count always matches what is on screen.
     */
    public Dashboard dashboard(Long sourceId) {
        Map<Long, Source> sources = sourceRepository.findAll().stream()
                .collect(Collectors.toMap(Source::getId, Function.identity()));
        Map<Long, Long> unreadBySource = new LinkedHashMap<>();
        for (SourceCounts counts : articleRepository.countBySource()) {
            if (sources.containsKey(counts.getSourceId())) {
                unreadBySource.put(counts.getSourceId(), counts.getUnread());
            }
        }

        List<SourceUnread> entries = unreadBySource.entrySet().stream()
                .map(entry -> new SourceUnread(sources.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(entry -> entry.source().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        long totalUnread = entries.stream().mapToLong(SourceUnread::unread).sum();

        Source selected = sourceId == null ? null : sources.get(sourceId);
        List<Article> articles = selected == null ? allUnread(sources) : forSource(selected);
        List<ArticleRow> rows = articles.stream()
                .map(article -> new ArticleRow(article, sources.get(article.getSourceId())))
                .toList();
        return new Dashboard(entries, totalUnread, selected, rows);
    }

    private List<Article> allUnread(Map<Long, Source> sources) {
        // newest first; on the same date the source name decides, and the id only breaks the remaining ties
        Comparator<Article> order = Comparator.comparing(ArticleService::sortKey).reversed()
                .thenComparing(article -> sources.get(article.getSourceId()).getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Article::getId, Comparator.reverseOrder());
        return articleRepository.findUnreadRows().stream()
                .filter(article -> sources.containsKey(article.getSourceId()))
                .sorted(order)
                .toList();
    }

    private List<Article> forSource(Source source) {
        Map<Long, Article> byId = new LinkedHashMap<>();
        articleRepository.findNewestRowsBySourceId(source.getId(), PageRequest.of(0, sourceViewSize))
                .forEach(article -> byId.put(article.getId(), article));
        articleRepository.findUnreadRowsBySourceId(source.getId())
                .forEach(article -> byId.putIfAbsent(article.getId(), article));
        return byId.values().stream().sorted(NEWEST_FIRST).toList();
    }

    private static Instant sortKey(Article article) {
        return article.getPublishedAt() != null ? article.getPublishedAt() : article.getFetchedAt();
    }
}
