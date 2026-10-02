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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FetchService {

    private static final Logger log = LoggerFactory.getLogger(FetchService.class);
    private static final int FIRST_FETCH_UNREAD_LIMIT = 10;
    private static final int MAX_PARALLEL_DOWNLOADS = 4;

    private final SourceRepository sourceRepository;
    private final ArticleRepository articleRepository;
    private final RssFetcher rssFetcher;
    private final ScrapeFetcher scrapeFetcher;
    private final TransactionTemplate transaction;
    private final ReentrantLock running = new ReentrantLock();

    public FetchService(
            SourceRepository sourceRepository,
            ArticleRepository articleRepository,
            RssFetcher rssFetcher,
            ScrapeFetcher scrapeFetcher,
            TransactionTemplate transaction) {
        this.sourceRepository = sourceRepository;
        this.articleRepository = articleRepository;
        this.rssFetcher = rssFetcher;
        this.scrapeFetcher = scrapeFetcher;
        this.transaction = transaction;
    }

    /**
     * Downloads every supported source concurrently (network time dominates), then stores the results one source at
     * a time, in name order, so a failing source never affects the others.
     */
    public Optional<FetchSummary> fetchAll() {
        if (!running.tryLock()) {
            return Optional.empty();
        }
        try (ExecutorService downloads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Source> all = sourceRepository.findAllByOrderByNameAsc();
            Semaphore slots = new Semaphore(MAX_PARALLEL_DOWNLOADS);
            Map<Source, Future<List<FeedItem>>> pending = new LinkedHashMap<>();
            for (Source source : all) {
                if (source.getType() != SourceType.UNSUPPORTED) {
                    pending.put(source, downloads.submit(() -> download(source, slots)));
                }
            }

            int ok = 0;
            int added = 0;
            int failed = 0;
            int skipped = 0;
            for (Source source : all) {
                Future<List<FeedItem>> download = pending.get(source);
                if (download == null) {
                    skipped++;
                    continue;
                }
                try {
                    added += store(source, await(download));
                    ok++;
                } catch (SourceBlockedException e) {
                    log.warn("Source '{}' is behind a bot check, marking unsupported", source.getName());
                    source.markUnsupported(Instant.now());
                    saveIfPresent(source);
                    skipped++;
                } catch (IOException | RuntimeException e) {
                    log.warn("Fetching source '{}' failed: {}", source.getName(), e.getMessage());
                    source.recordFetch(FetchStatus.ERROR, Instant.now());
                    saveIfPresent(source);
                    failed++;
                }
            }
            return Optional.of(new FetchSummary(ok, added, failed, skipped));
        } finally {
            running.unlock();
        }
    }

    private List<FeedItem> download(Source source, Semaphore slots) throws IOException, InterruptedException {
        slots.acquire();
        try {
            return source.getType() == SourceType.RSS ? rssFetcher.fetch(source) : scrapeFetcher.fetch(source);
        } finally {
            slots.release();
        }
    }

    private static List<FeedItem> await(Future<List<FeedItem>> download) throws IOException {
        try {
            return download.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Stores a source's items atomically: a failure part-way rolls back, so the next run is still a first fetch. */
    private int store(Source source, List<FeedItem> items) {
        Integer added = transaction.execute(status -> storeItems(source, items));
        return added == null ? 0 : added;
    }

    private int storeItems(Source source, List<FeedItem> items) {
        if (!sourceRepository.existsById(source.getId())) {
            log.info("Source '{}' was removed while it was being fetched, dropping the result", source.getName());
            return 0;
        }
        Instant now = Instant.now();
        Set<String> known = new HashSet<>(articleRepository.findUrlsBySourceId(source.getId()));
        boolean firstFetch = known.isEmpty();
        if (firstFetch) {
            items = items.stream()
                    .sorted(Comparator.comparing(FeedItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }
        Map<String, Article> backfillCandidates = null;
        int added = 0;
        for (FeedItem item : items) {
            if (known.contains(item.url())) {
                if (item.contentHtml() != null || item.imageUrl() != null) {
                    if (backfillCandidates == null) {
                        backfillCandidates = backfillCandidates(source);
                    }
                    backfill(backfillCandidates.get(item.url()), item);
                }
                continue;
            }
            Article article = new Article(source.getId(), item.url(), item.title(), item.publishedAt(), now);
            if (item.contentHtml() != null) {
                article.attachFeedContent(item.contentHtml());
            }
            article.useImageIfMissing(item.imageUrl());
            if (firstFetch && added >= FIRST_FETCH_UNREAD_LIMIT) {
                article.markReadSilently();
            }
            articleRepository.save(article);
            known.add(item.url());
            added++;
        }
        source.recordFetch(FetchStatus.OK, now);
        sourceRepository.save(source);
        return added;
    }

    private Map<String, Article> backfillCandidates(Source source) {
        Map<String, Article> byUrl = new HashMap<>();
        for (Article article : articleRepository.findBackfillCandidates(source.getId())) {
            byUrl.putIfAbsent(article.getUrl(), article);
        }
        return byUrl;
    }

    /** Fills in feed content and thumbnail that an article stored by an earlier fetch is still missing. */
    private void backfill(Article existing, FeedItem item) {
        if (existing == null) {
            return;
        }
        boolean changed = false;
        if (item.contentHtml() != null && existing.getFeedContentHtml() == null) {
            existing.attachFeedContent(item.contentHtml());
            changed = true;
        }
        changed |= existing.useImageIfMissing(item.imageUrl());
        if (changed) {
            articleRepository.save(existing);
        }
    }

    /** A source removed while a fetch was running must not be re-created by saving its stale state. */
    private void saveIfPresent(Source source) {
        if (sourceRepository.existsById(source.getId())) {
            sourceRepository.save(source);
        }
    }
}
