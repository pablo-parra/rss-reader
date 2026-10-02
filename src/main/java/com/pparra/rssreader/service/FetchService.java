package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Article;
import com.pparra.rssreader.domain.FetchStatus;
import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.FeedItem;
import com.pparra.rssreader.fetch.RssFetcher;
import com.pparra.rssreader.fetch.ScrapeFetcher;
import com.pparra.rssreader.fetch.SourceBlockedException;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FetchService {

    private static final Logger log = LoggerFactory.getLogger(FetchService.class);
    private static final int FIRST_FETCH_UNREAD_LIMIT = 10;

    private final SourceRepository sourceRepository;
    private final ArticleRepository articleRepository;
    private final RssFetcher rssFetcher;
    private final ScrapeFetcher scrapeFetcher;
    private final ReentrantLock running = new ReentrantLock();

    public FetchService(
            SourceRepository sourceRepository,
            ArticleRepository articleRepository,
            RssFetcher rssFetcher,
            ScrapeFetcher scrapeFetcher) {
        this.sourceRepository = sourceRepository;
        this.articleRepository = articleRepository;
        this.rssFetcher = rssFetcher;
        this.scrapeFetcher = scrapeFetcher;
    }

    public Optional<FetchSummary> fetchAll() {
        if (!running.tryLock()) {
            return Optional.empty();
        }
        try {
            int ok = 0;
            int added = 0;
            int failed = 0;
            int skipped = 0;
            for (Source source : sourceRepository.findAllByOrderByNameAsc()) {
                if (source.getType() == SourceType.UNSUPPORTED) {
                    skipped++;
                    continue;
                }
                try {
                    added += fetchSource(source);
                    ok++;
                } catch (SourceBlockedException e) {
                    log.warn("Source '{}' is behind a bot check, marking unsupported", source.getName());
                    source.markUnsupported(Instant.now());
                    sourceRepository.save(source);
                    skipped++;
                } catch (IOException | RuntimeException e) {
                    log.warn("Fetching source '{}' failed: {}", source.getName(), e.getMessage());
                    source.recordFetch(FetchStatus.ERROR, Instant.now());
                    sourceRepository.save(source);
                    failed++;
                }
            }
            return Optional.of(new FetchSummary(ok, added, failed, skipped));
        } finally {
            running.unlock();
        }
    }

    private int fetchSource(Source source) throws IOException {
        List<FeedItem> items = source.getType() == SourceType.RSS
                ? rssFetcher.fetch(source)
                : scrapeFetcher.fetch(source);
        Instant now = Instant.now();
        boolean firstFetch = !articleRepository.existsBySourceId(source.getId());
        if (firstFetch) {
            items = items.stream()
                    .sorted(Comparator.comparing(FeedItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }
        int added = 0;
        for (FeedItem item : items) {
            if (!articleRepository.existsBySourceIdAndUrl(source.getId(), item.url())) {
                Article article = new Article(source.getId(), item.url(), item.title(), item.publishedAt(), now);
                if (firstFetch && added >= FIRST_FETCH_UNREAD_LIMIT) {
                    article.markReadSilently();
                }
                articleRepository.save(article);
                added++;
            }
        }
        source.recordFetch(FetchStatus.OK, now);
        sourceRepository.save(source);
        return added;
    }
}
