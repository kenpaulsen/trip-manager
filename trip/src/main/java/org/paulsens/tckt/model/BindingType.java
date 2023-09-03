package org.paulsens.tckt.model;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.Getter;
import org.paulsens.tckt.dao.FilesystemPersistence;

@Getter
public enum BindingType {
    // NOTE: Do not change these numbers, they must match the db
    ANSWER(0, Answer.Id::resolve),
    BINDING(1, Binding.Id::resolve),
    COURSE(2, Course.Id::resolve),
    QUESTION(3, Question.Id::resolve),
    TICKET(4, Ticket.Id::resolve),
    USER(5, User.Id::resolve);

    private final int typeId;
    private final BiFunction<FilesystemPersistence, String, ?> resolveFunc;

    private static final Map<Integer, BindingType> values = new HashMap<>();

    static {
        for (BindingType value : values()) {
            values.put(value.getTypeId(), value);
        }
    }

    public static BindingType from(final int typeId) {
        return values.get(typeId);
    }

    <R> BindingType(final int typeId, final BiFunction<FilesystemPersistence, String, R> resolveFunc) {
        this.typeId = typeId;
        this.resolveFunc = resolveFunc;
    }

    @SuppressWarnings("unchecked")
    public <T> T resolve(final FilesystemPersistence persistence, final String strId) {
        return (T) resolveFunc.apply(persistence, strId);
    }
}
