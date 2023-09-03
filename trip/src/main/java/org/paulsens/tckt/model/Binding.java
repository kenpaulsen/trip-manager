package org.paulsens.tckt.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.paulsens.tckt.dao.FilesystemPersistence;

@Value
public class Binding {
    @EqualsAndHashCode.Exclude
    Id id;
    org.paulsens.tckt.model.Id srcId;
    org.paulsens.tckt.model.Id destId;
    BindingType srcType;
    BindingType destType;

    @JsonCreator
    public Binding(
            @JsonProperty("id") final Id id,
            @JsonProperty("srcId") final String srcId,
            @JsonProperty("destId") final String destId,
            @JsonProperty("srcType") final BindingType srcType,
            @JsonProperty("destType") final BindingType destType) {
        this.id = (id == null) ? Id.newId() : id;
        this.srcId = toId(srcId, srcType);
        this.destId = toId(destId, destType);
        this.srcType = srcType;
        this.destType = destType;
    }

    private static org.paulsens.tckt.model.Id toId(final String id, final BindingType type) {
        return switch (type) {
            case ANSWER -> new Answer.Id(id);
            case BINDING -> new Binding.Id(id);
            case COURSE -> new Course.Id(id);
            case QUESTION -> new Question.Id(id);
            case TICKET -> new Ticket.Id(id);
            case USER -> new User.Id(id);
        };
    }

    @Value
    public static class Id implements org.paulsens.tckt.model.Id {
        @JsonValue
        String value;

        public static Id newId() {
            return new Binding.Id(UUID.randomUUID().toString());
        }

        public static Binding resolve(final FilesystemPersistence persistence, String id) {
            // FIXME: Support passing in non-null
            return persistence.getBinding(null, new Binding.Id(id));
        }

        @Override
        public BindingType getType() {
            return BindingType.BINDING;
        }
    }
}
