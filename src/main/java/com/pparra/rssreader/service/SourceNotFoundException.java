package com.pparra.rssreader.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class SourceNotFoundException extends RuntimeException {

    public SourceNotFoundException(Long id) {
        super("Source " + id + " not found");
    }
}
