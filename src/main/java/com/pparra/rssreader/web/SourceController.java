package com.pparra.rssreader.web;

import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.service.FetchService;
import com.pparra.rssreader.service.FetchSummary;
import com.pparra.rssreader.service.SourceService;
import com.pparra.rssreader.service.InvalidOpmlException;
import com.pparra.rssreader.service.OpmlExport;
import com.pparra.rssreader.service.OpmlImportSummary;
import com.pparra.rssreader.service.OpmlService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SourceController {

    private final SourceService sourceService;
    private final FetchService fetchService;
    private final OpmlService opmlService;

    public SourceController(SourceService sourceService, FetchService fetchService, OpmlService opmlService) {
        this.sourceService = sourceService;
        this.fetchService = fetchService;
        this.opmlService = opmlService;
    }

    @GetMapping("/sources")
    public String list(Model model) {
        List<Source> sources = sourceService.list();
        model.addAttribute("sources", sources);
        model.addAttribute("leftOutOfExport", sources.stream().filter(s -> s.getType() != SourceType.RSS).count());
        model.addAttribute("exportable", sources.stream().filter(s -> s.getType() == SourceType.RSS).count());
        return "sources";
    }

    @PostMapping("/sources/import")
    public String importOpml(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        if (file.isEmpty()) {
            redirect.addFlashAttribute("error", "Choose an OPML file to import");
            return "redirect:/sources";
        }
        try (InputStream in = file.getInputStream()) {
            OpmlImportSummary summary = opmlService.importOpml(in);
            String text = "Import finished: " + summary.added() + " added, " + summary.skippedDuplicates()
                    + " skipped as duplicates, " + summary.skippedInvalid() + " skipped as invalid";
            redirect.addFlashAttribute(summary.added() == 0 && summary.skippedInvalid() > 0 ? "warning" : "message", text);
        } catch (InvalidOpmlException e) {
            redirect.addFlashAttribute("error", e.getMessage() + ". Nothing was imported.");
        } catch (IOException e) {
            redirect.addFlashAttribute("error", "The file could not be read. Nothing was imported.");
        }
        return "redirect:/sources";
    }

    @GetMapping("/sources/export")
    public ResponseEntity<byte[]> exportOpml() {
        OpmlExport export = opmlService.exportOpml();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/x-opml+xml;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"subscriptions.opml\"")
                .header("X-Sources-Left-Out", String.valueOf(export.leftOut()))
                .body(export.xml().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/sources")
    public String add(
            @RequestParam String url,
            @RequestParam(required = false) String name,
            RedirectAttributes redirect) {
        try {
            Source source = sourceService.create(url, name);
            if (source.getType() == SourceType.UNSUPPORTED) {
                redirect.addFlashAttribute(
                        "warning",
                        "Added \"" + source.getName() + "\" but it is unsupported: the page is unreachable or"
                                + " protected by a bot check, so it will be skipped.");
            } else {
                redirect.addFlashAttribute("message", "Added \"" + source.getName() + "\" (" + source.getType() + ")");
            }
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/sources";
    }

    @PostMapping("/sources/{id}/recheck")
    public String recheck(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            Source source = sourceService.recheck(id);
            if (source.getType() == SourceType.UNSUPPORTED) {
                redirect.addFlashAttribute("warning", "\"" + source.getName() + "\" is still unreachable or protected by a bot check");
            } else {
                redirect.addFlashAttribute("message", "\"" + source.getName() + "\" is supported again (" + source.getType() + ")");
            }
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/sources";
    }

    @PostMapping("/sources/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        sourceService.delete(id);
        redirect.addFlashAttribute("message", "Source removed");
        return "redirect:/sources";
    }

    @PostMapping("/sources/fetch-now")
    public String fetchNow(@RequestParam(defaultValue = "sources") String from, RedirectAttributes redirect) {
        fetchService.fetchAll().ifPresentOrElse(
                summary -> {
                    String text = describe(summary);
                    redirect.addFlashAttribute(summary.failed() > 0 ? "warning" : "message", text);
                },
                () -> redirect.addFlashAttribute("warning", "A fetch is already running, try again in a moment"));
        return from.equals("dashboard") ? "redirect:/" : "redirect:/sources";
    }

    private static String describe(FetchSummary summary) {
        StringBuilder text = new StringBuilder("Fetched ")
                .append(summary.sourcesOk()).append(summary.sourcesOk() == 1 ? " source: " : " sources: ")
                .append(summary.newArticles()).append(" new ").append(summary.newArticles() == 1 ? "article" : "articles");
        if (summary.failed() > 0) {
            text.append(", ").append(summary.failed()).append(" failed");
        }
        if (summary.skipped() > 0) {
            text.append(", ").append(summary.skipped()).append(" skipped (unsupported)");
        }
        return text.toString();
    }
}
