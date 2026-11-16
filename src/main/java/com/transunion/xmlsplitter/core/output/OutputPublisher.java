package com.transunion.xmlsplitter.core.output;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transunion.xmlsplitter.core.SplitException;

/**
 * Makes a run all-or-nothing. Parts are written into a hidden staging folder next to the output folder (same
 * file system, so the final rename is atomic) and the whole folder is renamed into place only after every part
 * has been written and verified. A failed run deletes the staging folder and leaves the output untouched.
 */
public class OutputPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutputPublisher.class);

    /** Creates the staging folder after checking that the output folder may be (re)written. */
    public Path prepare(Path outputDir, boolean overwrite) {
        Path target = normalize(outputDir);
        checkTarget(target, overwrite);
        try {
            Files.createDirectories(target.getParent());
            return Files.createDirectory(sibling(target, "staging"));
        } catch (IOException e) {
            throw new SplitException(SplitException.Reason.IO_FAILURE,
                    "Cannot create staging folder next to " + target + ": " + e.getMessage(), e);
        }
    }

    /**
     * Renames the staging folder to the output folder. With overwrite, the previous output is first moved aside
     * and only deleted once the new one is in place, so it is restored if the rename fails.
     */
    public void publish(Path staging, Path outputDir, boolean overwrite) {
        Path target = normalize(outputDir);
        checkTarget(target, overwrite);
        Path previous = null;
        try {
            if (Files.exists(target)) {
                if (isEmptyDirectory(target)) {
                    Files.delete(target);
                } else {
                    previous = sibling(target, "previous");
                    move(target, previous);
                }
            }
            move(staging, target);
        } catch (IOException e) {
            restore(previous, target);
            throw new SplitException(SplitException.Reason.IO_FAILURE,
                    "Cannot publish output to " + target + ": " + e.getMessage(), e);
        }
        if (previous != null) {
            deleteQuietly(previous);
        }
    }

    public void discard(Path staging) {
        deleteQuietly(staging);
    }

    private void checkTarget(Path target, boolean overwrite) {
        if (!Files.exists(target) || isEmptyDirectory(target)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            throw new SplitException(SplitException.Reason.OUTPUT_CONFLICT, target + " exists and is not a folder");
        }
        if (!overwrite) {
            throw new SplitException(SplitException.Reason.OUTPUT_CONFLICT,
                    "Output folder " + target + " is not empty; use --overwrite to replace it");
        }
        if (!Files.exists(target.resolve(ManifestWriter.MANIFEST_FILE))) {
            throw new SplitException(SplitException.Reason.OUTPUT_CONFLICT, "Refusing to overwrite " + target
                    + ": it has content but no " + ManifestWriter.MANIFEST_FILE + ", so it was not produced by this tool");
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Staging is a sibling of the target, so this only happens on exotic file systems.
            log.warn("Atomic rename not supported for {}; falling back to a regular move", to);
            Files.move(from, to);
        }
    }

    private static void restore(Path previous, Path target) {
        if (previous == null || Files.exists(target)) {
            return;
        }
        try {
            move(previous, target);
        } catch (IOException e) {
            log.error("Could not restore previous output; it is still available at {}", previous, e);
        }
    }

    private static boolean isEmptyDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static Path normalize(Path outputDir) {
        return outputDir.toAbsolutePath().normalize();
    }

    private static Path sibling(Path target, String purpose) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return target.resolveSibling("." + target.getFileName() + "." + purpose + "-" + id);
    }

    static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", root, e.getMessage());
        }
    }
}
