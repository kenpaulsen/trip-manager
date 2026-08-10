package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;
import org.paulsens.trip.model.Person;

/**
 * Derived view of who reacted with which emoji on one message. Counts and "mine" fall out of the id lists.
 *
 * <p>The cap is a <b>safety valve for a pathological message, not an expected path</b>: at the sizing this was
 * designed for (median 2 reactions, 99th percentile 10, worst case under 100) every id fits, and 100 ids is only
 * ~3.6 KB. It matters that the cap stays clear of the real worst case, because {@link #mine} is answered from the
 * id list — a reactor who falls outside it sees their own reaction un-highlighted and, since the write is
 * idempotent, clicking again cannot remove it. {@link #overflowCount} keeps the count exact regardless.
 */
@Value
public class ChatReactionSummary implements Serializable {

    /** Well clear of the ~100 worst case, so overflow only trips on genuinely anomalous traffic. */
    public static final int MAX_IDS_PER_EMOJI = 250;

    ChatMessage.Id messageId;
    /** emoji → who reacted, in reaction order. */
    Map<String, List<Person.Id>> byEmoji;
    /** emoji → how many reactions exist beyond the capped id list. */
    Map<String, Integer> overflowCount;
    /**
     * emoji → when it was most recently added, epoch millis. The chip order's tie-breaker.
     *
     * <p>Carried explicitly because <b>map order cannot be relied on here</b>: {@link #byEmoji} is built in a
     * {@code LinkedHashMap} but ends up in {@code Map.copyOf}, which is unordered, so chips appeared in an
     * arbitrary order that could change between reads of the same data. Ordering is now a property of the values
     * (count desc, then most recent first) rather than of the container.
     */
    Map<String, Long> lastReactedAt;

    @JsonCreator
    public ChatReactionSummary(
            @JsonProperty("messageId") final ChatMessage.Id messageId,
            @JsonProperty("byEmoji") final Map<String, List<Person.Id>> byEmoji,
            @JsonProperty("overflowCount") final Map<String, Integer> overflowCount,
            @JsonProperty("lastReactedAt") final Map<String, Long> lastReactedAt) {
        this.messageId = messageId;
        this.byEmoji = byEmoji == null ? Map.of() : copy(byEmoji);
        this.overflowCount = overflowCount == null ? Map.of() : Map.copyOf(overflowCount);
        this.lastReactedAt = lastReactedAt == null ? Map.of() : Map.copyOf(lastReactedAt);
    }

    public static ChatReactionSummary empty(final ChatMessage.Id messageId) {
        return new ChatReactionSummary(messageId, Map.of(), Map.of(), Map.of());
    }

    /** Builds a summary from reaction rows (already filtered to one message or a page). */
    public static ChatReactionSummary fromReactions(
            final ChatMessage.Id messageId, final List<ChatReaction> reactions) {
        final Map<String, List<Person.Id>> by = new LinkedHashMap<>();
        final Map<String, Integer> overflow = new LinkedHashMap<>();
        final Map<String, Long> latest = new LinkedHashMap<>();
        if (reactions != null) {
            for (final ChatReaction r : reactions) {
                if (r == null || r.getEmoji() == null || r.getPersonId() == null) {
                    continue;
                }
                final List<Person.Id> list = by.computeIfAbsent(r.getEmoji(), k -> new ArrayList<>());
                if (list.size() < MAX_IDS_PER_EMOJI) {
                    list.add(r.getPersonId());
                } else {
                    overflow.merge(r.getEmoji(), 1, Integer::sum);
                }
                // Tracked even for reactions past the id cap: the tie-breaker must reflect every reaction, not
                // only the ones whose reactor is still listed.
                if (r.getReactedAt() != null) {
                    latest.merge(r.getEmoji(), r.getReactedAt().toEpochMilli(), Math::max);
                }
            }
        }
        return new ChatReactionSummary(messageId, by, overflow, latest);
    }

    /**
     * Folds other summaries' counts into {@code direct}'s overflow — the roll-up of photo reactions into the
     * message that carried the photo. Counts only, never ids: {@link #mine} and the who-tooltip must keep
     * answering for the direct target alone (a photo reaction must not light the message chip as "mine"), and
     * SUM semantics are wanted — the same person reacting on two photos counts twice (user decision
     * 2026-08-09). Every folded emoji is seeded into {@link #byEmoji} even with an empty id list, because
     * clients decide which chips exist by iterating its keys; an overflow-only emoji would never render.
     */
    public static ChatReactionSummary foldCounts(
            final ChatReactionSummary direct, final List<ChatReactionSummary> folded) {
        if (direct == null || folded == null || folded.isEmpty()) {
            return direct;
        }
        final Map<String, List<Person.Id>> by = new LinkedHashMap<>(direct.byEmoji);
        final Map<String, Integer> overflow = new LinkedHashMap<>(direct.overflowCount);
        final Map<String, Long> latest = new LinkedHashMap<>(direct.lastReactedAt);
        boolean changed = false;
        for (final ChatReactionSummary other : folded) {
            changed |= foldOne(other, by, overflow, latest);
        }
        return changed ? new ChatReactionSummary(direct.messageId, by, overflow, latest) : direct;
    }

    private static boolean foldOne(
            final ChatReactionSummary other,
            final Map<String, List<Person.Id>> by,
            final Map<String, Integer> overflow,
            final Map<String, Long> latest) {
        if (other == null) {
            return false;
        }
        boolean changed = false;
        for (final String emoji : emojiOf(other)) {
            final int n = other.count(emoji);
            if (n <= 0) {
                continue;
            }
            by.putIfAbsent(emoji, List.of());
            overflow.merge(emoji, n, Integer::sum);
            final long at = other.lastReactedAtMillis(emoji);
            if (at > 0) {
                latest.merge(emoji, at, Math::max);
            }
            changed = true;
        }
        return changed;
    }

    private static Set<String> emojiOf(final ChatReactionSummary summary) {
        final Set<String> out = new LinkedHashSet<>(summary.byEmoji.keySet());
        out.addAll(summary.overflowCount.keySet());
        return out;
    }

    /** When this emoji was most recently added, epoch millis, or 0 when unknown (rows written before this field). */
    @JsonIgnore
    public long lastReactedAtMillis(final String emoji) {
        return lastReactedAt.getOrDefault(emoji, 0L);
    }

    @JsonIgnore
    public int count(final String emoji) {
        final int listed = byEmoji.getOrDefault(emoji, List.of()).size();
        return listed + overflowCount.getOrDefault(emoji, 0);
    }

    @JsonIgnore
    public boolean mine(final String emoji, final Person.Id me) {
        if (me == null) {
            return false;
        }
        return byEmoji.getOrDefault(emoji, List.of()).contains(me);
    }

    @JsonIgnore
    public int totalCount() {
        int total = 0;
        for (final String emoji : byEmoji.keySet()) {
            total += count(emoji);
        }
        for (final Map.Entry<String, Integer> e : overflowCount.entrySet()) {
            if (!byEmoji.containsKey(e.getKey())) {
                total += e.getValue();
            }
        }
        return total;
    }

    private static Map<String, List<Person.Id>> copy(final Map<String, List<Person.Id>> src) {
        final Map<String, List<Person.Id>> out = new LinkedHashMap<>();
        for (final Map.Entry<String, List<Person.Id>> e : src.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
        }
        return Map.copyOf(out);
    }
}
