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
    }

    public void recordFetch(FetchStatus status, Instant at) {
        this.lastFetchStatus = status;
        this.lastFetchedAt = at;
    }

    public void markUnsupported(Instant at) {
        this.type = SourceType.UNSUPPORTED;
        recordFetch(FetchStatus.UNSUPPORTED, at);
    }

    /** Applies a fresh type detection, e.g. after a source that was flagged unsupported became reachable again. */
    public void redetect(SourceType type, String feedUrl) {
        this.type = type;
        this.feedUrl = feedUrl;
        this.lastFetchStatus = type == SourceType.UNSUPPORTED ? FetchStatus.UNSUPPORTED : null;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
