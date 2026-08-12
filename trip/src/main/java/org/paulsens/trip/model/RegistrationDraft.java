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
}
