package com.ebank.transactions.web;

import com.ebank.transactions.application.LedgerExport;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Admin only: ledger entries of closed UTC days as CSV ({@code from} and {@code to} inclusive,
 * {@code yyyy-MM-dd}, 31 days at most). {@code X-Ledger-Entry-Count} gives the number of rows, so
 * the receiving side can check it got them all.
 */
@RestController
class LedgerExportController {

    static final String TEXT_CSV = "text/csv";

    private final LedgerExport export;

    LedgerExportController(LedgerExport export) {
        this.export = export;
    }

    @GetMapping(path = "/api/v1/ledger/export", produces = TEXT_CSV)
    ResponseEntity<StreamingResponseBody> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LedgerExport.Range range = export.prepare(from, to);
        long count = export.count(range);
        StreamingResponseBody body = stream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
            export.write(range, out);
            out.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, TEXT_CSV + ";charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("ebank-ledger-" + range.from() + "-" + range.to() + ".csv")
                        .build()
                        .toString())
                .header("X-Ledger-Entry-Count", Long.toString(count))
                .body(body);
    }
}
