package com.pparra.rssreader.web;

import com.pparra.rssreader.service.ArticleContentService;
import com.pparra.rssreader.service.ArticleService;
import com.pparra.rssreader.service.SourceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/articles")
public class ArticleController {

    private final ArticleService articleService;
    private final ArticleContentService contentService;
    private final SourceService sourceService;

    public ArticleController(
            ArticleService articleService, ArticleContentService contentService, SourceService sourceService) {
        this.articleService = articleService;
        this.contentService = contentService;
        this.sourceService = sourceService;
    }

    @GetMapping("/{id}")
    public String reader(@PathVariable Long id, Model model) {
        model.addAttribute("article", articleService.markRead(id));
        model.addAttribute("content", contentService.load(id));
        return "article";
    }

    /** Marks an article read from the dashboard without opening it (no content is loaded). */
    @PostMapping("/{id}/read")
    public String markRead(@PathVariable Long id, @RequestParam(required = false) Long source) {
        articleService.markRead(id);
        return backToDashboard(id, source);
    }

    @PostMapping("/{id}/unread")
    public String markUnread(@PathVariable Long id, @RequestParam(required = false) Long source) {
        articleService.markUnread(id);
        return backToDashboard(id, source);
    }

    /** Marks all unread articles of one source as read, then returns to that source view. */
    @PostMapping("/read-all")
    public String markAllRead(@RequestParam Long source, RedirectAttributes redirect) {
        String name = sourceService.get(source).getName();
        int count = articleService.markAllRead(source);
        redirect.addFlashAttribute("message", count == 0
                ? "Nothing to mark: every article of \"" + name + "\" was already read"
                : "Marked " + count + (count == 1 ? " article" : " articles") + " of \"" + name + "\" as read");
        return "redirect:/?source=" + source;
    }

    /** Back to the view the user came from: "All", or the source it was filtered by. */
    private static String backToDashboard(Long articleId, Long source) {
        return "redirect:/" + (source == null ? "" : "?source=" + source) + "#article-" + articleId;
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable Long id) {
        contentService.retry(id);
        return "redirect:/articles/" + id;
    }
}
