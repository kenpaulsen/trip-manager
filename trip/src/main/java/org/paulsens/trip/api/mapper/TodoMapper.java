package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.api.dto.TodoDto;
import org.paulsens.trip.api.dto.TodoStatusDto;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;

/**
 * Todos and their per-person statuses.
 *
 * <p>{@code TodoStatus} is not a plain bean -- it is a wrapper whose getters read through to a {@code TodoItem}
 * and a {@code PersonDataValue}. MapStruct maps it by those getters, which is exactly the flattening the wire
 * wants, so no explicit {@code @Mapping} is needed for the delegated fields.
 */
@Mapper(uses = ValueMappers.class)
public interface TodoMapper {

    TodoMapper INSTANCE = Mappers.getMapper(TodoMapper.class);

    TodoDto toDto(TodoItem todo);

    TodoStatusDto toDto(TodoStatus status);
}
