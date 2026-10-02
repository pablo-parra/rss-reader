package com.pparra.rssreader.web;

import com.pparra.rssreader.service.ArticleGroup;
import com.pparra.rssreader.service.ArticleService;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final ArticleService articleService;

    public DashboardController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<ArticleGroup> groups = articleService.dashboardGroups();
        model.addAttribute("groups", groups);
        model.addAttribute("totalUnread", groups.stream().mapToLong(ArticleGroup::unreadCount).sum());
        return "dashboard";
    }
}
