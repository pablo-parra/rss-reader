package com.pparra.rssreader.fetch;

import java.util.List;
import java.util.Locale;

public final class ChallengeDetector {

    private static final List<String> CHALLENGE_MARKERS =
            List.of("just a moment", "cf-chl", "challenge-platform", "attention required", "cf-turnstile");
    private static final List<String> CAPTCHA_MARKERS = List.of("g-recaptcha", "h-captcha", "cf-turnstile");
    private static final int SHORT_PAGE_CHARS = 30_000;

    private ChallengeDetector() {
    }

    public static boolean isChallenge(FetchedPage page) {
        if ("challenge".equalsIgnoreCase(page.headers().get("cf-mitigated"))) {
            return true;
        }
        String body = page.body() == null ? "" : page.body().toLowerCase(Locale.ROOT);
        boolean blockedStatus = page.status() == 403 || page.status() == 429 || page.status() == 503;
        if (blockedStatus && containsAny(body, CHALLENGE_MARKERS)) {
            return true;
        }
        return body.length() < SHORT_PAGE_CHARS && containsAny(body, CAPTCHA_MARKERS);
    }

    private static boolean containsAny(String body, List<String> markers) {
        return markers.stream().anyMatch(body::contains);
    }
}
