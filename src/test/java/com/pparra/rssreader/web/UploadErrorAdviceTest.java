package com.pparra.rssreader.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class UploadErrorAdviceTest {

    @Controller
    static class Oversized {
        @PostMapping("/upload")
        String upload() {
            throw new MaxUploadSizeExceededException(2 * 1024 * 1024);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Oversized())
            .setControllerAdvice(new UploadErrorAdvice()).build();

    @Test
    void anOversizedUploadRedirectsToTheSourcesPageWithAnError() throws Exception {
        mvc.perform(post("/upload"))
                .andExpect(redirectedUrl("/sources"))
                .andExpect(flash().attribute("error",
                        "The upload was rejected (the file is too large, the limit is 2 MB). Nothing was imported."));
    }
}
