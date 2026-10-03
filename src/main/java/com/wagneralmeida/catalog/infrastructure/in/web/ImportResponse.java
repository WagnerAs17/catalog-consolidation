package com.wagneralmeida.catalog.infrastructure.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wagneralmeida.catalog.domain.ImportReport;
import com.wagneralmeida.catalog.domain.Outcome;
import com.wagneralmeida.catalog.domain.RejectionReason;
import com.wagneralmeida.catalog.domain.WarningCode;
import java.util.List;
import java.util.UUID;

public record ImportResponse(UUID importId, Totals totals, List<Result> results) {

    public ImportResponse {
        results = List.copyOf(results);
    }

    public static ImportResponse from(UUID importId, ImportReport report) {
        ImportReport.Totals reportTotals = report.totals();
        Totals totals = new Totals(
                reportTotals.records(),
                reportTotals.createdAndLinked(),
                reportTotals.linkedToExisting(),
                reportTotals.alreadyLinked(),
                reportTotals.notProcessed());
        List<Result> results = report.results().stream().map(Result::from).toList();
        return new ImportResponse(importId, totals, results);
    }

    public record Totals(
            int records,
            int createdAndLinked,
            int linkedToExisting,
            int alreadyLinked,
            int notProcessed) {}

    public record Result(
            int position,
            @JsonInclude(JsonInclude.Include.ALWAYS) String sellerName,
            @JsonInclude(JsonInclude.Include.ALWAYS) String sellerProductId,
            Outcome outcome,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long productId,
            @JsonInclude(JsonInclude.Include.NON_NULL) RejectionReason reason,
            @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
            @JsonInclude(JsonInclude.Include.ALWAYS) List<WarningCode> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }

        private static Result from(ImportReport.Result result) {
            return new Result(
                    result.position(),
                    result.sellerName(),
                    result.sellerProductId(),
                    result.outcome(),
                    result.productId(),
                    result.reason(),
                    result.detail(),
                    result.warnings());
        }
    }
}
