package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.atlassian.confluence.page.PageTreeBuilder
import org.docToolchain.atlassian.confluence.page.PageDecorator
import org.docToolchain.atlassian.confluence.image.EmbeddedImage
import org.docToolchain.atlassian.confluence.image.ImageStore
import org.docToolchain.util.ContentHash
import org.docToolchain.atlassian.transformer.AdmonitionTransformer
import org.docToolchain.atlassian.transformer.CollapsibleTransformer
import org.docToolchain.atlassian.transformer.OpenApiTransformer
import org.docToolchain.atlassian.transformer.DescriptionListTransformer
import org.docToolchain.atlassian.transformer.HtmlTransformer
import org.docToolchain.atlassian.transformer.MarkTransformer
import org.docToolchain.atlassian.constants.ConfluenceTags

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

import java.nio.file.Path
import java.security.MessageDigest
import static groovy.io.FileType.FILES

import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV1
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV2
import org.docToolchain.atlassian.confluence.ConfluenceService

/**
 * Based on asciidoc2confluence script created by Ralf D. Mueller and Alexander Heusingfeld
 * https://github.com/rdmueller/asciidoc2confluence
 *
 * This task expects an HTML document created with AsciiDoctor
 * in the following style (default AsciiDoctor output)
 * <div class="sect1">
 *     <h2>Page Title</h2>
 *     <div class="sectionbody">
 *         <div class="sect2">
 *            <h3>Sub-Page Title</h3>
 *         </div>
 *         <div class="sect2">
 *            <h3>Sub-Page Title</h3>
 *         </div>
 *     </div>
 * </div>
 * <div class="sect1">
 *     <h2>Page Title</h2>
 *     ...
 * </div>
 *
 */
class Asciidoc2ConfluenceTask extends DocToolchainTask {

    private ConfigObject config
    private String docDir
    private ConfluenceService confluenceService
    private ConfluenceClient confluenceClient
    private baseUrl
    private Map allPages
    // #938-mksiva: global variable to hold input spaceKey passed in the Config.groovy
    private spaceKeyInput
    // configuration
    private confluenceSpaceKey
    private confluenceSubpagesForSections
    private confluencePagePrefix
    private confluencePageSuffix

    static Asciidoc2ConfluenceTask From(ConfigObject config, String docDir) {
        return new Asciidoc2ConfluenceTask(config, docDir)
    }

    Asciidoc2ConfluenceTask(ConfigObject config, String docDir) {
        super(config)
        this.config = config
        this.docDir = docDir
        confluenceService = new ConfluenceService(configService)
        confluenceClient = configService.getConfigProperty("confluence.useV1Api") ?
            new ConfluenceClientV1(configService) :
            new ConfluenceClientV2(configService)
    }

    // helper functions

    /**
     * An entry nobody configured is an empty ConfigObject rather than null, and 'as String' would
     * turn that into the literal '[:]'. A prefix nobody wrote is no prefix.
     *
     * @return the value as text, empty where nothing was configured
     */
    private static String asText(value) {
        (value == null || value instanceof ConfigObject) ? '' : value as String
    }

    /**
     * The same trap in the other direction: coercing an empty ConfigObject with 'as List' builds a
     * proxy whose iterator throws UnsupportedOperationException, which surfaces far from the cause.
     *
     * @return the value as a list, empty where nothing was configured
     */
    private static List asList(value) {
        if (value == null || value instanceof ConfigObject) {
            return []
        }
        return value instanceof List ? value : (value as List)
    }


    /**
     *  #342-dierk42
     *  Add labels to a Confluence page. Labels are taken from :keywords: which
     *  are converted as meta tags in HTML. Building the array: see below
     *
     *  Confluence allows adding labels only after creation of a page.
     *  Therefore we need extra API calls.
     *
     *  Currently the labels are added one by one. Suggestion for improvement:
     *  Build a label structure of all labels an place them with one call.
     *
     *  Replaces existing labels. No harm
     *  Does not check for deleted labels when keywords are deleted from source
     *  document!
     */
    def addLabels = { def pageId, def labelsArray ->
        // Attach each label in a API call of its own. The only prefix possible
        // in our own Confluence is 'global'
        labelsArray.each { label ->
            def label_data = [
                prefix : 'global',
                name : label
            ]
            confluenceClient.addLabel(pageId, label_data)
            println "added label ${label} to page ID ${pageId}"
        }
    }


    def uploadAttachment = { def pageId, String url, String fileName, String note ->
        def is
        def localHash
        if (url.startsWith('http')) {
            is = new URL(url).openStream()
            //build a hash of the attachment
            localHash = ContentHash.md5(new URL(url).openStream().text)
        } else {
            is = new File(url).newDataInputStream()
            //build a hash of the attachment
            localHash = ContentHash.md5(new File(url).newDataInputStream().text)
        }

        def attachment = confluenceClient.getAttachment(pageId, fileName)
        if (attachment?.results) {
            // attachment exists. need an update?
            if (confluenceClient.attachmentHasChanged(attachment, localHash)) {
                //hash is different -> attachment needs to be updated
                confluenceClient.updateAttachment(pageId, attachment.results[0].id, is, fileName, note, localHash)
                println "    updated attachment"
            }
        } else {
            confluenceClient.createAttachment(pageId, is, fileName, note, localHash)
        }
    }

    def realTitle(pageTitle){
        confluencePagePrefix + pageTitle + confluencePageSuffix
    }


    /**
     * # 352-LuisMuniz: Helper methods
     * Fetch all pages of the defined config ancestorsIds. Only keep relevant info in the pages Map
     * The map is indexed by lower-case title
     */
    def retrieveAllPages(String spaceKey) {
        // #938-mksiva: added a condition spaceKeyInput is null, if it is null, it means that, space key is different, so re fetch all pages.
        if (allPages != null && spaceKeyInput == null) {
            println "allPages already retrieved"
            allPages
        } else {
            def pageIds = []
            def checkSpace = false
            int pageLimit = config.confluence.pageLimit ? config.confluence.pageLimit : 100
            config.confluence.input.each { input ->
                if (!input.ancestorId) {
                    // if one ancestorId is missing we should scan the whole space
                    checkSpace = true;
                    return
                }
                pageIds.add(input.ancestorId)
            }
            println (".")

            if(checkSpace) {
                allPages = confluenceClient.fetchPagesBySpaceKey(spaceKey, pageLimit)
            } else {
                allPages = confluenceClient.fetchPagesByAncestorId(pageIds, pageLimit)
            }
            println("${allPages.size()} pages retrieved")
            allPages
        }
    }


    /**
     * Retrieve a page by id with contents and version
     */
    def retrieveFullPage = { String id ->
        println("retrieving page with id " + id)
        confluenceClient.retrieveFullPageById(id)
    }

    /**
     * if a parent has been specified, check whether a page has the same parent.
     */
    static boolean hasRequestedParent(Map existingPage, String requestedParentId) {
        if (requestedParentId) {
            existingPage.parentId == requestedParentId
        } else {
            true
        }
    }





    /**
     * modify local page in order to match the internal confluence storage representation a bit better
     * definition lists are not displayed by confluence, so turn them into tables
     * body can be of type Element or Elements
     */
    def parseBody(body, anchors, pageAnchors) {
        def uploads = []
        new OpenApiTransformer(configService.getConfigProperty('confluence.useOpenapiMacro'))
            .transformOpenApi(body)

        body.select('div.paragraph').unwrap()
        body.select('div.ulist').unwrap()
        //body.select('div.sect3').unwrap()
        new AdmonitionTransformer().transformAdmonitions(body)
        new CollapsibleTransformer().transformCollapsibles(body)
        //special for the arc42-template
        body.select('div.arc42help').select('.content')
            .wrap('<ac:structured-macro ac:name="expand"></ac:structured-macro>')
            .wrap('<ac:rich-text-body></ac:rich-text-body>')
            .wrap('<ac:structured-macro ac:name="info"></ac:structured-macro>')
            .before('<ac:parameter ac:name="title">arc42</ac:parameter>')
            .wrap('<ac:rich-text-body><p></p></ac:rich-text-body>')
        body.select('div.arc42help').unwrap()
        body.select('div.title').wrap("<strong></strong>").before("<br />").wrap("<div></div>")
        body.select('div.listingblock').wrap("<p></p>").unwrap()
        // see if we can find referenced images and fetch them
        new File("tmp/images/.").mkdirs()
        // find images, extract their URLs for later uploading (after we know the pageId) and replace them with this macro:
        // <ac:image ac:align="center" ac:width="500">
        // <ri:attachment ri:filename="deployment-context.png"/>
        // </ac:image>

        body.select('img').each { img ->
            def src = img.attr('src')
            def imgWidth = img.attr('width')?:500
            def imgAlign = img.attr('align')?:"center"

            //it is not an online image, so upload it to confluence and use the ri:attachment tag
            if(!src.startsWith("http")) {
                def sanitizedBaseUrl = baseUrl.toString().replaceAll('\\\\','/').replaceAll('/[^/]*$','/')
                def newUrl
                def fileName
                //it is an embedded image
                if(src.startsWith("data:image")){
                    def imageData = EmbeddedImage.parse(src)
                    def fileExtension = imageData.fileExtension()
                    fileName = img.attr('alt').replaceAll(/\s+/,"_").concat(".${fileExtension}")
                    def storedImage = new ImageStore(asList(config.imageDirs))
                        .store(sanitizedBaseUrl, fileName, fileExtension, imageData.content())
                    newUrl = storedImage.filePath()
                    fileName = storedImage.fileName()
                }else {
                    newUrl = sanitizedBaseUrl + src
                    fileName = URLDecoder.decode((src.tokenize('/')[-1]),"UTF-8")
                }
                newUrl = URLDecoder.decode(newUrl,"UTF-8")
                println "    image: "+newUrl
                uploads <<  [0,newUrl,fileName,"automatically uploaded"]
                img.after("<ac:image ac:align=\"${imgAlign}\" ac:width=\"${imgWidth}\"><ri:attachment ri:filename=\"${fileName}\"/></ac:image>")
            }
            // it is an online image, so we have to use the ri:url tag
            else {
                img.after("<ac:image ac:align=\"${imgAlign}\" ac:width=\"${imgWidth}\"><ri:url ri:value=\"${src}\"/></ac:image>")
            }
            img.remove()
        }


        if(config.confluence.enableAttachments){
            def attachmentPrefix = config.confluence.attachmentPrefix ? config.confluence.attachmentPrefix : 'attachment'
            body.select('a').each { link ->

                def src = link.attr('href')
                println "    attachment src: "+src

                //upload it to confluence and use the ri:attachment tag
                if(src.startsWith(attachmentPrefix)) {
                    def newUrl = baseUrl.toString().replaceAll('\\\\','/').replaceAll('/[^/]*$','/')+src
                    def fileName = URLDecoder.decode((src.tokenize('/')[-1]),"UTF-8")
                    newUrl = URLDecoder.decode(newUrl,"UTF-8")

                    uploads <<  [0,newUrl,fileName,"automatically uploaded non-image attachment by docToolchain"]
                    def uriArray=fileName.split("/")
                    def pureFilename = uriArray[uriArray.length-1]
                    def innerhtml = link.html()
                    link.after("<ac:structured-macro ac:name=\"view-file\" ac:schema-version=\"1\"><ac:parameter ac:name=\"name\"><ri:attachment ri:filename=\"${pureFilename}\"/></ac:parameter></ac:structured-macro>")
                    link.after("<ac:link><ri:attachment ri:filename=\"${pureFilename}\"/><ac:plain-text-link-body> <![CDATA[\"${innerhtml}\"]]></ac:plain-text-link-body></ac:link>")
                    link.remove()

                }
            }
        }

        new MarkTransformer().transformMarks(body)
        new DescriptionListTransformer().transformDescriptionLists(body)
        //not really sure if must check here the type
        String bodyString = body
        if(body instanceof Element){
            bodyString = body.html()
        }
        Element saneHtml = new Document("")
            .outputSettings(new Document.OutputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false))
            .html(bodyString)
        HtmlTransformer transformer = new HtmlTransformer()
        if(config.jira.api){
            transformer.withJiraIntegration(config.jira.api)
        }
        if(config.confluence.jiraServerId){
            transformer.usingOnPremiseJira(config.confluence.jiraServerId)
        }
        def pageString = transformer.transformToConfluenceFormat(saneHtml, anchors, pageAnchors, confluencePagePrefix, confluencePageSuffix)

        return Map.of(
            "page", pageString,
            "uploads", uploads
        )
    }


    /**
     * the create-or-update functionality for confluence pages
     * #342-dierk42: added parameter 'keywords'
     */
    def pushToConfluence(pageTitle, pageBody, parentId, anchors, pageAnchors, keywords) {
        parentId = parentId?.toString()

        def deferredUpload = []
        String realTitleLC = realTitle(pageTitle).toLowerCase()
        String realTitle = realTitle(pageTitle)

        //try to get an existing page
        def parsedBody = parseBody(pageBody, anchors, pageAnchors)
        def localPage = parsedBody.get("page")
        deferredUpload.addAll(parsedBody.get("uploads"))
        def localHash = ContentHash.md5(localPage)
        // Read through configService: an unset ConfigObject entry coerced with "as String"
        // becomes the literal "[:]", which would be published as page content.
        localPage = new PageDecorator(
            configService.getConfigProperty('confluence.disableToC') != null,
            configService.getConfigProperty('confluence.extraPageContent') as String,
            configService.getConfigProperty('confluence.tableOfContents') as String,
            configService.getConfigProperty('confluence.tableOfChildren') as String).decorate(localPage)

        // #938-mksiva: Changed the 3rd parameter from 'config.confluence.spaceKey' to 'confluenceSpaceKey' as it was always taking the default spaceKey
        // instead of the one passed in the input for each row.
        def pages = retrieveAllPages(confluenceSpaceKey)
        // println "Suche nach vorhandener Seite: " + pageTitle
        Map existingPage = pages[realTitleLC]
        def page

        if (existingPage) {
            if (hasRequestedParent(existingPage, parentId)) {
                page = retrieveFullPage(existingPage.id as String)
            } else {
                page = null
            }
        } else {
            page = null
        }

        if (page) {
            println "found existing page: " + page.id +" version "+page.version.number

            //extract hash from remote page to see if it is different from local one
            def remotePage = page.body.storage.value.toString().trim()

            def remoteHash = remotePage =~ /(?ms)hash: #([^#]+)#/
            remoteHash = remoteHash.size()==0?"":remoteHash[0][1]

            // println "remoteHash: " + remoteHash
            // println "localHash:  " + localHash

            if (remoteHash == localHash) {
                println "page hasn't changed!"
                deferredUpload.each {
                    uploadAttachment(page?.id, it[1], it[2], it[3])
                }
                deferredUpload = []
                // #324-dierk42: Add keywords as labels to page.
                if (keywords) {
                    addLabels(page.id, keywords)
                }
                return page.id
            } else {
                def newPageVersion = (page.version.number as Integer) + 1

                confluenceClient.updatePage(
                    page.id,
                    realTitle,
                    confluenceSpaceKey,
                    localPage,
                    newPageVersion,
                    config.confluence.pageVersionComment ?: '',
                    parentId
                )
                println "> updated page "+page.id
                deferredUpload.each {
                    uploadAttachment(page.id, it[1], it[2], it[3])
                }
                deferredUpload = []
                // #324-dierk42: Add keywords as labels to page.
                if (keywords) {
                    addLabels(page.id, keywords)
                }
                return page.id
            }
        } else {
            //#352-LuisMuniz if the existing page's parent does not match the requested parentId, fail
            if (existingPage && !hasRequestedParent(existingPage, parentId)) {
                throw new IllegalArgumentException("Cannot create page, page with the same "
                    + "title=${existingPage.title} "
                    + "with id=${existingPage.id} already exists in the space. "
                    + "A Confluence page title must be unique within a space, consider specifying a 'confluencePagePrefix' in ConfluenceConfig.groovy")
            }
            //create a page
            page = confluenceClient.createPage(
                realTitle,
                confluenceSpaceKey,
                localPage,
                config.confluence.pageVersionComment ?: '',
                parentId
            )
            println "> created page "+page?.id
            deferredUpload.each {
                uploadAttachment(page?.id, it[1], it[2], it[3])
            }
            deferredUpload = []
            // #324-dierk42: Add keywords as labels to page.
            if (keywords) {
                addLabels(page?.id, keywords)
            }
            return page?.id
        }
    }


    def pushPages(pages, anchors, pageAnchors, labels) {
        pages.each { page ->
            page.title = page.title.trim()
            println page.title
            def id = pushToConfluence page.title, page.body, page.parent, anchors, pageAnchors, labels
            page.children*.parent = id
            pushPages page.children, anchors, pageAnchors, labels
        }
    }



    def retrievePageIdByName = { String name ->
        def data = confluenceClient.retrievePageIdByName(name, confluenceSpaceKey)
        return data?.results?.get(0)?.id
    }


    void execute() {
        if(config.confluence.inputHtmlFolder) {
            def htmlFolder = "${docDir}/${config.confluence.inputHtmlFolder}"
            println "Starting processing files in folder: " + config.confluence.inputHtmlFolder
            def dir = new File(htmlFolder)

            dir.eachFileRecurse (FILES) { fileName ->
                if (fileName.isFile()){
                    def map = [file: config.confluence.inputHtmlFolder+fileName.getName()]
                    config.confluence.input.add(map)
                }
            }
        }

        config.confluence.input.each { input ->
            // TODO check why this is necessary
            if(input.file) {
                input.file = confluenceService.checkAndBuildCanonicalFileName(input.file)
                //  assignend, but never used in pushToConfluence(...) (fixed here)
                // #938-mksiva: assign spaceKey passed for each file in the input
                spaceKeyInput = input.spaceKey
                confluenceSpaceKey = input.spaceKey ?: config.confluence.spaceKey
                def confluenceCreateSubpages = (input.createSubpages != null) ? input.createSubpages : config.confluence.createSubpages
                def confluenceAllInOnePage = (input.allInOnePage != null) ? input.allInOnePage : config.confluence.allInOnePage
                if (!(confluenceCreateSubpages instanceof ConfigObject && confluenceAllInOnePage instanceof ConfigObject)) {
                    println "ERROR:"
                    println "Deprecated configuration, migrate as follows:"
                    println "allInOnePage = true -> subpagesForSections = 0"
                    println "allInOnePage = false && createSubpages = false -> subpagesForSections = 1"
                    println "allInOnePage = false && createSubpages = true -> subpagesForSections = 2"
                    throw new RuntimeException("config problem")
                }
                confluenceSubpagesForSections = (input.subpagesForSections != null) ? input.subpagesForSections : config.confluence.subpagesForSections

                if (confluenceSubpagesForSections instanceof ConfigObject) {
                    confluenceSubpagesForSections = 1
                }
                //  hard to read in case of using :sectnums: -> so we add a suffix
                confluencePagePrefix = asText(input.pagePrefix ?: config.confluence.pagePrefix)
                //  added
                confluencePageSuffix = asText(input.pageSuffix ?: config.confluence.pageSuffix)
                def confluencePreambleTitle = input.preambleTitle ?: config.confluence.preambleTitle
                if (!(confluencePreambleTitle instanceof ConfigObject)) {
                    println "ERROR:"
                    println "Deprecated configuration, use first level heading in document instead of preambleTitle configuration"
                    throw new RuntimeException("config problem")
                }
                File htmlFile = new File(input.file)

                println "Publish ${input.file} to $confluenceSpaceKey at ${config.confluence.api} ..."
                baseUrl = htmlFile
                Document dom = confluenceService.parseFile(htmlFile)

                // if ancestorName is defined try to find machingAncestorId in confluence
                def retrievedAncestorId
                if (input.ancestorName) {
                    // Retrieve a page id by name
                    retrievedAncestorId = retrievePageIdByName(input.ancestorName)
                    println("Retrieved pageId for given ancestorName '${input.ancestorName}' is ${retrievedAncestorId}")
                }
                // if input does not contain an ancestorName, check if there is ancestorId, otherwise check if there is a global one
                def parentId = retrievedAncestorId ?: input.ancestorId ?: config.confluence.ancestorId

                // if parentId is still not set, create a new parent page (parentId = null)
                parentId = parentId ?: null
                //println("ancestorName: '${input.ancestorName}', ancestorId: ${input.ancestorId} ---> final parentId: ${parentId}")

                // #342-dierk42: get the keywords from the meta tags
                def keywords = confluenceService.getKeywords(dom)

                def tree = new PageTreeBuilder().build(dom, parentId, confluenceSubpagesForSections)
                def pages = tree.pages
                def anchors = tree.anchors
                def pageAnchors = tree.pageAnchors
                //println "Pages: ${pages.size()}"
                //pages.each { page ->
                //    println "$page"
                //}
                pushPages pages, anchors, pageAnchors, keywords
                if (parentId) {
                    println "published to ${config.confluence.api - "rest/api/"}/spaces/${confluenceSpaceKey}/pages/${parentId}"
                } else {
                    println "published to ${config.confluence.api - "rest/api/"}/spaces/${confluenceSpaceKey}"
                }
            }
        }
    }
}

