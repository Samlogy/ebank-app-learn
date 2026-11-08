package com.ebank.transactions.web;

import com.ebank.common.error.ApiException;
import com.ebank.common.web.PageResponse;
import com.ebank.transactions.application.DepositCommand;
import com.ebank.transactions.application.TransferCommand;
import com.ebank.transactions.application.TransferResult;
import com.ebank.transactions.application.TransferSearch;
import com.ebank.transactions.application.TransferService;
import com.ebank.transactions.domain.Transfer;
import com.ebank.transactions.domain.TransferStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
class TransferController {

    private static final String KEY_HEADER = "Idempotency-Key";

    private final TransferService service;
    private final TransferMapper mapper;

    TransferController(TransferService service, TransferMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    ResponseEntity<TransferResponse> transfer(
            @RequestHeader(KEY_HEADER) @Size(min = 1, max = 128) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return respond(service.transfer(new TransferCommand(
                request.senderAccountId(),
                request.receiverAccountId(),
                request.amount(),
                request.currency(),
                request.reference(),
                idempotencyKey)));
    }

    @PostMapping("/deposits")
    ResponseEntity<TransferResponse> deposit(
            @RequestHeader(KEY_HEADER) @Size(min = 1, max = 128) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return respond(service.deposit(new DepositCommand(
                request.receiverAccountId(), request.amount(), request.currency(), request.reference(), idempotencyKey)));
    }

    @GetMapping("/{transactionId}")
    TransferResponse get(@PathVariable String transactionId) {
        return mapper.toResponse(service.get(transactionId));
    }

    @GetMapping
    PageResponse<TransferResponse> search(
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String receiver,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by("createdAt").descending());
        TransferSearch search = new TransferSearch(transactionId, accountId, sender, receiver);
        return PageResponse.from(service.search(search, request).map(mapper::toResponse));
    }

    private ResponseEntity<TransferResponse> respond(TransferResult result) {
        Transfer transfer = result.transfer();
        // Thrown here, after the commit, because the cancelled transfer must stay on record.
        if (transfer.getStatus() == TransferStatus.CANCELLED) {
            throw ApiException.unprocessable(
                    transfer.getFailureReason(),
                    "Insufficient funds. Transaction " + transfer.getTransactionId() + " was cancelled.");
        }
        TransferResponse body = mapper.toResponse(transfer);
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotency-Replayed", "true").body(body)
                : ResponseEntity.created(URI.create("/api/v1/transactions/" + body.transactionId())).body(body);
    }
}
