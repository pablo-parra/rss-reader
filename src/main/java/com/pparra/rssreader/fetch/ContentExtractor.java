package com.pparra.rssreader.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
public class ContentExtractor {

    private static final int MIN_CONTAINER_CHARS = 200;
    private static final double MAX_REMOVABLE_SHARE = 0.5;
    private static final int MAX_LINK_BLOCK_CHARS = 1500;
    private static final double LINK_DENSITY_LIMIT = 0.6;
    private static final int MIN_IMAGE_SIDE = 50;

    private static final Pattern NOT_FREE =
            Pattern.compile("\"isAccessibleForFree\"\\s*:\\s*\"?false\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKING_IMAGE =
            Pattern.compile("(?i)(pixel|tracking|spacer|beacon|1x1|blank\\.gif|gravatar\\.com)");

    private static final List<String> PAYWALL_MARKERS = List.of(
            "subscribe to continue",
            "subscribe to read",
            "already a subscriber",
            "sign in to continue reading",
            "continue reading with a subscription",
            "this article is for subscribers",
            "suscríbete",
            "para seguir leyendo",
            "abonnez-vous",
            "paywall");

    private static final String BODY_SELECTORS = "[itemprop=articleBody], .entry-content, .post-content, "
            + ".article-body, .article__body, .story-body, .post-body, .article-content, #article-body";
    private static final String STRIP_TAGS = "script, style, noscript, template, iframe, form, button, input, select, "
            + "textarea, svg, canvas, nav, aside, footer, dialog, audio, video, object, embed";
    private static final String HIDDEN = "[hidden], [aria-hidden=true], [style*=display:none], [style*=display: none]";
    private static final String LANDMARKS =
            "[role=complementary], [role=navigation], [role=banner], [role=contentinfo], [role=search]";

    private static final Set<String> NOISE_TOKENS = Set.of(
            "related", "recommended", "recommendations", "recirculation", "trending", "popular",
            "share", "shares", "sharing", "social", "follow",
            "newsletter", "subscribe", "signup",
            "comment", "comments", "respond",
            "promo", "promoted", "sponsor", "sponsored", "ad", "ads", "advert", "advertisement", "outbrain", "taboola",
            "sidebar", "widget", "widgets", "toolbar", "breadcrumb", "breadcrumbs", "pagination", "pager",
            "nav", "navigation", "menu", "skip",
            "tags", "taglist", "byline", "bio", "meta", "footer",
            "cookie", "cookies", "consent", "modal", "popup");

    private static final Set<String> IMAGE_NOISE_TOKENS =
            Set.of("avatar", "icon", "logo", "emoji", "sprite", "badge", "gravatar");

    private static final List<String> NOISE_HEADINGS = List.of(
            "related", "related articles", "related posts", "related stories", "related reading",
            "read more", "read next", "read also", "also read", "more from", "more stories",
            "you may also like", "you might also like", "recommended", "recommended for you",
            "share", "share this", "share this article", "share this post", "share on", "follow us",
            "newsletter", "subscribe", "leave a comment", "leave a reply", "comments", "about the author",
            "tags", "tagged", "filed under",
            "noticias relacionadas", "artículos relacionados", "relacionados", "lee también", "lee más",
            "te puede interesar", "también te puede interesar", "comparte", "compartir", "comentarios",
            "deja un comentario", "suscríbete", "sobre el autor", "etiquetas");

    private static final Set<String> PRUNABLE_TAGS = Set.of(
            "div", "section", "p", "ul", "ol", "li", "span", "figure", "figcaption", "a", "blockquote", "header",
            "h2", "h3", "h4", "h5", "h6", "strong", "em", "b", "i");

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("figure", "figcaption")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
            .addEnforcedAttribute("a", "target", "_blank")
            .addEnforcedAttribute("img", "loading", "lazy")
            .addEnforcedAttribute("img", "referrerpolicy", "no-referrer");

    public ExtractedContent extract(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        boolean declaredPaywalled = doc.select("script[type=application/ld+json]").stream()
                .anyMatch(script -> NOT_FREE.matcher(script.data()).find());

        doc.select(STRIP_TAGS).remove();
        Element container = pickContainer(doc);

        String lower = container.text().toLowerCase(Locale.ROOT);
        boolean markers = PAYWALL_MARKERS.stream().anyMatch(lower::contains);

        cleanContainer(container);

        String clean = Jsoup.clean(container.html(), baseUrl, SAFELIST);
        return new ExtractedContent(clean, container.text().length(), declaredPaywalled, markers);
    }

    private static Element pickContainer(Document doc) {
        Element body = longestText(doc.select(BODY_SELECTORS));
        if (body != null && body.text().length() >= MIN_CONTAINER_CHARS) {
            return body;
        }
        Element article = longestText(doc.select("article"));
        if (article != null && article.text().length() >= MIN_CONTAINER_CHARS) {
            return article;
        }
        Element main = doc.selectFirst("main");
        if (main != null && main.text().length() >= MIN_CONTAINER_CHARS) {
            return main;
        }
        Element best = doc.body();
        int bestScore = 0;
        for (Element candidate : doc.select("div, section")) {
            int score = candidate.children().stream()
                    .filter(child -> child.tagName().equals("p"))
                    .mapToInt(child -> child.text().length())
                    .sum();
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static Element longestText(Elements candidates) {
        Element best = null;
        for (Element candidate : candidates) {
            if (best == null || candidate.text().length() > best.text().length()) {
                best = candidate;
            }
        }
        return best;
    }

    private static void cleanContainer(Element container) {
        int total = Math.max(1, container.text().length());

        container.select("h1").remove();
        removeDescendants(container, container.select(HIDDEN + ", " + LANDMARKS));
        removeNoiseByAttributes(container, total);
        cutAfterNoiseHeadings(container, total);
        removeLinkHeavyBlocks(container, total);
        cleanImages(container);
        pruneEmpty(container);
    }

    private static void removeDescendants(Element container, Elements doomed) {
        for (Element element : doomed) {
            if (element != container && element.parent() != null) {
                element.remove();
            }
        }
    }

    private static boolean removable(Element element, Element container, int total) {
        return element != container && element.parent() != null && element.text().length() <= total * MAX_REMOVABLE_SHARE;
    }

    private static void removeNoiseByAttributes(Element container, int total) {
        for (Element element : new ArrayList<>(container.getAllElements())) {
            if (removable(element, container, total) && hasToken(element, NOISE_TOKENS)) {
                element.remove();
            }
        }
    }

    private static boolean hasToken(Element element, Set<String> tokens) {
        String attributes = element.className() + " " + element.id();
        String spaced = attributes.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        for (String token : spaced.split("[^a-z0-9]+")) {
            if (tokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void cutAfterNoiseHeadings(Element container, int total) {
        for (Element element : new ArrayList<>(container.select("h2, h3, h4, h5, h6, p, strong, b, div, span, label"))) {
            if (element == container || element.parent() == null || !isNoiseHeading(element)) {
                continue;
            }
            List<Element> doomed = new ArrayList<>();
            doomed.add(element);
            doomed.addAll(element.nextElementSiblings());
            int doomedText = doomed.stream().mapToInt(e -> e.text().length()).sum();
            if (doomedText <= total * MAX_REMOVABLE_SHARE) {
                doomed.forEach(Element::remove);
            }
        }
    }

    private static boolean isNoiseHeading(Element element) {
        String text = element.text().trim().toLowerCase(Locale.ROOT).replaceAll("[:：]+$", "").trim();
        if (text.isEmpty() || text.length() > 60) {
            return false;
        }
        boolean heading = element.tagName().matches("h[2-6]");
        for (String phrase : NOISE_HEADINGS) {
            if (text.equals(phrase) || (heading && text.startsWith(phrase + " "))) {
                return true;
            }
        }
        return false;
    }

    private static void removeLinkHeavyBlocks(Element container, int total) {
        for (Element element : new ArrayList<>(container.select("ul, ol, div, section, table"))) {
            if (!removable(element, container, total)) {
                continue;
            }
            int length = element.text().length();
            Elements links = element.select("a[href]");
            if (length == 0 || length > MAX_LINK_BLOCK_CHARS || links.size() < 2) {
                continue;
            }
            int linkLength = links.stream().mapToInt(link -> link.text().length()).sum();
            if ((double) linkLength / length > LINK_DENSITY_LIMIT) {
                element.remove();
            }
        }
    }

    private static void cleanImages(Element container) {
        for (Element image : new ArrayList<>(container.select("img"))) {
            String src = realImageUrl(image);
            if (src.isBlank() || isNotContentImage(image, src)) {
                image.remove();
            } else {
                image.attr("src", src);
            }
        }
    }

    private static String realImageUrl(Element image) {
        for (String attribute : List.of("data-src", "data-lazy-src", "data-original")) {
            String value = image.attr(attribute).trim();
            if (!value.isEmpty() && !value.startsWith("data:")) {
                return value;
            }
        }
        String src = image.attr("src").trim();
        if (!src.isEmpty() && !src.startsWith("data:")) {
            return src;
        }
        String fromSet = lastSrcsetUrl(image.attr("data-srcset"));
        if (fromSet.isEmpty()) {
            fromSet = lastSrcsetUrl(image.attr("srcset"));
        }
        if (fromSet.isEmpty() && image.parent() != null && image.parent().tagName().equals("picture")) {
            Element source = image.parent().selectFirst("source[srcset]");
            fromSet = source == null ? "" : lastSrcsetUrl(source.attr("srcset"));
        }
        return fromSet;
    }

    private static String lastSrcsetUrl(String srcset) {
        if (srcset == null || srcset.isBlank()) {
            return "";
        }
        String[] candidates = srcset.split(",");
        String last = candidates[candidates.length - 1].trim();
        int space = last.indexOf(' ');
        return space < 0 ? last : last.substring(0, space);
    }

    private static boolean isNotContentImage(Element image, String src) {
        return isTiny(image.attr("width")) || isTiny(image.attr("height"))
                || TRACKING_IMAGE.matcher(src).find()
                || hasToken(image, IMAGE_NOISE_TOKENS);
    }

    private static boolean isTiny(String dimension) {
        try {
            return !dimension.isBlank() && Integer.parseInt(dimension.replaceAll("[^0-9]", "")) < MIN_IMAGE_SIDE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void pruneEmpty(Element container) {
        List<Element> all = new ArrayList<>(container.getAllElements());
        for (int i = all.size() - 1; i >= 0; i--) {
            Element element = all.get(i);
            if (element != container
                    && element.parent() != null
                    && PRUNABLE_TAGS.contains(element.tagName())
                    && element.text().isBlank()
                    && element.select("img").isEmpty()) {
                element.remove();
            }
        }
    }
}
