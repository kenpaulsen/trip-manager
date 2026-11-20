package org.paulsens.trip.migrate;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

/**
 * Where the ledger migration writes: every file of a run names people, rows or amounts, so the directory and
 * each file in it are the owner's alone (0700 and 0600) from the moment they exist, not after a later chmod.
 */
final class LedgerFiles {
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_ONLY_OWNER = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_ONLY_OWNER = PosixFilePermissions.fromString("rw-------");

    private LedgerFiles() {
    }

    /** Creates the directory if need be and closes it to everyone but its owner. */
    static Path dir(final Path dir) throws IOException {
        Files.createDirectories(dir);
        if (POSIX) {
            Files.setPosixFilePermissions(dir, DIR_ONLY_OWNER);
        }
        return dir;
    }

    /**
     * Replaces the file's content in one step: the text goes to a temporary file beside it (the owner's alone
     * from its creation) which is then moved over the file. {@code rollback.json} is rewritten before every
     * binding a live run writes, and truncating it in place meant an interrupted rewrite left it empty or cut
     * off: the record of what was already written gone, and neither {@code rollback} nor a re-run able to read
     * it. The temporary file has ONE name per target (this tool is one process writing one file at a time), so
     * what an interrupted run left behind is replaced by the next write, never accumulated.
     */
    static void write(final Path file, final String text) throws IOException {
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        if (POSIX) {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(FILE_ONLY_OWNER));
        }
        try {
            Files.writeString(temp, text);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void writeLines(final Path file, final List<String> lines) throws IOException {
        write(file, String.join("\n", lines) + "\n");
    }

    static void writeJson(final Path file, final Object value) throws IOException {
        write(file, LedgerData.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
}
