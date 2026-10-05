package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Source;
import java.util.List;

/**
 * What the dashboard shows.
 *
 * @param sources every source that has articles, by name, with its unread count (the left-hand list)
 * @param totalUnread unread articles over all sources (the "All" entry)
 * @param selected the source being viewed, or null for "All"
 * @param articles "All": the unread articles of every source; a source: its newest articles, read or not
 */
public record Dashboard(List<SourceUnread> sources, long totalUnread, Source selected, List<ArticleRow> articles) {

    public long selectedUnread() {
        return sources.stream().filter(entry -> entry.source().equals(selected)).mapToLong(SourceUnread::unread).sum();
    }
}
