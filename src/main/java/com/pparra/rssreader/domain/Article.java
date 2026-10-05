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
@Table(name = "article")
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sourceId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String title;

    private Instant publishedAt;

    @Column(nullable = false)
    private Instant fetchedAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    private Instant readAt;

    @Column(columnDefinition = "TEXT")
    private String contentHtml;

    @Enumerated(EnumType.STRING)
    private ContentOrigin contentOrigin;

    private Instant contentFetchedAt;

    private Integer contentVersion;

    @Column(columnDefinition = "TEXT")
    private String contentFailureReason;

    @Column(columnDefinition = "TEXT")
    private String feedContentHtml;

    @Column(columnDefinition = "TEXT")
    private String imageUrl;

    protected Article() {
    }

    public Article(Long sourceId, String url, String title, Instant publishedAt, Instant fetchedAt) {
        this.sourceId = sourceId;
        this.url = url;
        this.title = title;
        this.publishedAt = publishedAt;
        this.fetchedAt = fetchedAt;
    }

    /**
     * Read-only projection for the dashboard (used by a JPQL constructor expression): it leaves the large HTML
     * columns out and must never be saved back.
     */
    public Article(Long id, Long sourceId, String url, String title, Instant publishedAt, Instant fetchedAt,
            boolean read, Instant readAt, ContentOrigin contentOrigin, String contentFailureReason, String imageUrl) {
        this.id = id;
        this.sourceId = sourceId;
        this.url = url;
        this.title = title;
        this.publishedAt = publishedAt;
        this.fetchedAt = fetchedAt;
        this.read = read;
        this.readAt = readAt;
        this.contentOrigin = contentOrigin;
        this.contentFailureReason = contentFailureReason;
        this.imageUrl = imageUrl;
    }

    public void markRead(Instant at) {
        if (!read || readAt == null) {
            this.read = true;
            this.readAt = at;
        }
    }

    public void markUnread() {
        this.read = false;
        this.readAt = null;
    }

    public void markReadSilently() {
        this.read = true;
    }

    public void cacheContent(String html, ContentOrigin origin, Instant at, int version) {
        this.contentHtml = html;
        this.contentOrigin = origin;
        this.contentFetchedAt = at;
        this.contentVersion = version;
        this.contentFailureReason = null;
    }

    public void cacheFailure(String reason, Instant at, int version) {
        cacheContent(null, ContentOrigin.UNAVAILABLE, at, version);
        this.contentFailureReason = reason;
    }

    /** Stores the raw full content carried by the feed; a previously failed load is reset so it is retried with it. */
    public void attachFeedContent(String html) {
        this.feedContentHtml = html;
        if (contentOrigin == ContentOrigin.UNAVAILABLE) {
            this.contentHtml = null;
            this.contentOrigin = null;
            this.contentFetchedAt = null;
            this.contentVersion = null;
            this.contentFailureReason = null;
        }
    }

    /** Sets the dashboard thumbnail unless one is already known; returns whether it changed. */
    public boolean useImageIfMissing(String url) {
        if (imageUrl == null && url != null && !url.isBlank()) {
            this.imageUrl = url;
            return true;
        }
        return false;
    }

    /** Replaces the thumbnail with the main image the article's own page declares; ignored when there is none. */
    public void replaceImage(String url) {
        if (url != null && !url.isBlank()) {
            this.imageUrl = url;
        }
    }

    /** Sets the publication date unless one is already known; returns whether it changed. */
    public boolean usePublishedAtIfMissing(Instant at) {
        if (publishedAt == null && at != null) {
            this.publishedAt = at;
            return true;
        }
        return false;
    }

    public boolean isContentUnavailable() {
        return contentOrigin == ContentOrigin.UNAVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Integer getContentVersion() {
        return contentVersion;
    }

    public String getContentHtml() {
        return contentHtml;
    }

    public ContentOrigin getContentOrigin() {
        return contentOrigin;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getFeedContentHtml() {
        return feedContentHtml;
    }

    public String getContentFailureReason() {
        return contentFailureReason;
    }

    public Instant getContentFetchedAt() {
        return contentFetchedAt;
    }
}
