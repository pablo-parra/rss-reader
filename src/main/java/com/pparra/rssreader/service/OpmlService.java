package com.pparra.rssreader.service;

import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.repository.SourceRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@Service
public class OpmlService {

    static final int MAX_BYTES = 2 * 1024 * 1024;

    private final SourceRepository sourceRepository;

    public OpmlService(SourceRepository sourceRepository) {
        this.sourceRepository = sourceRepository;
    }

    /**
     * Creates an RSS source for every outline that has an {@code xmlUrl}. The whole file is parsed before anything is
     * stored, so a malformed or oversized file changes nothing; one bad entry never aborts the rest.
     */
    public OpmlImportSummary importOpml(InputStream input) {
        Document document = parse(input);
        NodeList outlines = document.getElementsByTagName("outline");

        Set<String> seen = new HashSet<>();
        int added = 0;
        int duplicates = 0;
        int invalid = 0;
        for (int i = 0; i < outlines.getLength(); i++) {
            Element outline = (Element) outlines.item(i);
            String raw = outline.getAttribute("xmlUrl").trim();
            if (raw.isEmpty()) {
                continue; // a folder or a non-feed entry, not something to import
            }
            String url = feedUrlOf(raw);
            if (url == null) {
                invalid++;
            } else if (!seen.add(url) || sourceRepository.existsByUrlOrFeedUrl(url, url)) {
                duplicates++;
            } else if (store(outline, url)) {
                added++;
            } else {
                duplicates++;
            }
        }
        return new OpmlImportSummary(added, duplicates, invalid);
    }

    public OpmlExport exportOpml() {
        List<Source> all = sourceRepository.findAllByOrderByNameAsc();
        List<Source> feeds = all.stream().filter(source -> source.getType() == SourceType.RSS).toList();

        Document document = newDocument();
        Element opml = document.createElement("opml");
        opml.setAttribute("version", "2.0");
        document.appendChild(opml);

        Element head = document.createElement("head");
        head.appendChild(textElement(document, "title", "RSS Reader subscriptions"));
        head.appendChild(textElement(document, "dateCreated",
                DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC))));
        opml.appendChild(head);

        Element body = document.createElement("body");
        opml.appendChild(body);
        for (Source source : feeds) {
            Element outline = document.createElement("outline");
            outline.setAttribute("type", "rss");
            outline.setAttribute("text", source.getName());
            outline.setAttribute("title", source.getName());
            outline.setAttribute("xmlUrl", source.getFeedUrl() != null ? source.getFeedUrl() : source.getUrl());
            outline.setAttribute("htmlUrl", source.getUrl());
            body.appendChild(outline);
        }
        return new OpmlExport(serialize(document), feeds.size(), all.size() - feeds.size());
    }

    private boolean store(Element outline, String url) {
        String title = outline.getAttribute("title").trim();
        String text = outline.getAttribute("text").trim();
        String name = !title.isEmpty() ? title : !text.isEmpty() ? text : SourceService.hostOf(url);
        try {
            sourceRepository.save(new Source(url, name, SourceType.RSS, url, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // the same URL was stored in the meantime
        }
    }

    private static String feedUrlOf(String raw) {
        if (!raw.toLowerCase(Locale.ROOT).matches("^https?://.+")) {
            return null;
        }
        try {
            return SourceService.normalize(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Document parse(InputStream input) {
        byte[] bytes;
        try {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            throw new InvalidOpmlException("The file could not be read", e);
        }
        if (bytes.length == 0) {
            throw new InvalidOpmlException("The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new InvalidOpmlException("The file is too large (the limit is 2 MB)");
        }
        try {
            DocumentBuilder builder = secureBuilder();
            Document document = builder.parse(new java.io.ByteArrayInputStream(bytes));
            if (!"opml".equalsIgnoreCase(document.getDocumentElement().getTagName())) {
                throw new InvalidOpmlException("The file is not an OPML document");
            }
            return document;
        } catch (SAXException | IOException e) {
            throw new InvalidOpmlException("The file is not a valid OPML document", e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** No DTDs and no external entities, which rules out XXE and entity-expansion attacks. */
    private static DocumentBuilder secureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder;
    }

    private static Document newDocument() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Element textElement(Document document, String name, String text) {
        Element element = document.createElement(name);
        element.setTextContent(text);
        return element;
    }

    private static String serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }
}
