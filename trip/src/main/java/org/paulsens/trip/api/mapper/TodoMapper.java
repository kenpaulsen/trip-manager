package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.api.dto.TodoDto;
import org.paulsens.trip.api.dto.TodoStatusDto;
import org.paulsens.trip.content.PageHtml;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;

/**
 * Todos and their per-person statuses.
 *
 * <p>{@code TodoStatus} is not a plain bean -- it is a wrapper whose getters read through to a {@code TodoItem}
 * and a {@code PersonDataValue}. MapStruct maps it by those getters, which is exactly the flattening the wire
 * wants, so no explicit {@code @Mapping} is needed for the delegated fields. The one exception is
 * {@code moreDetails}: typed HTML, sent through the allowlist ({@code PageHtml}) so no client is handed markup
 * the website itself would not show.
 */
@Mapper(uses = ValueMappers.class, imports = PageHtml.class)
public interface TodoMapper {

    TodoMapper INSTANCE = Mappers.getMapper(TodoMapper.class);

    @Mapping(target = "moreDetails", expression = "java(PageHtml.cleanOrNull(todo.getMoreDetails()))")
    TodoDto toDto(TodoItem todo);

    @Mapping(target = "moreDetails", expression = "java(PageHtml.cleanOrNull(status.getMoreDetails()))")
    TodoStatusDto toDto(TodoStatus status);
}
