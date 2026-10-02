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

    protected Article() {
    }

    public Article(Long sourceId, String url, String title, Instant publishedAt, Instant fetchedAt) {
        this.sourceId = sourceId;
        this.url = url;
        this.title = title;
        this.publishedAt = publishedAt;
        this.fetchedAt = fetchedAt;
    }

    public void markRead(Instant at) {
        if (!read || readAt == null) {
            this.read = true;
            this.readAt = at;
        }
    }

    public void markReadSilently() {
        this.read = true;
    }

    public void cacheContent(String html, ContentOrigin origin, Instant at, int version) {
        this.contentHtml = html;
        this.contentOrigin = origin;
        this.contentFetchedAt = at;
        this.contentVersion = version;
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

    public Instant getContentFetchedAt() {
        return contentFetchedAt;
    }
}
