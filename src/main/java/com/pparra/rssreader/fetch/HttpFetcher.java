package com.pparra.rssreader.fetch;

import java.io.IOException;

public interface HttpFetcher {

    FetchedPage get(String url) throws IOException;
}
