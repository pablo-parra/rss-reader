package com.pparra.rssreader.fetch;

import java.util.Optional;

/** @param imageUrl the main image the page declares for the article, or null */
public record ContentAttempt(Optional<String> html, String failure, String imageUrl) {

    public static ContentAttempt success(String html) {
        return success(html, null);
    }

    public static ContentAttempt success(String html, String imageUrl) {
        return new ContentAttempt(Optional.of(html), null, imageUrl);
    }

    public static ContentAttempt failed(String reason) {
        return failed(reason, null);
    }

    /** A failed attempt can still know the page's main image (a paywalled page declares it in its metadata). */
    public static ContentAttempt failed(String reason, String imageUrl) {
        return new ContentAttempt(Optional.empty(), reason, imageUrl);
    }
}
