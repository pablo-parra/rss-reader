package com.pparra.rssreader.fetch;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

/**
 * Finds the post's metadata blocks (byline, author photo, publication date, "follow us" kickers) by their
 * structure and wraps each one in an {@code <aside>}, which the reader shows small and muted. It works on
 * what a block says and where it sits (near the start or the end of the text), never on site-specific
 * class names, so it applies to every source.
 */
final class MetadataMarker {

    private static final int MAX_BLOCK_CHARS = 140;
    private static final int HEAD_CHARS = 1500;
    private static final int TAIL_CHARS = 800;
    private static final int MAX_DATE_CHARS = 60;
    private static final int MAX_DATE_LETTERS = 25;

    private static final Pattern BYLINE_PREFIX = Pattern.compile(
            "^(por|by|autor|autora|autores|escrito por|texto|texto de|words by|written by|text by|von|par)\\b[:\\s]+(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NAME_WORD = Pattern.compile("^(\\p{Lu}[\\p{L}.'’-]*|de|del|la|las|los|van|von|da|di|y|and|e)$",
            Pattern.UNICODE_CASE);
    private static final Pattern AUTHOR_HREF = Pattern.compile("/(autor|autora|autores|author|authors|writer|profile|people|firma|firmas)/",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_DATE = Pattern.compile("\\b(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,2})\\b");
    private static final Pattern WORD_DATE = Pattern.compile(
            "\\b(\\d{1,2}\\s+(de\\s+)?(" + months() + ")\\.?,?\\s+(de\\s+)?\\d{4}|(" + months() + ")\\.?\\s+\\d{1,2},?\\s+\\d{4})\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME = Pattern.compile("\\b\\d{1,2}[:.h]\\d{2}(\\s?(am|pm|h|hs))?\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> KICKER_PHRASES = List.of(
            "seguir en google", "síguenos en google", "siguenos en google", "follow us on google", "preferred source",
            "fuente preferida", "añádenos a google", "suscríbete", "subscribe to");

    private MetadataMarker() {
    }

    private static String months() {
        return "enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre|"
                + "ene|feb|mar|abr|may|jun|jul|ago|sep|sept|oct|nov|dic|"
                + "january|february|march|april|june|july|august|september|october|november|december|"
                + "jan|apr|aug|dec";
    }

    static void mark(Element container) {
        Map<Element, int[]> span = offsets(container);
        int total = span.get(container)[1];

        List<Element> blocks = new ArrayList<>();
        for (Element element : container.getAllElements()) {
            if (element == container || element.parent() == null || !inEdgeZone(span.get(element), total)) {
                continue;
            }
            if (isMetadata(element)) {
                blocks.add(outermostSmall(element, container));
            }
        }
        List<Element> marked = new ArrayList<>();
        for (Element block : blocks) {
            if (block.parent() != null && marked.stream().noneMatch(m -> m == block || isInside(block, m))) {
                marked.removeIf(m -> isInside(m, block));
                marked.add(block);
            }
        }
        List<String> authors = new ArrayList<>();
        for (Element block : marked) {
            for (Element link : block.select("a")) {
                authors.add(link.text().trim().toLowerCase(Locale.ROOT));
            }
        }
        for (Element image : container.select("img")) {
            if (!isInsideAny(image, marked) && !image.attr("alt").isBlank()
                    && authors.contains(image.attr("alt").trim().toLowerCase(Locale.ROOT))) {
                marked.add(image);
            }
        }
        for (Element block : marked) {
            if (block.parent() != null) {
                block.wrap("<aside></aside>");
            }
        }
    }

    private static boolean isInside(Element inner, Element outer) {
        for (Element p = inner.parent(); p != null; p = p.parent()) {
            if (p == outer) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideAny(Element element, List<Element> blocks) {
        return blocks.stream().anyMatch(b -> b == element || isInside(element, b));
    }

    private static boolean inEdgeZone(int[] range, int total) {
        return range[0] < HEAD_CHARS || range[1] > total - TAIL_CHARS;
    }

    /** Climbs while the parent is still a short block, so "Por + author + date" becomes one unit. */
    private static Element outermostSmall(Element element, Element container) {
        Element top = element;
        while (top.parent() != null && top.parent() != container && top.parent().text().length() <= MAX_BLOCK_CHARS) {
            top = top.parent();
        }
        return top;
    }

    static boolean isMetadata(Element element) {
        String text = element.text().trim();
        if (text.isEmpty() || text.length() > MAX_BLOCK_CHARS) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return isByline(element, text) || isDate(element, text) || KICKER_PHRASES.stream().anyMatch(lower::contains);
    }

    private static boolean isByline(Element element, String text) {
        if (!element.select("a[rel~=author], [itemprop=author]").isEmpty() && text.length() <= 100) {
            return true;
        }
        var prefix = BYLINE_PREFIX.matcher(text);
        if (!prefix.matches()) {
            return false;
        }
        String rest = prefix.group(2).trim();
        if (rest.isEmpty() || rest.length() > 80 || rest.endsWith(".")) {
            return false;
        }
        boolean authorLink = element.select("a").stream().anyMatch(a -> AUTHOR_HREF.matcher(a.attr("href")).find());
        return authorLink || looksLikeNames(rest);
    }

    private static boolean looksLikeNames(String rest) {
        String[] words = rest.split("[\\s,]+");
        if (words.length > 8) {
            return false;
        }
        for (String word : words) {
            if (!NAME_WORD.matcher(word).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDate(Element element, String text) {
        if (text.length() > MAX_DATE_CHARS) {
            return false;
        }
        boolean hasDate = NUMERIC_DATE.matcher(text).find() || WORD_DATE.matcher(text).find();
        boolean timeTag = !element.select("time").isEmpty() && text.chars().anyMatch(Character::isDigit);
        if (!hasDate && !timeTag) {
            return false;
        }
        String rest = WORD_DATE.matcher(NUMERIC_DATE.matcher(text).replaceAll(" ")).replaceAll(" ");
        rest = TIME.matcher(rest).replaceAll(" ").replaceAll("[^\\p{L}]", "");
        return rest.length() <= MAX_DATE_LETTERS;
    }

    /** Start and end offsets of each element's text within the container's text. */
    private static Map<Element, int[]> offsets(Element container) {
        Map<Element, int[]> ranges = new IdentityHashMap<>();
        int[] position = {0};
        container.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element e) {
                    ranges.put(e, new int[] {position[0], position[0]});
                } else if (node instanceof TextNode t) {
                    position[0] += t.text().length();
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e) {
                    ranges.get(e)[1] = position[0];
                }
            }
        });
        return ranges;
    }
}
