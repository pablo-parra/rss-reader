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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JdkHttpFetcher implements HttpFetcher {

    private static final int MAX_BYTES = 2_000_000;
    private static final String USER_AGENT = "RssReader/0.1 (personal feed reader)";
    private static final String ACCEPT =
            "text/html,application/xhtml+xml,application/rss+xml,application/atom+xml,application/xml;q=0.9,*/*;q=0.5";
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLARED_CHARSET =
            Pattern.compile("(?:encoding|charset)\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private static final int MAX_REDIRECTS = 5;
    private static final java.util.Set<Integer> REDIRECTS = java.util.Set.of(301, 302, 303, 307, 308);

    // Redirects are followed by hand so that every hop is checked by HostGuard.
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final boolean blockInternalHosts;

    public JdkHttpFetcher(@Value("${app.fetch.block-internal-hosts:true}") boolean blockInternalHosts) {
        this.blockInternalHosts = blockInternalHosts;
    }

    @Override
    public FetchedPage get(String url) throws IOException {
        URI current;
        try {
            current = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (blockInternalHosts) {
                    HostGuard.check(current);
                }
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", ACCEPT)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    Optional<String> location = response.headers().firstValue("location");
                    if (REDIRECTS.contains(response.statusCode()) && location.isPresent()) {
                        current = current.resolve(location.get().trim());
                        continue;
                    }
                    return toPage(response, in, current);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid redirect while fetching " + url, e);
        }
        throw new IOException("Too many redirects for " + url);
    }

    private static FetchedPage toPage(HttpResponse<InputStream> response, InputStream in, URI finalUri)
            throws IOException {
        byte[] bytes = in.readNBytes(MAX_BYTES);
        String contentType = response.headers().firstValue("content-type").orElse("");
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return new FetchedPage(
                response.statusCode(), contentType, new String(bytes, charsetOf(contentType, bytes)),
                finalUri.toString(), headers);
    }

    /** Charset from the Content-Type header, else the XML prolog or HTML meta tag, else UTF-8. */
    private static Charset charsetOf(String contentType, byte[] body) {
        Matcher header = CHARSET.matcher(contentType);
        if (header.find()) {
            return charsetNamed(header.group(1));
        }
        String head = new String(body, 0, Math.min(body.length, 1024), StandardCharsets.ISO_8859_1);
        Matcher declared = DECLARED_CHARSET.matcher(head);
        return declared.find() ? charsetNamed(declared.group(1)) : StandardCharsets.UTF_8;
    }

    private static Charset charsetNamed(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException ignored) {
            return StandardCharsets.UTF_8;
        }
    }
}
