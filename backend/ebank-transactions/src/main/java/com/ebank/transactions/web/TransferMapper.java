package com.ebank.transactions.web;

import com.ebank.transactions.domain.Transfer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
interface TransferMapper {

    @Mapping(target = "amount", expression = "java(transfer.money().amount())")
    @Mapping(target = "currency", expression = "java(transfer.money().currency())")
    TransferResponse toResponse(Transfer transfer);
}
