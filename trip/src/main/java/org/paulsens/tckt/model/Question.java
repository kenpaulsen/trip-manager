package org.paulsens.tckt.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.UUID;
import lombok.Value;
import org.paulsens.tckt.dao.FilesystemPersistence;

@Value
public class Question {
    Id id;
    String question;
    String answerKey;
    List<Choice> choices;

    @JsonCreator
    public Question(
            @JsonProperty("id") final Id id,
            @JsonProperty("question") final String question,
            @JsonProperty("answerKey") final String answerKey,
            @JsonProperty("choices") final List<Choice> choices) {
        this.id = id;
        this.question = question;
        this.answerKey = answerKey;
        this.choices = choices;
    }

    @Value
    public static class Choice {
        String name;
        String description;

        @JsonCreator
        public Choice(@JsonProperty("name") final String name, @JsonProperty("description") final String description) {
            this.name = name;
            this.description = description;
        }
    }

    @Value
    public static class Id implements org.paulsens.tckt.model.Id {
        @JsonValue
        String value;

        public static Id newId() {
            return new Id(UUID.randomUUID().toString());
        }

        public static Question resolve(final FilesystemPersistence persistence, final String id) {
            return persistence.getQuestion(null, new Question.Id(id));
        }

        @Override
        public BindingType getType() {
            return BindingType.QUESTION;
        }
    }
}
