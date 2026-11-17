package org.paulsens.trip.migrate;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The command lines of the one-time migrations in this package ({@link ConferenceImportCli},
 * {@link NoteElFlattenCli}) read their arguments ONE way: {@code <command> --name value --flag}. A refusal is an
 * {@link IllegalArgumentException} whose message the CLI prints before exiting with status 1.
 */
final class CliArgs {
    private CliArgs() {
    }

    /** Every {@code --name [value]} after the command; a flag with no value maps to "". */
    static Map<String, String> options(final String[] args) {
        final Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            final String name = args[i].substring(2);
            final boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            opts.put(name, hasValue ? args[++i] : "");
        }
        return opts;
    }

    /** Refuses a command that writes unless {@code --yes} was given. */
    static void requireYes(final Map<String, String> opts) {
        if (!opts.containsKey("yes")) {
            throw new IllegalArgumentException("This writes to the target; pass --yes after reviewing plan.txt.");
        }
    }

    /** The path an option names; refuses a missing or blank one. */
    static Path path(final Map<String, String> opts, final String name) {
        final String value = opts.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required.");
        }
        return Path.of(value);
    }
}
