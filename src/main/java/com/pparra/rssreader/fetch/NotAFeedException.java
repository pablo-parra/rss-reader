package com.pparra.rssreader.fetch;

import java.io.IOException;

/** The URL of an RSS source answered with a web page instead of a feed (wrong URL, or the feed moved). */
public class NotAFeedException extends IOException {

    public NotAFeedException(String url) {
        super("The URL returns a web page, not a feed: " + url);
    }
}
