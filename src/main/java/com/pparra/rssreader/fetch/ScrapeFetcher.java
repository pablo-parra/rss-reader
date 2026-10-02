package com.pparra.rssreader.fetch;

import com.pparra.rssreader.domain.Source;
import java.io.IOException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class ScrapeFetcher {

    private static final int MIN_TITLE_CHARS = 15;
    private static final int MAX_ITEMS = 50;
    private static final String HEADING_LINKS = "h1 a[href], h2 a[href], h3 a[href], a:has(h1, h2, h3)";
    private static final String CHROME = "nav, header, footer, aside";

    private final HttpFetcher http;

    public ScrapeFetcher(HttpFetcher http) {
        this.http = http;
    }

    public List<FeedItem> fetch(Source source) throws IOException {
        FetchedPage page = http.get(source.getUrl());
        if (ChallengeDetector.isChallenge(page)) {
            throw new SourceBlockedException(source.getUrl());
        }
        if (!page.isSuccess()) {
            throw new IOException("HTTP " + page.status() + " for " + source.getUrl());
        }

        Document doc = Jsoup.parse(page.body(), page.finalUrl());
        String host = hostOf(page.finalUrl());
        String pageUrl = withoutTrailingSlash(page.finalUrl());

        List<Element> anchors = doc.select(HEADING_LINKS);
        if (anchors.isEmpty()) {
            anchors = doc.select("article").stream()
                    .flatMap(article -> article.select("a[href]").stream()
                            .max(Comparator.comparingInt(a -> a.text().length()))
                            .stream())
                    .toList();
        }

        Map<String, FeedItem> items = new LinkedHashMap<>();
        for (Element anchor : anchors) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            String url = anchor.absUrl("href").replaceFirst("#.*$", "");
            String title = anchor.text().trim();
            if (isArticleLink(anchor, url, title, host, pageUrl)) {
                items.putIfAbsent(url, new FeedItem(url, title, publishedDate(anchor)));
            }
        }
        return List.copyOf(items.values());
    }

    private static boolean isArticleLink(Element anchor, String url, String title, String host, String pageUrl) {
        if (title.length() < MIN_TITLE_CHARS || isPageChrome(anchor)) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean web = scheme.equals("http") || scheme.equals("https");
        boolean rootPath = uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/");
        return web
                && host.equals(hostOf(url))
                && !rootPath
                && !withoutTrailingSlash(url).equals(pageUrl);
    }

    private static boolean isPageChrome(Element anchor) {
        Element chrome = anchor.closest(CHROME);
        return chrome != null && chrome.closest("article") == null;
    }

    private static Instant publishedDate(Element anchor) {
        Element article = anchor.closest("article");
        Element time = article == null ? null : article.selectFirst("time[datetime]");
        if (time == null) {
            return null;
        }
        String value = time.attr("datetime").trim();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException ignored) {
            // fall through to the other accepted formats
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length()))).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        String host = URI.create(url).getHost();
        if (host == null) {
            return "";
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
