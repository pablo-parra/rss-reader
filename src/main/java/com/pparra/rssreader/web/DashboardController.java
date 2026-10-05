package com.pparra.rssreader.web;

import com.pparra.rssreader.service.ArticleService;
import com.pparra.rssreader.service.Dashboard;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardController {

    private final ArticleService articleService;

    public DashboardController(ArticleService articleService) {
        this.articleService = articleService;
    }

    /** "All" without a {@code source} parameter; with one, the articles of that source. */
    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) Long source, Model model) {
        Dashboard dashboard = articleService.dashboard(source);
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("selectedId", dashboard.selected() == null ? null : dashboard.selected().getId());
        return "dashboard";
    }
}
