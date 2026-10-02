package com.pparra.rssreader.fetch;

import java.io.IOException;

public class SourceBlockedException extends IOException {

    public SourceBlockedException(String url) {
        super("Bot check or CAPTCHA at " + url);
    }
}
