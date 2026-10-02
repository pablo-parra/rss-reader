package com.pparra.rssreader.fetch;

import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;

public final class ChallengeDetector {

    private static final List<String> CHALLENGE_MARKERS =
            List.of("just a moment", "cf-chl", "challenge-platform", "attention required", "cf-turnstile");
    private static final List<String> CAPTCHA_MARKERS = List.of("g-recaptcha", "h-captcha", "cf-turnstile");
    private static final int SHORT_PAGE_CHARS = 30_000;
    private static final int CHALLENGE_TEXT_CHARS = 1_000;

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
        // A captcha widget on a page with real content (comment form, article about captchas) is not a challenge.
        return body.length() < SHORT_PAGE_CHARS && containsAny(body, CAPTCHA_MARKERS)
                && Jsoup.parse(body).text().length() < CHALLENGE_TEXT_CHARS;
    }

    private static boolean containsAny(String body, List<String> markers) {
        return markers.stream().anyMatch(body::contains);
    }
}
