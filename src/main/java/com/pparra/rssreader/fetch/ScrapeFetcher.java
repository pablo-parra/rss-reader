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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PATH_DATE = Pattern.compile("/((?:19|20)\\d{2})[/-](\\d{2})[/-](\\d{2})(?=[/\\-]|$)");

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

        Map<String, Instant> structuredDates = structuredDates(doc);
        Map<String, FeedItem> items = new LinkedHashMap<>();
        for (Element anchor : anchors) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            String url = anchor.absUrl("href").replaceFirst("#.*$", "");
            String title = anchor.text().trim();
            if (isArticleLink(anchor, url, title, host, pageUrl)) {
                items.putIfAbsent(url, new FeedItem(
                        url, title, publishedDate(anchor, url, structuredDates), null, thumbnail(anchor, page.finalUrl())));
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

    /** Thumbnail of the listing card (the anchor's enclosing article), including lazy-loaded images; null if none. */
    private static String thumbnail(Element anchor, String pageUrl) {
        Element card = anchor.closest("article");
        return card == null ? null : ContentExtractor.firstImageUrl(card.outerHtml(), pageUrl);
    }

    /** Publication date, best source first: the card's {@code <time>}, the page's JSON-LD, a date in the URL path. */
    private static Instant publishedDate(Element anchor, String url, Map<String, Instant> structuredDates) {
        Element article = anchor.closest("article");
        Element time = article == null ? null : article.selectFirst("time[datetime]");
        Instant fromTime = time == null ? null : parseDate(time.attr("datetime"));
        if (fromTime != null) {
            return fromTime;
        }
        Instant fromJsonLd = structuredDates.get(withoutTrailingSlash(url));
        return fromJsonLd != null ? fromJsonLd : dateInPath(url);
    }

    /**
     * Listing pages often describe their articles in JSON-LD (for example the {@code hasPart} of a profile page):
     * every object with a {@code url} and a {@code datePublished} is collected, keyed by URL.
     */
    private static Map<String, Instant> structuredDates(Document doc) {
        Map<String, Instant> dates = new HashMap<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                collectDates(JSON.readTree(script.data()), dates);
            } catch (IOException ignored) {
                // malformed JSON-LD is common; the other date sources still apply
            }
        }
        return dates;
    }

    private static void collectDates(JsonNode node, Map<String, Instant> dates) {
        if (node.isObject()) {
            JsonNode url = node.get("url");
            JsonNode published = node.get("datePublished");
            if (url != null && url.isTextual() && published != null && published.isTextual()) {
                Instant date = parseDate(published.asText());
                if (date != null) {
                    dates.putIfAbsent(withoutTrailingSlash(url.asText().replaceFirst("#.*$", "")), date);
                }
            }
        }
        node.forEach(child -> collectDates(child, dates));
    }

    /** A calendar date in the URL ({@code /2026-10-05/} or {@code /2026/10/05/}), at noon UTC so every zone shows that day. */
    private static Instant dateInPath(String url) {
        Matcher matcher = PATH_DATE.matcher(URI.create(url).getPath());
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))).atTime(12, 0).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Instant parseDate(String raw) {
        String value = raw.trim();
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
