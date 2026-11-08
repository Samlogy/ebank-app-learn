package com.ebank.transactions.application;

/** All fields are optional and combined with AND. */
public record TransferSearch(String transactionId, String accountId, String sender, String receiver) {}
