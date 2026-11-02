package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * One question on a trip's registration form. Free text by default (every question stored before typed
 * questions existed reads as {@link QuestionType#TEXT}); a CHOICE or MULTI_CHOICE question offers
 * {@link #choices}, and on a trip with priced checkout those choices carry prices, which is how admission
 * types and add-ons are configured (see {@code docs/checkout.md}). The answer lives in
 * {@code Registration.options} under {@link #getKey()}: free text, a choice id, comma-joined choice ids, or
 * {@code Yes}/{@code No}.
 */
@Data
// ageBandsApply (a per-question opt-out of the trip's age bands, 2026-11) was retired on 2026-11-02: bands adjust
// the ADMISSION price only now (docs/checkout.md), so a stored flag is read and dropped.
@JsonIgnoreProperties({"ageBandsApply"})
public final class RegistrationOption implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The answer a YES_NO question stores for yes; anything else (including blank) is not yes. */
    public static final String YES = "Yes";
    public static final String NO = "No";

    private int id;
    private String shortDesc;
    private String longDesc;
    private Boolean show;
    /** How the question is answered; a question stored before types existed reads as {@link QuestionType#TEXT}. */
    private QuestionType type;
    /** Whether a registration is refused while this question is unanswered; null reads as optional. */
    private Boolean required;
    /** The answers a CHOICE or MULTI_CHOICE question offers; ignored for the other types. */
    private List<OptionChoice> choices;

    /**
     * The {@code Registration.options} map key for this option. The map is String-keyed while the id is an
     * int, and EL will not coerce an int subscript to a String map key -- which is why the single-person
     * registration page had to build its inputs with dynamic components. A settable EL binding like
     * {@code #&#123;regs[pid].options[opt.key]&#125;} needs this getter.
     */
    @JsonIgnore
    public String getKey() {
        return String.valueOf(id);
    }

    @JsonCreator
    public RegistrationOption(
            @JsonProperty("id") final int id,
            @JsonProperty("shortDesc") final String shortDesc,
            @JsonProperty("longDesc") final String longDesc,
            @JsonProperty("show") final Boolean show,
            @JsonProperty("type") final QuestionType type,
            @JsonProperty("required") final Boolean required,
            @JsonProperty("choices") final List<OptionChoice> choices) {
        this.id = id;
        this.shortDesc = shortDesc;
        this.longDesc = longDesc;
        this.show = (show == null) ? Boolean.TRUE : show;
        this.type = (type == null) ? QuestionType.TEXT : type;
        this.required = required;
        this.choices = (choices == null) ? new ArrayList<>() : new ArrayList<>(choices);
    }

    /** A free-text question: every question that predates typed questions. */
    public RegistrationOption(final int id, final String shortDesc, final String longDesc, final Boolean show) {
        this(id, shortDesc, longDesc, show, null, null, null);
    }

    /** Null (a forged or cleared menu) is free text, the type every question had before types existed. */
    public void setType(final QuestionType type) {
        this.type = (type == null) ? QuestionType.TEXT : type;
    }

    /** Plain boolean for EL and the editor checkbox: null reads as optional. */
    @JsonIgnore
    public boolean isRequiredFlag() {
        return Boolean.TRUE.equals(required);
    }

    public void setRequiredFlag(final boolean flag) {
        this.required = flag ? Boolean.TRUE : null;
    }

    public List<OptionChoice> getChoices() {
        if (choices == null) {
            choices = new ArrayList<>();
        }
        return choices;
    }

    public void setChoices(final List<OptionChoice> choices) {
        this.choices = (choices == null) ? new ArrayList<>() : new ArrayList<>(choices);
    }

    /** Whether the question is answered by picking from {@link #choices}. */
    @JsonIgnore
    public boolean isChoiceType() {
        return getType() == QuestionType.CHOICE || getType() == QuestionType.MULTI_CHOICE;
    }

    /** Whether any choice costs money: the question takes part in pricing. */
    @JsonIgnore
    public boolean isPriced() {
        return isChoiceType() && getChoices().stream().anyMatch(OptionChoice::isPriced);
    }

    /** The choices a registrant may pick now (disabled ones are kept for existing answers, never offered). */
    @JsonIgnore
    public List<OptionChoice> getOfferedChoices() {
        return getChoices().stream().filter(OptionChoice::isOffered).toList();
    }

    /** The choice with this id, offered or not, or null. */
    public OptionChoice choice(final String choiceId) {
        if (choiceId == null) {
            return null;
        }
        return getChoices().stream().filter(c -> choiceId.equals(c.getId())).findFirst().orElse(null);
    }

    /**
     * The choice ids an answer names: one for CHOICE, any number for MULTI_CHOICE (comma-joined, the
     * {@code ContentInstance} MULTI_CHOICE convention), none for the other types or a blank answer.
     */
    public List<String> choiceIdsOf(final String answer) {
        if (!isChoiceType() || answer == null || answer.isBlank()) {
            return List.of();
        }
        return getType() == QuestionType.CHOICE ? List.of(answer.trim()) : OptionChoice.splitCsv(answer);
    }

    /**
     * How an answer reads to a person: the choice labels for a choice question (an unknown id is shown as
     * stored, so nothing silently vanishes), the answer itself otherwise, null when unanswered. Exports and admin
     * views use this.
     */
    public String displayOf(final String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        if (!isChoiceType()) {
            return answer;
        }
        final List<String> labels = new ArrayList<>();
        for (final String choiceId : choiceIdsOf(answer)) {
            final OptionChoice found = choice(choiceId);
            labels.add(found == null ? choiceId : found.getLabel());
        }
        return String.join(", ", labels);
    }

    /** Whether {@code answer} counts as answered for a required question. */
    public boolean isAnswered(final String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return !isChoiceType() || !choiceIdsOf(answer).isEmpty();
    }

    /**
     * The trip's "registration type" question, or null: the first SHOWN, required, pick-one question with a priced
     * choice ("Full weekend / Friday only"). Its answer is what a registration counts as when nothing stored a
     * {@code Registration.registrationType}, and its choice's {@link OptionChoice#getDays() days} say which days of
     * the event the person attends (the check-in counts, {@code docs/checkin.md}).
     */
    public static RegistrationOption typeQuestion(final List<RegistrationOption> questions) {
        if (questions == null) {
            return null;
        }
        return questions.stream()
                .filter(q -> q != null && Boolean.TRUE.equals(q.getShow()) && q.isRequiredFlag())
                .filter(q -> q.getType() == QuestionType.CHOICE && q.isPriced())
                .findFirst().orElse(null);
    }

    /** Appends a new free choice: the editor's "Add choice". */
    public OptionChoice addChoice() {
        final OptionChoice added = new OptionChoice("New choice");
        getChoices().add(added);
        return added;
    }

    /** Removes the choice with this id; answers that named it keep the id and display it as stored. */
    public boolean removeChoice(final String choiceId) {
        return getChoices().removeIf(c -> c.getId().equals(choiceId));
    }

    /** How a registration question is answered. Serialized by name. */
    public enum QuestionType {
        /** Free text: every question stored before types existed. */
        TEXT("Text"),
        /** Exactly one of the choices. */
        CHOICE("Pick one"),
        /** Any number of the choices (add-ons). */
        MULTI_CHOICE("Pick any"),
        /** Yes or No. */
        YES_NO("Yes / No");

        private final String displayValue;

        QuestionType(final String displayValue) {
            this.displayValue = displayValue;
        }

        public String getDisplayValue() {
            return displayValue;
        }
    }
}
