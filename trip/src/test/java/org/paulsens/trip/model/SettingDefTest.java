package org.paulsens.trip.model;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Each {@code with*} copy of a {@link SettingDef} changes exactly its own field and carries every other one over.
 *
 * <p>The withers once rebuilt through a 13-argument positional constructor, where a {@code true} in a neighbor's
 * boolean slot compiled and, for instance, made an email setting refuse every address as "not a hex color". The
 * fields are read by reflection so a flag added later is compared too, and a boolean field with no wither in
 * {@link #WITHERS} fails {@link #everyFlagHasAWitherHere()} until this table names it.
 */
public class SettingDefTest {

    /** Each wither, keyed by the one field it is allowed to change. */
    private static final Map<String, UnaryOperator<SettingDef>> WITHERS = withers();

    private static Map<String, UnaryOperator<SettingDef>> withers() {
        final Map<String, UnaryOperator<SettingDef>> withers = new LinkedHashMap<>();
        withers.put("orgOverridable", SettingDef::withOrgOverride);
        withers.put("orgOnly", SettingDef::withOrgOnly);
        withers.put("choices", def -> def.withChoices("a", "b"));
        withers.put("httpUrl", SettingDef::withHttpUrl);
        withers.put("hexColor", SettingDef::withHexColor);
        withers.put("email", SettingDef::withEmail);
        withers.put("siteFrom", SettingDef::withSiteFrom);
        withers.put("analyticsId", SettingDef::withAnalyticsId);
        return withers;
    }

    private static SettingDef plain() {
        return new SettingDef("x.key", Config.Type.INT, "42", "The label", "The description");
    }

    @Test
    public void eachWitherChangesOnlyItsOwnField() {
        final Map<String, Object> before = fields(plain());
        for (final Map.Entry<String, UnaryOperator<SettingDef>> wither : WITHERS.entrySet()) {
            final Map<String, Object> after = fields(wither.getValue().apply(plain()));
            // org-only implies overridable (the constructor folds it in), so that one wither moves two fields.
            final Set<String> expected = "orgOnly".equals(wither.getKey())
                    ? Set.of("orgOnly", "orgOverridable") : Set.of(wither.getKey());
            Assert.assertEquals(changed(before, after), new TreeSet<>(expected), wither.getKey());
        }
    }

    @Test
    public void everyWitherKeepsEveryOtherFieldAlreadySet() {
        final SettingDef everything = everything();
        final Map<String, Object> before = fields(everything);
        Assert.assertEquals(before.get("choices"), List.of("a", "b"));
        for (final Map.Entry<String, UnaryOperator<SettingDef>> wither : WITHERS.entrySet()) {
            final Map<String, Object> after = fields(wither.getValue().apply(everything));
            Assert.assertEquals(changed(before, after), Set.of(), wither.getKey());
        }
    }

    @Test
    public void theWithersAreOrderIndependent() {
        final SettingDef forward = everything();
        SettingDef backward = plain();
        final List<UnaryOperator<SettingDef>> reversed = new ArrayList<>(WITHERS.values());
        Collections.reverse(reversed);
        for (final UnaryOperator<SettingDef> wither : reversed) {
            backward = wither.apply(backward);
        }
        Assert.assertEquals(backward, forward);
    }

    @Test
    public void everyFlagHasAWitherHere() {
        final Set<String> flags = Arrays.stream(SettingDef.class.getDeclaredFields())
                .filter(field -> field.getType() == boolean.class && !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        final Set<String> covered = new TreeSet<>(WITHERS.keySet());
        covered.remove("choices");
        Assert.assertEquals(flags, covered, "a new SettingDef flag needs its wither in WITHERS");
    }

    /** Every wither applied once, in declaration order. */
    private static SettingDef everything() {
        SettingDef def = plain();
        for (final UnaryOperator<SettingDef> wither : WITHERS.values()) {
            def = wither.apply(def);
        }
        return def;
    }

    private static Set<String> changed(final Map<String, Object> before, final Map<String, Object> after) {
        return before.keySet().stream()
                .filter(name -> !before.get(name).equals(after.get(name)))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every instance field by name, so a field added later is compared without editing this test. */
    private static Map<String, Object> fields(final SettingDef def) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final Field field : SettingDef.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                values.put(field.getName(), read(field, def));
            }
        }
        return values;
    }

    private static Object read(final Field field, final SettingDef def) {
        try {
            field.setAccessible(true);
            return field.get(def);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
