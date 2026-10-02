package com.pparra.rssreader.scheduler;

import com.pparra.rssreader.service.FetchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyFetchJob {

    private static final Logger log = LoggerFactory.getLogger(DailyFetchJob.class);

    private final FetchService fetchService;

    public DailyFetchJob(FetchService fetchService) {
        this.fetchService = fetchService;
    }

    @Scheduled(cron = "${app.fetch.cron}")
    public void run() {
        fetchService.fetchAll().ifPresentOrElse(
                summary -> log.info("Daily fetch finished: {}", summary),
                () -> log.info("Daily fetch skipped: another fetch is already running"));
    }
}
