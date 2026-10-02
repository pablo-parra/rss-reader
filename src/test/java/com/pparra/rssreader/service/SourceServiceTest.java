package com.pparra.rssreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.fetch.SourceTypeDetector;
import com.pparra.rssreader.fetch.SourceTypeDetector.Detection;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceServiceTest {

    private final SourceRepository sources = mock(SourceRepository.class);
    private final SourceTypeDetector detector = mock(SourceTypeDetector.class);
    private final SourceService service = new SourceService(sources, mock(ArticleRepository.class), detector);

    @Test
    void hostCaseAndRootSlashDoNotCreateDistinctSources() {
        when(detector.detect(any())).thenReturn(new Detection(SourceType.SCRAPE, null));
        when(sources.save(any(Source.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.create("https://Example.COM/", null).getUrl()).isEqualTo("https://example.com");
        assertThat(service.create("https://example.com/Blog/Feed?x=1", null).getUrl())
                .isEqualTo("https://example.com/Blog/Feed?x=1");
    }

    @Test
    void recheckBringsAnUnsupportedSourceBack() {
        Source walled = new Source("https://a.com", "A", SourceType.UNSUPPORTED, null, Instant.now());
        when(sources.findById(1L)).thenReturn(Optional.of(walled));
        when(sources.save(walled)).thenReturn(walled);
        when(detector.detect("https://a.com")).thenReturn(new Detection(SourceType.RSS, "https://a.com/feed"));

        Source result = service.recheck(1L);

        assertThat(result.getType()).isEqualTo(SourceType.RSS);
        assertThat(result.getFeedUrl()).isEqualTo("https://a.com/feed");
        assertThat(result.getLastFetchStatus()).isNull();
    }
}
