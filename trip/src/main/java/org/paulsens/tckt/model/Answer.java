package org.paulsens.tckt.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Value;
import org.paulsens.tckt.dao.FilesystemPersistence;

@Value
public class Answer implements Serializable {
    Id id;
    String value;
    LocalDateTime submitted;
    Question.Id questionId;
    User.Id userId;
    Course.Id courseId;
    Ticket.Id ticketId;

    @JsonCreator
    public Answer(
            @JsonProperty("id") final Id id,
            @JsonProperty("value") final String value,
            @JsonProperty("submitted") final LocalDateTime submitted,
            @JsonProperty("questionId") final Question.Id questionId,
            @JsonProperty("userId") final User.Id userId,
            @JsonProperty("courseId") final Course.Id courseId,
            @JsonProperty("ticketId") final Ticket.Id ticketId) {
        this.id = id;
        this.value = value;
        this.submitted = submitted;
        this.questionId = questionId;
        this.userId = userId;
        this.courseId = courseId;
        this.ticketId = ticketId;
    }

    @Value
    public static class Id implements org.paulsens.tckt.model.Id {
        @JsonValue
        String value;

        public static Id newId() {
            return new Id(UUID.randomUUID().toString());
        }

        public static Answer resolve(final FilesystemPersistence persistence, final String id) {
            return persistence.getAnswer(null, new Answer.Id(id));
        }

        @Override
        public BindingType getType() {
            return BindingType.ANSWER;
        }
    }
}
