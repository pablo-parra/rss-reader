package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Source;

/** An entry of the dashboard's source list: a source with the number of its unread articles. */
public record SourceUnread(Source source, long unread) {
}
