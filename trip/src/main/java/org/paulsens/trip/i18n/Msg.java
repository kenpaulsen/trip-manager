package org.paulsens.trip.i18n;

import java.util.Collections;
import java.util.Enumeration;
import java.util.ResourceBundle;

/**
 * The pages' {@code #{msg['common.save']}}: the {@code resource-bundle} that {@code faces-config.xml} registers,
 * as a thin face over {@link Text}.
 *
 * <p>A class rather than a set of {@code .properties} files because Faces resolves ONE base name per variable,
 * and the words live in many files across two repos. It holds no language of its own: {@code ResourceBundle}
 * caches one instance per requested locale, so every lookup asks {@link Text} for the request's language at
 * that moment instead of trusting whichever locale the instance was first built for.
 */
public class Msg extends ResourceBundle {

    @Override
    protected Object handleGetObject(final String key) {
        return Text.get(key);
    }

    /** Always true: a missing key renders {@link Text}'s visible marker, never a MissingResourceException. */
    @Override
    public boolean containsKey(final String key) {
        return key != null;
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.emptyEnumeration();
    }
}
