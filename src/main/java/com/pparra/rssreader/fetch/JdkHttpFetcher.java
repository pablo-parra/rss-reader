package com.pparra.rssreader.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class JdkHttpFetcher implements HttpFetcher {

    private static final int MAX_BYTES = 2_000_000;
    private static final String USER_AGENT = "RssReader/0.1 (personal feed reader)";
    private static final String ACCEPT =
            "text/html,application/xhtml+xml,application/rss+xml,application/atom+xml,application/xml;q=0.9,*/*;q=0.5";
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public FetchedPage get(String url) throws IOException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", ACCEPT)
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }

        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BYTES);
                String contentType = response.headers().firstValue("content-type").orElse("");
                Map<String, String> headers = new HashMap<>();
                response.headers().map().forEach((name, values) -> {
                    if (!values.isEmpty()) {
                        headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
                    }
                });
                return new FetchedPage(
                        response.statusCode(),
                        contentType,
                        new String(bytes, charsetOf(contentType)),
                        response.uri().toString(),
                        headers);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    private static Charset charsetOf(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (IllegalArgumentException ignored) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }
}
