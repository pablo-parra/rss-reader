package com.pparra.rssreader.web;

import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.service.FetchService;
import com.pparra.rssreader.service.FetchSummary;
import com.pparra.rssreader.service.SourceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SourceController {

    private final SourceService sourceService;
    private final FetchService fetchService;

    public SourceController(SourceService sourceService, FetchService fetchService) {
        this.sourceService = sourceService;
        this.fetchService = fetchService;
    }

    @GetMapping("/sources")
    public String list(Model model) {
        model.addAttribute("sources", sourceService.list());
        return "sources";
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
