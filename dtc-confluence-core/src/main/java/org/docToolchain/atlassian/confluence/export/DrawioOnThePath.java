package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The draw.io desktop application, used as a command, where it happens to be installed.
 *
 * <p>Found by looking for the executable on the {@code PATH} and not by running it: a probe would
 * cost a process start on every export, and this starts an Electron application whose version
 * probe is not reliable - {@code drawio --version} answers with a Mach port error on a Mac with no
 * window server session, while the export itself works.</p>
 *
 * <p>Nothing here is required. {@link #found()} answering empty is the normal case, and the export
 * then reports a stale preview instead of refreshing it.</p>
 */
public final class DrawioOnThePath implements DiagramRenderer {

    /** What the application is called on every platform it ships for. */
    private static final List<String> NAMES = List.of("drawio", "drawio.exe", "drawio.cmd");

    /** Long enough for an Electron start plus a render, short enough not to hang an export. */
    private static final long PATIENCE_SECONDS = 120;

    /** The pages of an {@code mxfile}, in the order the viewer numbers them. */
    private static final Pattern DIAGRAM_ID =
            Pattern.compile("<diagram\\b[^>]*\\bid=\"([^\"]*)\"");

    private final Path executable;

    private DrawioOnThePath(Path executable) {
        this.executable = executable;
    }

    /**
     * @return the renderer, or empty where draw.io is not on this machine's {@code PATH}
     */
    public static Optional<DiagramRenderer> found() {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
            if (directory.isEmpty()) {
                continue;
            }
            for (String name : NAMES) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(new DrawioOnThePath(candidate));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<byte[]> render(String diagram, String aspect) {
        Path work = null;
        try {
            work = Files.createTempDirectory("dtc-drawio");
            Path source = work.resolve("diagram.drawio");
            Path target = work.resolve("diagram.png");
            Files.writeString(source, diagram, StandardCharsets.UTF_8);
            if (!run(source, target, pageOf(diagram, aspect)) || !Files.isRegularFile(target)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException | RuntimeException notDrawn) {
            System.out.println(">>> WARN: draw.io could not draw the diagram: "
                    + notDrawn.getMessage());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            removeQuietly(work);
        }
    }

    private boolean run(Path source, Path target, int page)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(executable.toString(),
                "--export", "--format", "png",
                "--page-index", String.valueOf(page),
                "--output", target.toString(), source.toString()));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            System.out.println(">>> WARN: draw.io took longer than " + PATIENCE_SECONDS
                    + " seconds, so the picture is left as it was");
            return false;
        }
        return process.exitValue() == 0;
    }

    /**
     * @return which page of the file to draw, counting from one - which is how draw.io has
     *         numbered them since version 27. A page the file does not hold is drawn as the first,
     *         because a diagram is better than an error.
     *
     *     <p>Package-private so that it can be stated on its own: the rest of this
     *     class cannot be, since it starts an application that may not be here.</p>
     */
    static int pageOf(String diagram, String aspect) {
        if (aspect == null || aspect.isEmpty()) {
            return 1;
        }
        Matcher pages = DIAGRAM_ID.matcher(diagram);
        for (int page = 1; pages.find(); page++) {
            if (aspect.equals(pages.group(1))) {
                return page;
            }
        }
        return 1;
    }

    private static void removeQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            entries.sorted((one, other) -> other.getNameCount() - one.getNameCount())
                    .forEach(DrawioOnThePath::deleteQuietly);
        } catch (IOException leftBehind) {
            System.out.println(">>> WARN: could not clean up " + directory + ": "
                    + leftBehind.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException leftBehind) {
            // A temporary file left in the temporary directory is not worth a word.
        }
    }

    /** @return where it was found, which is what the export says it is using */
    @Override
    public String toString() {
        return executable.toString();
    }
}
