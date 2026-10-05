package com.pparra.rssreader.service;

/** The uploaded file cannot be used as an OPML subscription list; nothing was imported. */
public class InvalidOpmlException extends IllegalArgumentException {

    public InvalidOpmlException(String message) {
        super(message);
    }

    public InvalidOpmlException(String message, Throwable cause) {
        super(message, cause);
    }
}
