package org.paulsens.trip.migrate;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

/**
 * The ledger migration's file writes. {@code rollback.json} is rewritten before every binding a live run writes,
 * so a rewrite must never leave the file empty or cut off: the new text replaces the old in one move.
 */
public class LedgerFilesTest {

    private static List<String> namesIn(final Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void aRewriteReplacesTheFileInOneMoveAndLeavesNothingBehind() throws IOException {
        final Path dir = LedgerFiles.dir(Files.createTempDirectory("ledger-files").resolve("run"));
        final Path file = dir.resolve("rollback.json");
        LedgerFiles.write(file, "first\n");
        LedgerFiles.write(file, "second, and longer than the first\n");
        LedgerFiles.write(file, "third\n");
        assertEquals(Files.readString(file), "third\n", "the whole new text, none of the longer old one");
        assertEquals(namesIn(dir), List.of("rollback.json"), "the temporary file was moved, not left beside it");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "rw-------",
                    "the owner's alone from the moment it exists, a rewrite included");
        }
    }

    @Test
    public void aWriteThatCannotFinishLeavesTheOldFileWholeAndNoTemporaryOne() throws IOException {
        final Path dir = LedgerFiles.dir(Files.createTempDirectory("ledger-files").resolve("run"));
        final Path file = dir.resolve("rollback.json");
        LedgerFiles.write(file, "what was already recorded\n");
        // A directory cannot be replaced by a file: the move fails after the temporary file was fully written,
        // which is the interrupted rewrite. In-place truncation would have emptied the target before failing.
        final Path blocked = dir.resolve("blocked");
        Files.createDirectories(blocked.resolve("child"));
        assertThrows(IOException.class, () -> LedgerFiles.write(blocked, "never lands\n"));
        assertEquals(Files.readString(file), "what was already recorded\n");
        assertEquals(namesIn(dir), List.of("blocked", "rollback.json"), "and the failed write cleaned up after itself");
    }
}
