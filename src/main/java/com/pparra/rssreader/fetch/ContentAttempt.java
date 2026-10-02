package com.pparra.rssreader.fetch;

import java.util.Optional;

public record ContentAttempt(Optional<String> html, String failure) {

    public static ContentAttempt success(String html) {
        return new ContentAttempt(Optional.of(html), null);
    }

    public static ContentAttempt failed(String reason) {
        return new ContentAttempt(Optional.empty(), reason);
    }
}
