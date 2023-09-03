package org.paulsens.tckt.commands;

import java.io.File;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TcktCommandsTest {

    @Test
    public void testEnsureDirectories() {
        final TcktCommands commands = new TcktCommands();
        final String base = "/tmp/" + RandomData.genAlpha(7);
        final String relPath = "/one/two/three/four";
        final String relPath2 = "/1/two/3/four";
        final String relPath3 = "/ONE/two/three/four";
        final String relPath4 = "/x/two/abc/4";
        try {
            ensure(commands, base + relPath);
            ensure(commands, base + relPath2);
            ensure(commands, base + relPath3);
            ensure(commands, base + relPath4);
        } finally {
            cleanup(new File(base));
        }
    }

    private void ensure(final TcktCommands commands, final String path) {
        Assert.assertFalse(new File(path).exists());
        commands.ensureDirectories(path);
        Assert.assertFalse(new File(path).exists());
        Assert.assertTrue(new File(path.substring(0, path.lastIndexOf('/'))).exists());
    }

    private void cleanup(final File base) {
        if (base.isDirectory()) {
            final File[] files = base.listFiles();
            if (files != null) {
                for (final File file : files) {
                    cleanup(file);
                }
            }
            if (!base.delete()) {
                throw new IllegalStateException("Unable to delete directory: " + base.getAbsolutePath());
            }

        } else {
            if (base.exists()) {
                // It's a file for some reason
                if (!base.delete()) {
                    throw new IllegalStateException("Unable to delete file: " + base.getAbsolutePath());
                }
            } else {
                throw new IllegalStateException("Trying to delete a path that doesn't exist: " + base.getAbsolutePath());
            }
        }
    }
}