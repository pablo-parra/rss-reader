package com.pparra.rssreader.web;

import com.pparra.rssreader.service.ArticleContentService;
import com.pparra.rssreader.service.ArticleService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/articles")
public class ArticleController {

    private final ArticleService articleService;
    private final ArticleContentService contentService;

    public ArticleController(ArticleService articleService, ArticleContentService contentService) {
        this.articleService = articleService;
        this.contentService = contentService;
    }

    @GetMapping("/{id}")
    public String reader(@PathVariable Long id, Model model) {
        model.addAttribute("article", articleService.markRead(id));
        model.addAttribute("content", contentService.load(id));
        return "article";
    }

    /** Marks an article read from the dashboard without opening it (no content is loaded). */
    @PostMapping("/{id}/read")
    public String markRead(@PathVariable Long id) {
        articleService.markRead(id);
        return "redirect:/#article-" + id;
    }

    @PostMapping("/{id}/unread")
    public String markUnread(@PathVariable Long id) {
        articleService.markUnread(id);
        return "redirect:/#article-" + id;
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable Long id) {
        contentService.retry(id);
        return "redirect:/articles/" + id;
    }
}
