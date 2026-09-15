package org.docToolchain.atlassian.confluence.publish;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.docToolchain.atlassian.transformer.AdmonitionTransformer;
import org.docToolchain.atlassian.transformer.CalloutStyle;
import org.docToolchain.atlassian.transformer.CollapsibleTransformer;
import org.docToolchain.atlassian.transformer.DescriptionListTransformer;
import org.docToolchain.atlassian.transformer.HtmlTransformer;
import org.docToolchain.atlassian.transformer.MarkTransformer;
import org.docToolchain.atlassian.transformer.OpenApiTransformer;
import org.docToolchain.configuration.ConfigService;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

/**
 * Turns the HTML of one page into Confluence storage format.
 *
 * <p>Asciidoctor's output and Confluence's storage format overlap only in the plain parts. Every
 * transformer here takes one construct that does not survive the crossing - an admonition, a code
 * block, a collapsible section, a definition list - and writes the macro Confluence has for it.</p>
 */
public class BodyBuilder {

    /** Where the configuration names no prefix, this is what marks a link as an attachment. */
    private static final String DEFAULT_ATTACHMENT_PREFIX = "attachment";

    private final ConfigService configService;
    private final String baseUrl;
    private final String pagePrefix;
    private final String pageSuffix;

    /**
     * @param baseUrl    the HTML file being published, which its images are relative to
     * @param pagePrefix what is put in front of every page title, for a link to find the page
     * @param pageSuffix and what is put behind it
     */
    public BodyBuilder(ConfigService configService, String baseUrl,
                       String pagePrefix, String pageSuffix) {
        this.configService = configService;
        this.baseUrl = baseUrl;
        this.pagePrefix = pagePrefix;
        this.pageSuffix = pageSuffix;
    }

    /**
     * @param anchors     element id to the title of the page it ended up on
     * @param pageAnchors the same for section headings, which name a page rather than a position
     */
    public PublishedBody build(Element body, Map<String, String> anchors,
                               Map<String, String> pageAnchors) {
        List<Upload> uploads = new ArrayList<>();

        new OpenApiTransformer(configService.getConfigProperty("confluence.useOpenapiMacro"))
                .transformOpenApi(body);
        // Asciidoctor wraps a paragraph and a list in a div that carries nothing Confluence reads.
        body.select("div.paragraph").unwrap();
        body.select("div.ulist").unwrap();
        new AdmonitionTransformer().transformAdmonitions(body);
        new CollapsibleTransformer().transformCollapsibles(body);
        transformArc42Help(body);
        body.select("div.title").wrap("<strong></strong>").before("<br />").wrap("<div></div>");
        body.select("div.listingblock").wrap("<p></p>").unwrap();

        uploads.addAll(new ImageTransformer(baseUrl, imageDirs()).transformImages(body));
        uploads.addAll(transformAttachmentLinks(body));

        new MarkTransformer().transformMarks(body);
        new DescriptionListTransformer().transformDescriptionLists(body);

        return new PublishedBody(toStorageFormat(body, anchors, pageAnchors), uploads);
    }

    /**
     * An arc42 template marks its guidance as {@code div.arc42help}; folded away, it stays
     * available without standing in front of what the author wrote.
     */
    private static void transformArc42Help(Element body) {
        body.select("div.arc42help").select(".content")
                .wrap("<ac:structured-macro ac:name=\"expand\"></ac:structured-macro>")
                .wrap("<ac:rich-text-body></ac:rich-text-body>")
                .wrap("<ac:structured-macro ac:name=\"info\"></ac:structured-macro>")
                .before("<ac:parameter ac:name=\"title\">arc42</ac:parameter>")
                .wrap("<ac:rich-text-body><p></p></ac:rich-text-body>");
        body.select("div.arc42help").unwrap();
    }

    /**
     * Turns a link to a local file into an attachment of the page, where the configuration asks
     * for it. Off by default, because a link to a path is more often meant as a link.
     */
    private List<Upload> transformAttachmentLinks(Element body) {
        List<Upload> uploads = new ArrayList<>();
        if (configService.getConfigProperty("confluence.enableAttachments") == null) {
            return uploads;
        }
        Object configured = configService.getConfigProperty("confluence.attachmentPrefix");
        String prefix = configured == null ? DEFAULT_ATTACHMENT_PREFIX : String.valueOf(configured);
        String directory = baseUrl.replaceAll("\\\\", "/").replaceAll("/[^/]*$", "/");

        for (Element link : body.select("a")) {
            String source = link.attr("href");
            System.out.println("    attachment src: " + source);
            if (!source.startsWith(prefix)) {
                continue;
            }
            String fileName = decode(source.substring(source.lastIndexOf('/') + 1));
            uploads.add(new Upload(decode(directory + source), fileName,
                    "automatically uploaded non-image attachment by docToolchain"));
            String text = link.html();
            // Escaped, because the fragment is parsed again: a quote in the name would close
            // the attribute and cut the name short.
            String named = Entities.escape(fileName);
            link.after("<ac:structured-macro ac:name=\"view-file\" ac:schema-version=\"1\">"
                    + "<ac:parameter ac:name=\"name\"><ri:attachment ri:filename=\"" + named
                    + "\"/></ac:parameter></ac:structured-macro>");
            link.after("<ac:link><ri:attachment ri:filename=\"" + named + "\"/>"
                    + "<ac:plain-text-link-body> <![CDATA[\"" + text + "\"]]>"
                    + "</ac:plain-text-link-body></ac:link>");
            link.remove();
        }
        return uploads;
    }

    private String toStorageFormat(Element body, Map<String, String> anchors,
                                   Map<String, String> pageAnchors) {
        // Re-parsed as XML: storage format is XHTML, and the HTML parser would close tags it
        // knows differently from the ac: and ri: elements that are about to be written.
        Element sane = new Document("")
                .outputSettings(new Document.OutputSettings()
                        .syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false))
                .html(body.html());

        HtmlTransformer transformer = new HtmlTransformer()
                .withCallouts(CalloutStyle.from(configService.getConfigProperty("confluence.callouts")));
        Object jiraApi = configService.getConfigProperty("jira.api");
        if (jiraApi != null) {
            transformer.withJiraIntegration(String.valueOf(jiraApi));
        }
        Object jiraServerId = configService.getConfigProperty("confluence.jiraServerId");
        if (jiraServerId != null) {
            transformer.usingOnPremiseJira(String.valueOf(jiraServerId));
        }
        return transformer.transformToConfluenceFormat(sane, anchors, pageAnchors,
                pagePrefix, pageSuffix);
    }

    /**
     * @return where to look for an image, empty where nothing was configured - coercing an unset
     *         entry to a list builds a proxy whose iterator throws
     */
    private List<String> imageDirs() {
        Object configured = configService.getConfigProperty("imageDirs");
        if (!(configured instanceof Collection<?> directories)) {
            return List.of();
        }
        return directories.stream().map(String::valueOf).toList();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            return value;
        }
    }
}
