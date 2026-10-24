package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The draw.io desktop application, used as a command, where it happens to be installed.
 *
 * <p>Found by looking for the executable on the {@code PATH} and not by running it: a probe at
 * discovery would cost a process start on every export, and most exports have nothing to redraw.
 * What it says it is, is asked on the first picture that wants redrawing, because the one thing
 * that has to be established is which way it counts pages - see {@link #pageOf}.</p>
 *
 * <p>Nothing here is required. {@link #found()} answering empty is the normal case, and the export
 * then reports a stale preview instead of refreshing it. An installation that cannot be shown to
 * count pages from one is the same case: it is not used, and the preview is left as it is.</p>
 */
public final class DrawioOnThePath implements DiagramRenderer {

    /** What the application is called on every platform it ships for. */
    private static final List<String> NAMES = List.of("drawio", "drawio.exe", "drawio.cmd");

    /** Long enough for an Electron start plus a render, short enough not to hang an export. */
    private static final long PATIENCE_SECONDS = 120;

    /** The pages of an {@code mxfile}, in the order the viewer numbers them. */
    private static final Pattern DIAGRAM_ID =
            Pattern.compile("<diagram\\b[^>]*\\bid=\"([^\"]*)\"");

    /**
     * A version at the start of a line, which is where both {@code --version} and the first line
     * of {@code --help} put it. Anchored so that a number out of a log line cannot pass for one:
     * the application writes its diagnostics to the same stream, each line starting with
     * {@code [}.
     */
    private static final Pattern VERSION =
            Pattern.compile("(?m)^[ \\t]*(?:draw\\.io[ \\t]+)?v?(\\d+)\\.(\\d+)\\.(\\d+)\\b");

    /** The first version that numbers pages from one, which is what this supplies. */
    private static final int[] COUNTS_FROM_ONE = {27, 0, 2};

    private final Path executable;

    /** What it says it is, asked once; {@code null} until it has been asked. */
    private String version;

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
        OptionalInt page = pageOf(diagram, aspect, version());
        if (page.isEmpty()) {
            System.out.println(">>> WARN: " + executable + " does not say it is 27.0.2 or newer,"
                    + " which is where --page-index began counting from one, so the picture is"
                    + " left as it is. Drawn with the other convention it would show the view"
                    + " after the one asked for.");
            return Optional.empty();
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("dtc-drawio");
            Path source = work.resolve("diagram.drawio");
            Path target = work.resolve("diagram.png");
            Files.writeString(source, diagram, StandardCharsets.UTF_8);
            if (!run(source, target, page.getAsInt()) || !Files.isRegularFile(target)) {
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
     * @param version what this draw.io says it is, which decides whether it can be used at all
     * @return which page of the file to draw, counting from one, or empty where that convention
     *         is not this installation's. A page the file does not hold is drawn as the first,
     *         because a diagram is better than an error.
     *
     *     <p>Measured, on the 32.6.1 this was written against: {@code --page-index 0} is answered
     *     with "pages are numbered from 1 (0-based before v27.0.2)" and nothing is drawn, which is
     *     where the threshold comes from. Before it, a one-based index therefore drew the page
     *     after the one asked for - and a refresh that overwrote a preview with the following view
     *     would be worse than the stale picture of the right one it replaced. So a version that
     *     cannot be read, or that reads as older, refuses rather than guesses.</p>
     *
     *     <p>Package-private so that it can be stated on its own: the rest of this
     *     class cannot be, since it starts an application that may not be here.</p>
     */
    static OptionalInt pageOf(String diagram, String aspect, String version) {
        if (!countsPagesFromOne(version)) {
            return OptionalInt.empty();
        }
        if (aspect == null || aspect.isEmpty()) {
            return OptionalInt.of(1);
        }
        Matcher pages = DIAGRAM_ID.matcher(diagram);
        for (int page = 1; pages.find(); page++) {
            if (aspect.equals(pages.group(1))) {
                return OptionalInt.of(page);
            }
        }
        return OptionalInt.of(1);
    }

    /**
     * @return whether a draw.io saying this counts pages from one. Nothing readable is a no: the
     *         convention it uses is then unknown, and unknown is not established.
     */
    private static boolean countsPagesFromOne(String said) {
        Matcher version = said == null ? null : VERSION.matcher(said);
        if (version == null || !version.find()) {
            return false;
        }
        for (int part = 0; part < COUNTS_FROM_ONE.length; part++) {
            int found = Integer.parseInt(version.group(part + 1));
            if (found != COUNTS_FROM_ONE[part]) {
                return found > COUNTS_FROM_ONE[part];
            }
        }
        return true;
    }

    /**
     * @return what {@code drawio --version} printed, which is all that is read of it. Whatever it
     *         exits with: on a Mac without a window server session the application prints what it
     *         was asked for and then fails at exit over a Mach port, and the answer is still the
     *         answer. Where nothing is printed, nothing is established and no picture is drawn.
     */
    private String version() {
        if (version == null) {
            version = probe();
        }
        return version;
    }

    private String probe() {
        try {
            Process process = new ProcessBuilder(executable.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String said;
            try (var output = process.getInputStream()) {
                // Read before waiting: a full pipe would otherwise hold the process at its write.
                said = new String(output.readAllBytes(), StandardCharsets.UTF_8);
            }
            return process.waitFor(PATIENCE_SECONDS, TimeUnit.SECONDS) ? said : "";
        } catch (IOException | RuntimeException notAsked) {
            System.out.println(">>> WARN: " + executable + " could not be asked what it is: "
                    + notAsked.getMessage());
            return "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
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
