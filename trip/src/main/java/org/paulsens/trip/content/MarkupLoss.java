package org.paulsens.trip.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.owasp.html.HtmlSanitizer;

/**
 * Formatting that went missing between what was stored and what is about to be saved, while the text it held stayed:
 * a "Clear formatting" over styled text, or a value last written by Quill (the site's editor until 2026-11-23,
 * which dropped any tag or attribute it had no format for, such as an image's {@code style="float:left"}). The
 * content drawer names each such loss before Apply saves it, through the same question that names what the allowlist
 * removes ({@code ContentCommands.wouldRemove}), so formatting is never lost without the author's OK.
 *
 * <p>Only a tag that carries formatting (an attribute beyond its address) is watched, and only such tags are compared:
 * a bare {@code <p>}, {@code <b>} or {@code <div>} changes into the editor's own spelling with nothing lost, and a
 * plain link or image, whose content is its address, has no formatting to lose. Each stored tag first claims an edited
 * tag of the same canonical spelling ({@link HtmlShape#describe}), the one holding its own words when there is one. A
 * watched tag counts as lost when nothing is left for it to claim, its content is still there (an image, frame or link
 * whose address is still on the page, or a wrapper at least half of whose word pairs still are; pairs, because common
 * words are in any text), and nothing unclaimed of its kind holding that same content still has every kind of
 * formatting it had (each attribute name, whatever its value). The second test tells formatting that went apart from
 * an author deleting or replacing a paragraph; the third tells it apart from an author's restyle (a new alignment,
 * color or size changes a value, while "Clear formatting" removes the attribute). A wordless tag (a blank styled line)
 * is the author's to delete, except a link target (an empty anchor with a name or id), which is reported whenever it
 * goes and nothing like it remains, since nothing on the page shows it.
 *
 * <p>The check leans to asking (the owner's choice, 2026-11-23): its content test reads the whole edited value, so an
 * author who joins two styled lines into one, or deletes one of several lines alike, is asked about formatting that
 * did not go, because those words are still on the page. A rule that left out the words of the other lines alike
 * stopped those questions but hid real losses inside an unchanged tag of the same spelling, and was reverted: an
 * unneeded question costs a click, a missed loss costs the formatting.
 *
 * <p>Spellings that are not a loss compare equal: {@code b}/{@code strong}, {@code i}/{@code em}, and a
 * {@code div} turned into a {@code p} with the same attributes. Re-applying the lost markup onto the edited HTML was
 * considered and rejected: an editor splits and merges elements as it pleases, so which edited element a lost wrapper
 * belonged to cannot be told reliably.
 *
 * <p>Cost: the check runs on a page request each time the drawer redraws, on a server every organization shares, and
 * its input is whatever an author posts. What it compares and indexes follows the size of the EDIT, not of the page;
 * reading the two values and the one set of the edited value's words follow the page, which {@link #LENGTH_BOUND}
 * caps. Every bound on the work is a count, never a clock; the one clock is the wait for a turn at the gate:
 * <ul>
 *   <li>Equal values are answered without reading them. A reading holds each word once: a tag's text is a range of
 *       the reading's words, however deep the tag sits.</li>
 *   <li>Tags identical on both sides (same spelling, same words) cancel in one pass. Only the tags an edit touched
 *       are compared by content, and those find their candidates through an index of the edited text's word pairs,
 *       never by scanning every tag (until 2026-11-23 that scan took 14 seconds for a 300 KB page whose font had
 *       changed).</li>
 *   <li>Comparisons are counted against {@link #WORK_BOUND}, each attribute name compared counting as one, and
 *       everything kept per tag (index entries, word sets) against {@link #HELD_BOUND}, charged BEFORE it is built: a
 *       tag with hundreds of attributes around tens of thousands of words would otherwise be indexed once per
 *       attribute and fill the heap, and one with dozens of attributes compared against thousands of candidates
 *       would hold a request thread for seconds while counting as little work.</li>
 *   <li>At most {@link #AT_ONCE} checks run at a time ({@link #GATE}); the next waits its turn, at most
 *       {@link #GATE_WAIT_MILLIS}, and is skipped past that. Whatever a check still costs that the counts miss, it
 *       then holds that many of the server's carrier threads, never all of them (the threading rule: long CPU work
 *       never takes every request thread). An untouched field never waits: it is answered before the gate.</li>
 * </ul>
 * Above a bound, or a value longer than {@link #LENGTH_BOUND}, no tag is listed, the skip is logged, and the drawer
 * says instead that the value was too large to check ({@link Check#skipped}), so that even then nothing is saved
 * unasked. The bounds on the work are counts so that the drawer's redraw and its Apply list the same losses: Apply is
 * answered by the key of the list that was shown. The gate's wait is the exception: when it runs out at Apply but did
 * not at the redraw (or the other way round), the two lists differ and Apply asks once more, never saves unasked.
 */
@Slf4j
public final class MarkupLoss {

    /** Elements that never hold text, so their content is their address. */
    private static final Set<String> ADDRESS_ONLY = Set.of("img", "iframe", "video", "audio", "source", "embed");
    private static final Map<String, String> SAME_AS = Map.of("b", "strong", "i", "em", "div", "p");
    /** What separates words: anything not a letter or a digit. Compiled once; it splits every text of both values. */
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** At most this much of a lost tag is shown, so that a long style still fits on one line of the notice. */
    private static final int SHOWN = 160;
    /**
     * The most comparisons one check may make (each candidate looked at, each word pair looked up, each attribute
     * name compared): measured at well under half a second on a laptop.
     */
    static final long WORK_BOUND = 3_000_000;
    /**
     * The most one check may keep per tag: an index entry or a set member for each word and word pair of each tag it
     * indexes. The tags an everyday edit touches hold a few thousand.
     */
    static final long HELD_BOUND = 400_000;
    /**
     * A value longer than this is not read: no stored value is, since a whole row (every value, and the versions it
     * keeps) is at most 400 KB, and reading is the one cost no count covers.
     */
    static final int LENGTH_BOUND = 400_000;
    /** How many checks may run at once, so that they never hold every carrier thread of the server. */
    static final int AT_ONCE = 2;
    /** The gate of {@link #AT_ONCE}: fair, so a waiting check is not passed over. */
    static final Semaphore GATE = new Semaphore(AT_ONCE, true);
    /** The longest a check waits for a turn at the gate: a few large checks' time. */
    static final long GATE_WAIT_MILLIS = 1_500;

    private MarkupLoss() {
    }

    /**
     * The tests' shorthand: the tags of {@code before} that {@code after} lost while keeping their content, each
     * as the author wrote it (its opening tag, with any address left out); empty when nothing was lost, and ALSO
     * when the comparison was {@link Check#skipped skipped}, which is why a caller uses {@link #check}.
     */
    static List<String> lost(final String before, final String after) {
        return check(before, after).lost();
    }

    /**
     * One check's answer: what was lost, what the check took (candidate comparisons made, entries kept), and whether
     * a bound stopped it, in which case nothing is listed and the caller says the value was not checked.
     */
    public record Check(List<String> lost, long work, long held, boolean skipped) {
    }

    /**
     * The check, with whether it was skipped as more than one request may spend (logged). Waits for a turn at the
     * {@link #GATE}.
     */
    public static Check check(final String before, final String after) {
        final Check free = answeredUnread(before, after);
        if (free != null) {
            return free;
        }
        if (!turn()) {
            log.warn("Skipped the check for lost formatting: no turn at the gate within {} ms", GATE_WAIT_MILLIS);
            return new Check(List.of(), 0, 0, true);
        }
        final Check check;
        try {
            check = check(before, after, WORK_BOUND, HELD_BOUND);
        } finally {
            GATE.release();
        }
        if (check.skipped()) {
            log.warn("Skipped the check for lost formatting: comparing {} stored with {} edited characters passed"
                    + " a bound (work {}, held {})", before.length(), after == null ? 0 : after.length(),
                    check.work(), check.held());
        }
        return check;
    }

    /**
     * The answer that needs no reading, or null: equal values lost nothing (every untouched field of every redraw is
     * answered here), and a value longer than any row is not read. Asked BEFORE the gate, so that an untouched field
     * never waits behind someone else's large check.
     */
    private static Check answeredUnread(final String before, final String after) {
        if (before == null || before.isBlank() || before.equals(after)) {
            return new Check(List.of(), 0, 0, false);
        }
        final int edited = after == null ? 0 : after.length();
        return before.length() > LENGTH_BOUND || edited > LENGTH_BOUND ? new Check(List.of(), 0, 0, true) : null;
    }

    /**
     * A turn at the {@link #GATE}, waiting at most {@link #GATE_WAIT_MILLIS}: a wait without end would let one
     * author's replayed large checks stall every organization's drawer. False when none came (the check is then
     * skipped, and the drawer says the text was too large to check, so Apply still asks).
     */
    private static boolean turn() {
        try {
            return GATE.tryAcquire(GATE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@link #check(String, String)} with the bounds given, so that a test can meet them with a small page. */
    static Check check(final String before, final String after, final long workBound, final long heldBound) {
        final Check free = answeredUnread(before, after);
        if (free != null) {
            return free;
        }
        final String edited = after == null ? "" : after;
        final Matching matching = new Matching(read(before), read(edited), workBound, heldBound);
        try {
            return new Check(matching.lost(), matching.work, matching.held, false);
        } catch (final PastABound ex) {
            return new Check(List.of(), matching.work, matching.held, true);
        }
    }

    /** Thrown inside one check when it passes a bound; never leaves this class. */
    private static final class PastABound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private PastABound() {
            super(null, null, false, false);
        }
    }

    /**
     * Pairs each stored tag with the edited tag that is still it, and names the stored tags left over whose
     * formatting went while their content stayed.
     */
    private static final class Matching {
        private final Reading now;
        /** The tags that carry formatting, on each side: the only ones that can lose any, or stand in for one. */
        private final List<Element> stored;
        private final List<Element> edited;
        private final long workBound;
        private final long heldBound;
        /** The edited tags per spelling, in document order; the claimed ones are passed over as they are met. */
        private final Map<String, Deque<Element>> bySpelling = new HashMap<>();
        private long work;
        private long held;

        private Matching(final Reading was, final Reading now, final long workBound, final long heldBound) {
            this.now = now;
            this.stored = formatted(was);
            this.edited = formatted(now);
            this.workBound = workBound;
            this.heldBound = heldBound;
        }

        /**
         * Each stored tag claims an edited one of the same spelling; what is claimed is that tag, unchanged, and
         * cannot also stand as the restyled version of another (a styled outer span would otherwise hide the inner
         * span whose color went). Three passes: the tags that are identical on both sides; then every stored tag
         * whose own words an edited tag of its spelling holds; then the rest, in order. One pass in order would let
         * an earlier tag the author deleted take a later tag's counterpart, and the later tag would look stripped.
         */
        private List<String> lost() {
            final List<Element> missing = claimInOrder(claimSameContent(claimIdentical()));
            final List<Element> kept = new ArrayList<>();
            for (final Element element : missing) {
                if (contentKept(element)) {
                    kept.add(element);
                }
            }
            return kept.isEmpty() ? List.of() : withoutARestyledVersion(kept);
        }

        /**
         * Cancels the tags an edit did not touch (same spelling, and the same address or the same words), in one
         * pass over each side, and answers the stored tags that are left.
         */
        private List<Element> claimIdentical() {
            final Map<String, Deque<Element>> identical = new HashMap<>();
            for (final Element element : edited) {
                identical.computeIfAbsent(element.identity(), key -> new ArrayDeque<>(1)).add(element);
                bySpelling.computeIfAbsent(element.key, key -> new ArrayDeque<>(1)).add(element);
            }
            final List<Element> touched = new ArrayList<>();
            for (final Element element : stored) {
                final Deque<Element> same = identical.get(element.identity());
                if (same != null && !same.isEmpty() && sameWords(element, same.peekFirst())) {
                    same.removeFirst().claimed = true;
                } else {
                    touched.add(element);
                }
            }
            return touched;
        }

        /**
         * {@link Element#identity} compares a hash of the words: the words themselves settle it. (A tag with an
         * address is that address, whatever its text.)
         */
        private boolean sameWords(final Element stored, final Element edited) {
            if (!stored.address.isEmpty()) {
                return true;
            }
            spend(stored.words().size() / 4 + 1);
            return stored.words().equals(edited.words());
        }

        /** Each touched stored tag claims the first edited tag of its spelling holding its content. */
        private List<Element> claimSameContent(final List<Element> touched) {
            final Set<String> asked = new HashSet<>();
            for (final Element element : touched) {
                asked.add(element.key);
            }
            final Index sameSpelling = new Index();
            for (final Element edited : edited) {
                if (!edited.claimed && asked.contains(edited.key)) {
                    index(sameSpelling, edited.key, edited);
                }
            }
            final List<Element> unmatched = new ArrayList<>();
            for (final Element element : touched) {
                final Element counterpart = find(element, sameSpelling.holdersOf(element.key, element), false);
                if (counterpart == null) {
                    unmatched.add(element);
                } else {
                    counterpart.claimed = true;
                }
            }
            return unmatched;
        }

        /** Each stored tag still unmatched claims the next unclaimed edited tag of its spelling, if any is left. */
        private List<Element> claimInOrder(final List<Element> unmatched) {
            final List<Element> missing = new ArrayList<>();
            for (final Element element : unmatched) {
                final Element next = nextUnclaimed(element.key);
                if (next == null) {
                    missing.add(element);
                } else {
                    next.claimed = true;
                }
            }
            return missing;
        }

        private Element nextUnclaimed(final String spelling) {
            final Deque<Element> same = bySpelling.get(spelling);
            while (same != null && !same.isEmpty()) {
                final Element next = same.removeFirst();
                if (!next.claimed) {
                    return next;
                }
            }
            return null;
        }

        /** Whether a missing tag's content is still anywhere in the edited value. */
        private boolean contentKept(final Element element) {
            if (element.address.isEmpty() && element.words().isEmpty()) {
                // A wordless tag (a blank styled line) is the author's to delete; only a link target, which nothing
                // on the page shows, is reported whenever it goes.
                return element.formatting.contains("name") || element.formatting.contains("id");
            }
            final Content content = now.content();
            return element.address.isEmpty() ? mostlyIn(element, content.words, content.pairs)
                    : content.addresses.contains(element.address);
        }

        /** The tags of a reading that carry formatting, in document order. */
        private static List<Element> formatted(final Reading reading) {
            final List<Element> formatted = new ArrayList<>();
            for (final Element element : reading.elements) {
                if (element.watched) {
                    formatted.add(element);
                }
            }
            return formatted;
        }

        /**
         * The tags of {@code kept} with no restyled version: no unclaimed edited tag of the same kind, holding the
         * same content, that still has every kind of formatting the stored one had. Such a version carries every
         * attribute name of the stored tag, so it is looked for among the carriers of the RAREST of them: asking by
         * one that every edited tag kept would walk them all for each stored tag.
         */
        private List<String> withoutARestyledVersion(final List<Element> kept) {
            final Map<String, Integer> carriers = new HashMap<>();
            for (final Element edited : edited) {
                if (!edited.claimed) {
                    spend(edited.formatting.size() + 1);
                    edited.kinds().forEach(kind -> carriers.merge(kind, 1, Integer::sum));
                }
            }
            final Map<Element, String> rarest = new IdentityHashMap<>();
            final Set<String> asked = new HashSet<>();
            for (final Element element : kept) {
                final String kind = element.rarestKind(carriers);
                if (kind != null) {
                    rarest.put(element, kind);
                    asked.add(kind);
                }
            }
            final Index sameFormatting = new Index();
            for (final Element edited : edited) {
                if (!edited.claimed) {
                    indexKinds(sameFormatting, edited, asked);
                }
            }
            final List<String> lost = new ArrayList<>();
            for (final Element element : kept) {
                final String kind = rarest.get(element);
                if (kind == null || find(element, sameFormatting.holdersOf(kind, element), true) == null) {
                    lost.add(element.shown());
                }
            }
            return List.copyOf(lost);
        }

        private void indexKinds(final Index index, final Element edited, final Set<String> asked) {
            for (final String kind : edited.kinds()) {
                if (asked.contains(kind)) {
                    index(index, kind, edited);
                }
            }
        }

        /** Charged before it is built: an entry, and a member of the tag's own sets, per word and per pair. */
        private void index(final Index index, final String scope, final Element edited) {
            hold(2L * edited.words().size() + 1);
            index.add(scope, edited);
        }

        /**
         * The unclaimed edited tag among {@code holders} that holds the stored one's content, or null when none
         * does. Looking for the stored tag itself, the first in document order; looking for a {@code restyled}
         * version of it, any that still has all of its formatting.
         */
        private Element find(final Element stored, final List<Deque<Element>> holders, final boolean restyled) {
            spend(stored.pairs().size() + 1);
            Element found = null;
            for (final Deque<Element> some : holders) {
                if (found != null && restyled) {
                    break;
                }
                // Claims mostly go in document order, so the claimed tags gather at the front: dropped here, they
                // are not walked past again by every later tag.
                while (!some.isEmpty() && some.peekFirst().claimed) {
                    some.removeFirst();
                }
                found = earlier(stored, some, found, restyled);
            }
            return found;
        }

        /** The first tag of {@code some} before {@code found} that holds the stored one's content, else found. */
        private Element earlier(final Element stored, final Deque<Element> some, final Element found,
                final boolean restyled) {
            for (final Element candidate : some) {
                if (found != null && candidate.order >= found.order) {
                    break;
                }
                // Comparing formatting compares each of the stored tag's attribute names: counted as such, or a tag
                // with dozens of them costs dozens of times what it is charged.
                spend(restyled ? stored.formatting.size() + 1 : 1);
                if (!candidate.claimed && (!restyled || candidate.keepsFormattingOf(stored))
                        && holdsContentOf(stored, candidate)) {
                    return candidate;
                }
            }
            return found;
        }

        private boolean holdsContentOf(final Element stored, final Element candidate) {
            spend(stored.pairs().size());
            return stored.holdsSameContent(candidate);
        }

        private void spend(final long units) {
            work += units;
            if (work > workBound) {
                throw new PastABound();
            }
        }

        private void hold(final long units) {
            held += units;
            if (held > heldBound) {
                throw new PastABound();
            }
        }
    }

    /**
     * Where content is among edited tags, per scope (a spelling, or a kind of formatting): by address, by word, by
     * word pair, and the tags with no words. A stored tag asks for the few lists that must contain any tag of its
     * scope holding its content, so the tags of another spelling, or without its formatting, are never walked.
     */
    private static final class Index {
        /** Scope, then what is held: "@" and an address, a word, a pair ("one two"), or "" for no words at all. */
        private final Map<String, Map<String, Deque<Element>>> scopes = new HashMap<>();

        private void add(final String scope, final Element element) {
            final Map<String, Deque<Element>> held = scopes.computeIfAbsent(scope, key -> new HashMap<>());
            if (!element.address.isEmpty()) {
                held.computeIfAbsent('@' + element.address, key -> new ArrayDeque<>(1)).add(element);
            }
            if (element.words().isEmpty()) {
                held.computeIfAbsent("", key -> new ArrayDeque<>(1)).add(element);
            }
            for (final String word : element.wordSet()) {
                held.computeIfAbsent(word, key -> new ArrayDeque<>(1)).add(element);
            }
            for (final String pair : element.pairSet()) {
                held.computeIfAbsent(pair, key -> new ArrayDeque<>(1)).add(element);
            }
        }

        /**
         * Lists, each in document order, that together contain every tag of {@code scope} {@link
         * Element#holdsSameContent holding} the stored one's content. For a tag of several words: a holder has at
         * least half of its word pairs, so it is in the list of at least one of any "half plus one" of them, and the
         * rarest ones are asked.
         */
        private List<Deque<Element>> holdersOf(final String scope, final Element stored) {
            final Map<String, Deque<Element>> held = scopes.getOrDefault(scope, Map.of());
            final List<String> words = stored.words();
            if (!stored.address.isEmpty() || words.size() < 2) {
                final Deque<Element> one = held.get(!stored.address.isEmpty() ? '@' + stored.address
                        : words.isEmpty() ? "" : words.get(0));
                return one == null ? List.of() : List.of(one);
            }
            final List<Deque<Element>> lists = new ArrayList<>();
            for (final String pair : stored.pairs()) {
                final Deque<Element> some = held.get(pair);
                if (some != null) {
                    lists.add(some);
                }
            }
            // A pair nothing holds has an empty list, the rarest there is: only what is left of "half plus one"
            // after those needs asking.
            final int asked = stored.pairs().size() / 2 + 1 - (stored.pairs().size() - lists.size());
            if (asked <= 0) {
                return List.of();
            }
            lists.sort(Comparator.comparingInt(Deque::size));
            // Each list once, in the sorted order (the work a check takes must be the same every time it runs), and
            // told apart by identity.
            final Set<Deque<Element>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            final List<Deque<Element>> rarest = new ArrayList<>();
            for (final Deque<Element> some : lists.subList(0, asked)) {
                if (seen.add(some)) {
                    rarest.add(some);
                }
            }
            return rarest;
        }
    }

    /** The sanitizer's reading; it calls {@link Reading#closeDocument} itself. */
    private static Reading read(final String html) {
        final Reading reading = new Reading();
        HtmlSanitizer.sanitize(html, reading);
        return reading;
    }

    /** Lower-cased runs of letters and digits, the words a wrapper's content is recognized by. */
    static List<String> words(final String text) {
        final List<String> words = new ArrayList<>();
        for (final String word : NOT_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /** Each word joined to the next, in order. */
    static List<String> pairs(final List<String> words) {
        final List<String> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < words.size(); i++) {
            pairs.add(words.get(i) + ' ' + words.get(i + 1));
        }
        return pairs;
    }

    /** Whether at least half of {@code own}'s word pairs (or its one word) are among {@code words}/{@code pairs}. */
    private static boolean mostlyIn(final Element own, final Set<String> words, final Set<String> pairs) {
        if (own.words().size() < 2) {
            return words.contains(own.words().get(0));
        }
        final List<String> ownPairs = own.pairs();
        int kept = 0;
        for (final String pair : ownPairs) {
            if (pairs.contains(pair)) {
                kept++;
            }
        }
        return kept * 2 >= ownPairs.size();
    }

    /** Words, word pairs and addresses: everything a reading holds. */
    private record Content(Set<String> words, Set<String> pairs, Set<String> addresses) {
    }

    /** One opening tag: its name, canonical key, how it was written, its address and where its text is. */
    private static final class Element {
        private final Reading in;
        private final String name;
        private final String key;
        private final String written;
        private final String address;
        private final boolean watched;
        /** The names of its attributes beyond its address (style, class, width, target): what formatting it has. */
        private final Set<String> formatting = new TreeSet<>();
        /** Its place among its reading's tags, in document order. */
        private final int order;
        /** Its text: the reading's words from {@code start} up to {@code end}, the text of the tags inside it too. */
        private final int start;
        private int end;
        /** Set on an edited tag once a stored tag has taken it as its own. */
        private boolean claimed;
        /** Its words and pairs as sets: built for an indexed tag only, and charged for before that. */
        private Set<String> wordSet;
        private Set<String> pairSet;

        private Element(final Reading in, final String element, final List<String> attributes) {
            this.in = in;
            this.order = in.elements.size();
            this.start = in.words.size();
            this.end = start;
            this.name = SAME_AS.getOrDefault(element, element);
            this.key = HtmlShape.describe(name, attributes);
            this.written = writtenOf(element, attributes);
            this.address = addressOf(element, attributes);
            for (int i = 0; i + 1 < attributes.size(); i += 2) {
                final String attribute = attributes.get(i).toLowerCase(Locale.ROOT);
                if (!"src".equals(attribute) && !"href".equals(attribute)
                        && !HtmlShape.describe(name, List.of(attribute, attributes.get(i + 1))).equals(name)) {
                    formatting.add(attribute);
                }
            }
            // A tag with an attribute beyond its address left after canonical spelling (an empty or default attribute
            // is none): a plain link or image has no formatting to lose.
            this.watched = !formatting.isEmpty();
        }

        /** Its end tag (or the document's end) was met: its text stops here. */
        private void close() {
            end = in.words.size();
        }

        private List<String> words() {
            return in.words.subList(start, end);
        }

        private List<String> pairs() {
            return end - start < 2 ? List.of() : in.pairs.subList(start, end - 1);
        }

        private Set<String> wordSet() {
            if (wordSet == null) {
                wordSet = new HashSet<>(words());
            }
            return wordSet;
        }

        private Set<String> pairSet() {
            if (pairSet == null) {
                pairSet = new HashSet<>(pairs());
            }
            return pairSet;
        }

        /**
         * Equal for two tags an edit did not touch: the same spelling, and the same address or (by a hash, which
         * the caller confirms) the same words.
         */
        private String identity() {
            return key + '\n' + (address.isEmpty() ? end - start + ":" + in.hashOf(start, end) : '@' + address);
        }

        /**
         * The kind of formatting to look for a restyled version of this stored tag under: its name with the
         * attribute name the fewest edited tags carry (the first in the alphabet among equals, so that the work is
         * the same every time), or null when no edited tag carries one of its attributes, so that none can be its
         * restyled version.
         */
        private String rarestKind(final Map<String, Integer> carriers) {
            String rarest = null;
            for (final String attribute : formatting) {
                final String kind = name + ' ' + attribute;
                if (!carriers.containsKey(kind)) {
                    return null;
                }
                if (rarest == null || carriers.get(kind) < carriers.get(rarest)) {
                    rarest = kind;
                }
            }
            return rarest;
        }

        /** Every kind this edited tag can answer to: its name with each attribute name. */
        private List<String> kinds() {
            final List<String> kinds = new ArrayList<>();
            for (final String attribute : formatting) {
                kinds.add(name + ' ' + attribute);
            }
            return kinds;
        }

        /**
         * Whether this edited tag is of the stored one's kind and still has every kind of formatting it had (each
         * attribute name, whatever its value). Holding the same content too, it is a restyle, not a loss: a new
         * alignment or color changes a value; "Clear formatting" removes the attribute.
         */
        private boolean keepsFormattingOf(final Element stored) {
            return name.equals(stored.name) && formatting.containsAll(stored.formatting);
        }

        /** Whether {@code other} holds this tag's content: its address, or at least half of its word pairs. */
        private boolean holdsSameContent(final Element other) {
            if (!address.isEmpty()) {
                return address.equals(other.address);
            }
            if (words().isEmpty()) {
                return other.words().isEmpty();
            }
            return !other.words().isEmpty() && mostlyIn(this, other.wordSet(), other.pairSet());
        }

        private String shown() {
            return written.codePointCount(0, written.length()) <= SHOWN ? written
                    : written.substring(0, written.offsetByCodePoints(0, SHOWN - 1)) + "…";
        }

        private static String writtenOf(final String element, final List<String> attributes) {
            final StringBuilder out = new StringBuilder("<").append(element);
            for (int i = 0; i + 1 < attributes.size(); i += 2) {
                final String attribute = attributes.get(i).toLowerCase(Locale.ROOT);
                if (!"src".equals(attribute) && !"href".equals(attribute)) {
                    out.append(' ').append(attribute).append("=\"").append(attributes.get(i + 1)).append('"');
                }
            }
            return out.append('>').toString();
        }

        /** An embed's {@code src}, or a link's {@code href}: what the element IS, whatever its text. */
        private static String addressOf(final String element, final List<String> attributes) {
            final String wanted = "a".equals(element) ? "href" : ADDRESS_ONLY.contains(element) ? "src" : null;
            for (int i = 0; wanted != null && i + 1 < attributes.size(); i += 2) {
                if (wanted.equalsIgnoreCase(attributes.get(i))) {
                    return attributes.get(i + 1).trim();
                }
            }
            return "";
        }
    }

    /**
     * The sanitizer's raw reading of a body (nothing filtered): every tag and every word, once each. A tag's text is
     * a range of the words, so a word inside tags nested a hundred deep is still held once.
     */
    private static final class Reading implements HtmlSanitizer.Policy {
        /** The multiplier of the words' rolling hash ({@link #hashOf}). */
        private static final long ROLL = 0x9E3779B97F4A7C15L;

        private final List<Element> elements = new ArrayList<>();
        private final Deque<Element> open = new ArrayDeque<>();
        private final List<String> words = new ArrayList<>();
        private List<String> pairs = List.of();
        /** The hash of the first {@code i} words, and {@link #ROLL} to the power {@code i}. */
        private long[] hashed = new long[1];
        private long[] rolled = {1};
        private Content content;

        @Override
        public void openDocument() {
            // Nothing to prepare.
        }

        @Override
        public void closeDocument() {
            open.forEach(Element::close);
            open.clear();
            pairs = MarkupLoss.pairs(words);
            hashed = new long[words.size() + 1];
            rolled = new long[words.size() + 1];
            rolled[0] = 1;
            for (int i = 0; i < words.size(); i++) {
                hashed[i + 1] = hashed[i] * ROLL + words.get(i).hashCode() + 1;
                rolled[i + 1] = rolled[i] * ROLL;
            }
        }

        @Override
        public void openTag(final String element, final List<String> attributes) {
            final String lower = element.toLowerCase(Locale.ROOT);
            final Element tag = new Element(this, lower, attributes);
            elements.add(tag);
            if (!HtmlTags.VOID_ELEMENTS.contains(lower)) {
                open.push(tag);
            }
        }

        @Override
        public void closeTag(final String element) {
            final String closed = SAME_AS.getOrDefault(element.toLowerCase(Locale.ROOT),
                    element.toLowerCase(Locale.ROOT));
            if (open.stream().noneMatch(tag -> tag.name.equals(closed))) {
                return;
            }
            // Implied end tags: closing an outer element closes everything still open inside it.
            while (!open.isEmpty()) {
                final Element tag = open.pop();
                tag.close();
                if (tag.name.equals(closed)) {
                    return;
                }
            }
        }

        @Override
        public void text(final String text) {
            words.addAll(MarkupLoss.words(text));
        }

        /** A hash of the words from {@code start} up to {@code end}, the same for the same words anywhere. */
        private long hashOf(final int start, final int end) {
            return hashed[end] - hashed[start] * rolled[end - start];
        }

        /** Everything this reading holds. */
        private Content content() {
            if (content == null) {
                final Set<String> addresses = new HashSet<>();
                for (final Element element : elements) {
                    if (!element.address.isEmpty()) {
                        addresses.add(element.address);
                    }
                }
                content = new Content(new HashSet<>(words), new HashSet<>(pairs), addresses);
            }
            return content;
        }
    }
}
