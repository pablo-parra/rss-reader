package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleService {

    private static final Comparator<Article> NEWEST_FIRST =
            Comparator.comparing(ArticleService::sortKey).reversed().thenComparing(Article::getId, Comparator.reverseOrder());

    private final ArticleRepository articleRepository;
    private final SourceRepository sourceRepository;
    private final int readVisibleDays;

    public ArticleService(
            ArticleRepository articleRepository,
            SourceRepository sourceRepository,
            @Value("${app.dashboard.read-visible-days}") int readVisibleDays) {
        this.articleRepository = articleRepository;
        this.sourceRepository = sourceRepository;
        this.readVisibleDays = readVisibleDays;
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

    public List<ArticleGroup> dashboardGroups() {
        Map<Long, Source> sources = sourceRepository.findAll().stream()
                .collect(Collectors.toMap(Source::getId, Function.identity()));
        Instant since = Instant.now().minus(Duration.ofDays(readVisibleDays));

        Map<Long, List<Article>> bySource = articleRepository.findVisibleOnDashboard(since).stream()
                .filter(article -> sources.containsKey(article.getSourceId()))
                .sorted(NEWEST_FIRST)
                .collect(Collectors.groupingBy(Article::getSourceId, LinkedHashMap::new, Collectors.toList()));

        return bySource.entrySet().stream()
                .map(entry -> new ArticleGroup(sources.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static Instant sortKey(Article article) {
        return article.getPublishedAt() != null ? article.getPublishedAt() : article.getFetchedAt();
    }
}
