package com.pparra.rssreader.fetch;

import java.util.Map;

public record FetchedPage(int status, String contentType, String body, String finalUrl, Map<String, String> headers) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
