package org.paulsens.tckt.testutil;

import java.io.File;

public class FileUtils {
    public static boolean fileExists(final String fn) {
        return new File(fn).exists();
    }

    public static boolean deleteFile(final String fn) {
        return new File(fn).delete();
    }
}
