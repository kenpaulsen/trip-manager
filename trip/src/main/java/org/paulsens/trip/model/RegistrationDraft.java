package org.paulsens.trip.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * The working copy of a registration form (single traveler or a whole family party), keyed by person-id
 * value. Held in SESSION rather than viewScope: filling out a registration routinely takes a detour --
 * add a family member, complete a profile -- and a view-scoped copy throws away every typed answer the
 * moment the page is left. The page binds its inputs to these very maps, so each postback writes straight
 * into the session-held draft. {@code RegistrationCommands.loadDraft} reconciles a returning draft against
 * the store rather than trusting it: fresher persisted state always wins.
 */
@Data
public class RegistrationDraft implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** personId value -> the transient Registration carrying the typed option answers. */
    private final Map<String, Registration> regs = new HashMap<>();
    /** personId value -> Boolean "register this traveler" checkbox. */
    private final Map<String, Object> sel = new HashMap<>();
    /** personId value -> Boolean daily-chat-digest answer. */
    private final Map<String, Object> digest = new HashMap<>();
    /**
     * The required-document checkboxes, one flat map keyed {@code personId|waiverId|version|agree} (or
     * {@code |attest}) -> Boolean. Lazily created rather than initialized: a draft restored from a session
     * written before this field existed comes back with it null (deserialization runs no initializer).
     */
    private Map<String, Object> waivers;

    /** Priced checkout (docs/checkout.md): personId value -> the discount code that person typed. Lazy, as above. */
    private Map<String, String> codes;
    /** Priced checkout: {@code personId|choiceId} -> Boolean, the attestation checkboxes. Lazy, as above. */
    private Map<String, Object> attest;

    public Map<String, Object> getWaivers() {
        if (waivers == null) {
            waivers = new HashMap<>();
        }
        return waivers;
    }

    public Map<String, String> getCodes() {
        if (codes == null) {
            codes = new HashMap<>();
        }
        return codes;
    }

    public Map<String, Object> getAttest() {
        if (attest == null) {
            attest = new HashMap<>();
        }
        return attest;
    }

    /**
     * Copies one person's checkout entries (their code and ticked attestations) from {@code old}, beside the
     * answers {@code RegistrationCommands.loadDraft} carries: someone who cancels at the processor and comes back
     * must not retype a code or re-tick a statement.
     */
    public void carryCheckoutFrom(final RegistrationDraft old, final String personId) {
        if (old == null || personId == null) {
            return;
        }
        if (old.codes != null && old.codes.get(personId) != null) {
            getCodes().put(personId, old.codes.get(personId));
        }
        if (old.attest != null) {
            final String prefix = attestKey(personId, "");
            old.attest.forEach((key, value) -> carryAttestation(prefix, key, value));
        }
    }

    private void carryAttestation(final String prefix, final String key, final Object value) {
        if (key.startsWith(prefix)) {
            getAttest().put(key, value);
        }
    }

    /** The attestation map's key for one person's choice. */
    public static String attestKey(final String personId, final String choiceId) {
        return personId + "|" + choiceId;
    }
}
