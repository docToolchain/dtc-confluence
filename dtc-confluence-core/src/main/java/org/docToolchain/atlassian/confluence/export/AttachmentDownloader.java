package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the attachments of an exported tree to disk, where the converter expects to find them.
 *
 * <p>The path an exported document refers to is
 * {@code images/<folders of the page>/<version>_<filename>}. The version is part of the name
 * because a document may refer to an older revision of a diagram, and the folders mirror the page
 * tree so that two pages may each have an {@code overview.png}.</p>
 *
 * <p>A file that cannot be fetched is reported and skipped. An export of a few hundred pages that
 * stops at one broken attachment is worth less than one that says which files are missing.</p>
 */
public class AttachmentDownloader {

    /** The directory below the destination that every attachment is written into. */
    private static final String IMAGE_DIRECTORY = "images";

    private final ConfluenceReader reader;

    public AttachmentDownloader(ConfluenceReader reader) {
        this.reader = reader;
    }

    /**
     * @param destination the docs directory; files land under its images folder
     * @return the names of the attachments that could not be written
     */
    public List<String> downloadAll(ExportedTree tree, File destination) {
        List<String> failed = new ArrayList<>();
        Path imageRoot = destination.toPath().resolve(IMAGE_DIRECTORY).toAbsolutePath().normalize();
        for (Map<String, Object> attachment : tree.getAttachments().values()) {
            String name = fileNameOf(tree, attachment);
            String downloadUrl = textOf(attachment.get("downloadUrl"));
            if (downloadUrl.isEmpty()) {
                System.out.println(">>> WARN: " + name + " has no download link, skipping it");
                failed.add(name);
                continue;
            }
            try {
                byte[] content = reader.download(downloadUrl);
                if (content == null) {
                    System.out.println(">>> WARN: " + name + " is no longer there, skipping it");
                    failed.add(name);
                    continue;
                }
                write(imageRoot, name, content);
            } catch (IOException | RuntimeException e) {
                System.out.println(">>> WARN: " + name + " could not be fetched: " + e.getMessage());
                failed.add(name);
            }
        }
        return failed;
    }

    /**
     * Writes one attachment below the image directory, and nowhere else.
     *
     * <p>The name is Confluence's, so it is the remote end that decides what it says. A name that
     * resolves outside the image directory is refused rather than written: an export must not be
     * able to put a file anywhere it likes on the machine that runs it.</p>
     */
    private void write(Path imageRoot, String name, byte[] content) throws IOException {
        Path target = imageRoot.resolve(name).normalize();
        if (!target.startsWith(imageRoot)) {
            throw new IOException("its name would write it outside " + imageRoot);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    /**
     * @return the path below images/ that the exported document refers to
     */
    private String fileNameOf(ExportedTree tree, Map<String, Object> attachment) {
        List<String> folders =
                PageNaming.folderStructure(tree.getPages(), textOf(attachment.get("pageId")));
        String prefix = folders.isEmpty() ? "" : String.join("/", folders) + "/";
        return prefix + textOf(attachment.get("version")) + "_"
                + PageNaming.sanitizeAttachmentName(textOf(attachment.get("filename")));
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
