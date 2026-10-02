package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkHttpFetcherTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", "/new");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/new", exchange -> respond(exchange, "text/html", "<p>hello</p>".getBytes()));
        server.createContext("/latin1", exchange -> respond(exchange, "text/xml",
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><t>café</t>".getBytes(Charset.forName("ISO-8859-1"))));
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void refusesLoopbackByDefault() {
        assertThatThrownBy(() -> new JdkHttpFetcher(true).get(base + "/new")).isInstanceOf(IOException.class);
    }

    @Test
    void followsRedirectsAndReportsTheFinalUrl() throws IOException {
        FetchedPage page = new JdkHttpFetcher(false).get(base + "/old");

        assertThat(page.body()).isEqualTo("<p>hello</p>");
        assertThat(page.finalUrl()).isEqualTo(base + "/new");
    }

    @Test
    void stopsAfterTooManyRedirects() {
        assertThatThrownBy(() -> new JdkHttpFetcher(false).get(base + "/loop"))
                .isInstanceOf(IOException.class).hasMessageContaining("Too many redirects");
    }

    @Test
    void usesTheEncodingDeclaredInTheXmlPrologWhenTheHeaderHasNone() throws IOException {
        assertThat(new JdkHttpFetcher(false).get(base + "/latin1").body()).contains("café");
    }
}
