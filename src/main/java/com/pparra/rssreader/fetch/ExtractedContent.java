package com.pparra.rssreader.fetch;

public record ExtractedContent(String html, int textLength, boolean declaredPaywalled, boolean paywallMarkers) {
}
