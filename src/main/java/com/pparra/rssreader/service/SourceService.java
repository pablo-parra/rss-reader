package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.fetch.SourceTypeDetector;
import com.pparra.rssreader.fetch.SourceTypeDetector.Detection;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {

    private final SourceRepository sourceRepository;
    private final ArticleRepository articleRepository;
    private final SourceTypeDetector detector;

    public SourceService(
            SourceRepository sourceRepository, ArticleRepository articleRepository, SourceTypeDetector detector) {
        this.sourceRepository = sourceRepository;
        this.articleRepository = articleRepository;
        this.detector = detector;
    }

    public Source create(String rawUrl, String rawName) {
        String url = normalize(rawUrl);
        requireNew(url);

        Detection detection = detector.detect(url);
        if (detection.feedUrl() != null) {
            requireNew(detection.feedUrl());
        }

        String name = rawName == null || rawName.isBlank() ? hostOf(url) : rawName.trim();
        return sourceRepository.save(new Source(url, name, detection.type(), detection.feedUrl(), Instant.now()));
    }

    public List<Source> list() {
        return sourceRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public void delete(Long id) {
        articleRepository.deleteBySourceId(id);
        sourceRepository.deleteById(id);
    }

    private void requireNew(String url) {
        if (sourceRepository.existsByUrlOrFeedUrl(url, url)) {
            throw new IllegalArgumentException("This source is already in your list");
        }
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A URL is required");
        }
        String candidate = raw.trim().replaceFirst("#.*$", "");
        if (!candidate.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            candidate = "https://" + candidate;
        }
        URI uri;
        try {
            uri = new URI(candidate).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That doesn't look like a valid URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https URLs are supported");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("That doesn't look like a valid URL");
        }
        return uri.toString();
    }

    private static String hostOf(String url) {
        String host = URI.create(url).getHost();
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
