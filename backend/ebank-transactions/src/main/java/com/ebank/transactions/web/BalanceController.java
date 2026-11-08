package com.ebank.transactions.web;

import com.ebank.common.money.Money;
import com.ebank.transactions.application.TransferService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Lives in this module because the ledger is the only source of a balance. */
@RestController
class BalanceController {

    private final TransferService service;

    BalanceController(TransferService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/accounts/{accountId}/balance")
    BalanceResponse balance(@PathVariable String accountId) {
        Money balance = service.balance(accountId);
        return new BalanceResponse(accountId, balance.amount(), balance.currency());
    }
}
