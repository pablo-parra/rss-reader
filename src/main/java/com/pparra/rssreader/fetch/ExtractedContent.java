package com.pparra.rssreader.fetch;

/** @param pageImage the main image the page declares (og:image / twitter:image), absolute, or null */
public record ExtractedContent(
        String html, int textLength, boolean declaredPaywalled, boolean paywallMarkers, String pageImage) {

    public ExtractedContent(String html, int textLength, boolean declaredPaywalled, boolean paywallMarkers) {
        this(html, textLength, declaredPaywalled, paywallMarkers, null);
    }
}
