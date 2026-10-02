package com.pparra.rssreader.fetch;

import java.util.Map;

public final class Pages {

    private Pages() {
    }

    public static FetchedPage ok(String contentType, String body, String finalUrl) {
        return new FetchedPage(200, contentType, body, finalUrl, Map.of());
    }

    public static FetchedPage html(String body, String finalUrl) {
        return ok("text/html; charset=utf-8", body, finalUrl);
    }

    public static FetchedPage status(int status, String body, String finalUrl) {
        return new FetchedPage(status, "text/html", body, finalUrl, Map.of());
    }

    public static String articleHtml(String paragraph, int repeat) {
        return "<html><body><nav>menu</nav><article><h1>Title</h1>" + ("<p>" + paragraph + "</p>").repeat(repeat)
                + "</article><footer>footer</footer></body></html>";
    }
}
