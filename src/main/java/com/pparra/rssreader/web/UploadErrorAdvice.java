package com.pparra.rssreader.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * A file over the multipart limit fails while the request is being parsed, before any controller is chosen, so a
 * controller-local handler never sees it; this advice turns it into the same flash error as the other import errors.
 */
@ControllerAdvice
public class UploadErrorAdvice {

    @ExceptionHandler(MultipartException.class)
    public String uploadRejected(HttpServletRequest request) {
        RequestContextUtils.getOutputFlashMap(request).put(
                "error", "The upload was rejected (the file is too large, the limit is 2 MB). Nothing was imported.");
        return "redirect:/sources";
    }
}
