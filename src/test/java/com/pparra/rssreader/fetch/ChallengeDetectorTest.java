package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChallengeDetectorTest {

    @Test
    void emptyCaptchaPageIsAChallenge() {
        assertThat(ChallengeDetector.isChallenge(Pages.html("<html><div class=\"g-recaptcha\"></div></html>", "https://a.com")))
                .isTrue();
    }

    @Test
    void realContentWithACaptchaWidgetIsNotAChallenge() {
        String body = "<html><body><article>" + "<p>Plenty of genuine article text.</p>".repeat(60)
                + "</article><div class=\"g-recaptcha\"></div></body></html>";

        assertThat(ChallengeDetector.isChallenge(Pages.html(body, "https://a.com/post"))).isFalse();
    }

    @Test
    void cloudflareInterstitialIsAChallenge() {
        assertThat(ChallengeDetector.isChallenge(Pages.status(403, "<title>Just a moment...</title>", "https://a.com")))
                .isTrue();
    }
}
