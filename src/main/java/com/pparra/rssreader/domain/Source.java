package com.pparra.rssreader.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "source")
public class Source {

    private static final String UNREACHABLE = "The page is unreachable or protected by a bot check";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String url;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType type;

    private String feedUrl;

    private Instant lastFetchedAt;

    @Column(columnDefinition = "TEXT")
    private String lastFetchError;

    @Enumerated(EnumType.STRING)
    private FetchStatus lastFetchStatus;

    @Column(nullable = false)
    private Instant createdAt;

    protected Source() {
    }

    public Source(String url, String name, SourceType type, String feedUrl, Instant createdAt) {
        this.url = url;
        this.name = name;
        this.type = type;
        this.feedUrl = feedUrl;
        this.createdAt = createdAt;
        this.lastFetchStatus = type == SourceType.UNSUPPORTED ? FetchStatus.UNSUPPORTED : null;
        this.lastFetchError = type == SourceType.UNSUPPORTED ? UNREACHABLE : null;
    }

    public void recordFetch(FetchStatus status, Instant at) {
        this.lastFetchStatus = status;
        this.lastFetchedAt = at;
        if (status == FetchStatus.OK) {
            this.lastFetchError = null;
        }
    }

    /** Records a failed fetch together with the reason, so it can be shown instead of only a bare ERROR. */
    public void recordError(String reason, Instant at) {
        recordFetch(FetchStatus.ERROR, at);
        this.lastFetchError = reason;
    }

    public void markUnsupported(Instant at) {
        this.type = SourceType.UNSUPPORTED;
        recordFetch(FetchStatus.UNSUPPORTED, at);
        this.lastFetchError = "Blocked by a bot check (Cloudflare or similar)";
    }

    /** Applies a fresh type detection, e.g. after a source that was flagged unsupported became reachable again. */
    public void redetect(SourceType type, String feedUrl) {
        this.type = type;
        this.feedUrl = feedUrl;
        this.lastFetchStatus = type == SourceType.UNSUPPORTED ? FetchStatus.UNSUPPORTED : null;
        this.lastFetchError = type == SourceType.UNSUPPORTED ? UNREACHABLE : null;
    }

    public Long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getName() {
        return name;
    }

    public SourceType getType() {
        return type;
    }

    public String getFeedUrl() {
        return feedUrl;
    }

    public Instant getLastFetchedAt() {
        return lastFetchedAt;
    }

    public FetchStatus getLastFetchStatus() {
        return lastFetchStatus;
    }

    public String getLastFetchError() {
        return lastFetchError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
