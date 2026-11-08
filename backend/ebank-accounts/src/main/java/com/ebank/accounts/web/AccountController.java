package com.ebank.accounts.web;

import com.ebank.accounts.application.AccountService;
import com.ebank.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private final AccountService service;
    private final AccountMapper mapper;

    AccountController(AccountService service, AccountMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    PageResponse<AccountResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return PageResponse.from(service.list(request).map(mapper::toResponse));
    }

    @GetMapping("/{accountId}")
    AccountResponse get(@PathVariable String accountId) {
        return mapper.toResponse(service.get(accountId));
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = mapper.toResponse(service.create(request.type(), request.currency()));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.accountId())).body(created);
    }

    @PutMapping("/{accountId}")
    AccountResponse update(@PathVariable String accountId, @Valid @RequestBody UpdateAccountRequest request) {
        return mapper.toResponse(service.changeType(accountId, request.type()));
    }

    @PatchMapping("/{accountId}/activate")
    AccountResponse activate(@PathVariable String accountId) {
        return mapper.toResponse(service.activate(accountId));
    }

    @PatchMapping("/{accountId}/disable")
    AccountResponse disable(@PathVariable String accountId) {
        return mapper.toResponse(service.disable(accountId));
    }

    @PatchMapping("/{accountId}/close")
    AccountResponse close(@PathVariable String accountId) {
        return mapper.toResponse(service.close(accountId));
    }

    @GetMapping("/{accountId}/history")
    PageResponse<AccountHistoryResponse> history(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by("changedAt").descending());
        return PageResponse.from(service.history(accountId, request).map(mapper::toResponse));
    }
}
