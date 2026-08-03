package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.api.dto.TransactionDto;
import org.paulsens.trip.model.Transaction;

/**
 * {@code Transaction} to its wire shape.
 *
 * <p>{@code userAmount} is left to the resource: it is computed by
 * {@code TransactionsCommands.getUserAmount}, which divides a shared amount across the group, and duplicating
 * that arithmetic in a mapper is how two places end up disagreeing about what somebody owes.
 */
@Mapper(uses = ValueMappers.class)
public interface TransactionMapper {

    TransactionMapper INSTANCE = Mappers.getMapper(TransactionMapper.class);

    /** {@code deleted} is a soft-delete timestamp on the model and a boolean on the wire. */
    @Mapping(target = "deleted", expression = "java(tx.getDeleted() != null)")
    @Mapping(target = "userAmount", ignore = true)
    TransactionDto toDto(Transaction tx);
}
