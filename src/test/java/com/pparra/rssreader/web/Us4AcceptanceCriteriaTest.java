package com.pparra.rssreader.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pparra.rssreader.domain.Source;
import com.pparra.rssreader.domain.SourceType;
import com.pparra.rssreader.repository.ArticleRepository;
import com.pparra.rssreader.repository.SourceRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** One test per acceptance criterion of US-4 (Import and export subscriptions, OPML). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Us4AcceptanceCriteriaTest {

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired ArticleRepository articles;

    @BeforeEach
    void clean() {
        articles.deleteAll();
        sources.deleteAll();
    }

    private static MockMultipartFile opml(String xml) {
        return new MockMultipartFile("file", "subscriptions.opml", "text/x-opml", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String opmlWith(String outlines) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml version=\"2.0\"><head><title>x</title></head><body>"
                + outlines + "</body></opml>";
    }

    @Test
    void uploadingAnOpmlFileCreatesASourcePerFeedEntry_skippingDuplicatesAndInvalidOnes_andShowsTheCounts() throws Exception {
        sources.save(new Source("https://existing.com/feed", "Existing", SourceType.RSS, "https://existing.com/feed", Instant.now()));
        String xml = opmlWith(
                "<outline text=\"Tech\">"
                        + "<outline type=\"rss\" text=\"Ana\" title=\"Ana's Blog\" xmlUrl=\"https://ana.com/feed\"/>"
                        + "<outline type=\"rss\" text=\"Folder child\"><outline type=\"rss\" text=\"Deep\" xmlUrl=\"https://deep.com/rss\"/></outline>"
                        + "</outline>"
                        + "<outline type=\"rss\" text=\"Existing again\" xmlUrl=\"https://existing.com/feed\"/>"
                        + "<outline type=\"rss\" text=\"Ana twice\" xmlUrl=\"https://ana.com/feed\"/>"
                        + "<outline type=\"rss\" text=\"No scheme\" xmlUrl=\"not-a-url\"/>"
                        + "<outline type=\"rss\" text=\"Ftp\" xmlUrl=\"ftp://x.com/feed\"/>"
                        + "<outline text=\"Just a label\"/>");

        mvc.perform(multipart("/sources/import").file(opml(xml)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/sources"))
                .andExpect(flash().attribute("message",
                        "Import finished: 2 added, 2 skipped as duplicates, 2 skipped as invalid"));

        assertThat(sources.findAll()).extracting(Source::getUrl)
                .containsExactlyInAnyOrder("https://existing.com/feed", "https://ana.com/feed", "https://deep.com/rss");
        Source ana = sources.findAll().stream().filter(s -> s.getUrl().equals("https://ana.com/feed")).findFirst().orElseThrow();
        assertThat(ana.getName()).isEqualTo("Ana's Blog");
        assertThat(ana.getType()).isEqualTo(SourceType.RSS);
        assertThat(ana.getFeedUrl()).isEqualTo("https://ana.com/feed");
    }

    @Test
    void exportingDownloadsAValidOpmlFileWithAllRssSources_andStatesHowManyNonFeedSourcesWereLeftOut() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana & Co", SourceType.RSS, null, Instant.now()));
        sources.save(new Source("https://blog.com", "Blog", SourceType.RSS, "https://blog.com/atom.xml", Instant.now()));
        sources.save(new Source("https://scraped.com", "Scraped", SourceType.SCRAPE, null, Instant.now()));
        sources.save(new Source("https://walled.com", "Walled", SourceType.UNSUPPORTED, null, Instant.now()));

        String body = mvc.perform(get("/sources/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"subscriptions.opml\""))
                .andExpect(header().string("X-Sources-Left-Out", "2"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(document.getDocumentElement().getTagName()).isEqualTo("opml");
        NodeList outlines = document.getElementsByTagName("outline");
        assertThat(outlines.getLength()).isEqualTo(2);
        Element first = (Element) outlines.item(0);
        assertThat(first.getAttribute("text")).isEqualTo("Ana & Co");
        assertThat(first.getAttribute("xmlUrl")).isEqualTo("https://ana.com/feed");
        Element second = (Element) outlines.item(1);
        assertThat(second.getAttribute("xmlUrl")).isEqualTo("https://blog.com/atom.xml");
        assertThat(second.getAttribute("htmlUrl")).isEqualTo("https://blog.com");

        mvc.perform(get("/sources"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2 sources have no feed and will be left out")));
    }

    @Test
    void anExportedFileCanBeImportedAgain() throws Exception {
        sources.save(new Source("https://ana.com/feed", "Ana", SourceType.RSS, null, Instant.now()));
        String exported = mvc.perform(get("/sources/export")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        sources.deleteAll();

        mvc.perform(multipart("/sources/import").file(opml(exported)))
                .andExpect(flash().attribute("message", "Import finished: 1 added, 0 skipped as duplicates, 0 skipped as invalid"));

        assertThat(sources.findAll()).extracting(Source::getUrl, Source::getName).containsExactly(
                org.assertj.core.groups.Tuple.tuple("https://ana.com/feed", "Ana"));
    }

    @Test
    void aMalformedFileIsRejectedWithAClearErrorAndChangesNothing() throws Exception {
        sources.save(new Source("https://keep.com/feed", "Keep", SourceType.RSS, null, Instant.now()));

        mvc.perform(multipart("/sources/import").file(opml("<opml><body><outline xmlUrl=\"https://a.com/f\"")))
                .andExpect(flash().attribute("error", "The file is not a valid OPML document. Nothing was imported."));
        mvc.perform(multipart("/sources/import").file(opml("<html><body/></html>")))
                .andExpect(flash().attribute("error", "The file is not an OPML document. Nothing was imported."));
        mvc.perform(multipart("/sources/import").file(new MockMultipartFile("file", "empty.opml", "text/xml", new byte[0])))
                .andExpect(flash().attribute("error", "Choose an OPML file to import"));

        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void anOversizedFileIsRejectedAndChangesNothing() throws Exception {
        String padding = "<!-- " + "x".repeat(2 * 1024 * 1024) + " -->";
        String xml = opmlWith(padding + "<outline type=\"rss\" text=\"A\" xmlUrl=\"https://a.com/feed\"/>");

        mvc.perform(multipart("/sources/import").file(opml(xml)))
                .andExpect(flash().attribute("error", "The file is too large (the limit is 2 MB). Nothing was imported."));

        assertThat(sources.count()).isZero();
    }

    @Test
    void aFileDeclaringExternalEntitiesIsRejected() throws Exception {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE opml [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<opml version=\"2.0\"><body><outline type=\"rss\" text=\"&xxe;\" xmlUrl=\"https://a.com/feed\"/></body></opml>";

        mvc.perform(multipart("/sources/import").file(opml(xml)))
                .andExpect(flash().attribute("error", "The file is not a valid OPML document. Nothing was imported."));

        assertThat(sources.count()).isZero();
    }
}
