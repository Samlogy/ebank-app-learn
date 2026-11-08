package com.ebank.accounts.web;

import com.ebank.accounts.domain.Account;
import com.ebank.accounts.domain.AccountHistory;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
interface AccountMapper {

    AccountResponse toResponse(Account account);

    AccountHistoryResponse toResponse(AccountHistory history);
}
