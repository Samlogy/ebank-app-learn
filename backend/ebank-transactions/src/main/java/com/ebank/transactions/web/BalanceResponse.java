package com.ebank.transactions.web;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal amount, String currency) {}
