package com.wagneralmeida.catalog.domain;

import java.util.List;
import java.util.UUID;

public record ImportReport(UUID importId, Totals totals, List<Result> results) {

    public ImportReport {
        results = List.copyOf(results);
    }

    public ImportReport(UUID importId, List<Result> results) {
        this(importId, Totals.from(results), results);
    }

    public record Totals(
            int records,
            int createdAndLinked,
            int linkedToExisting,
            int alreadyLinked,
            int notProcessed) {

        private static Totals from(List<Result> results) {
            int createdAndLinked = 0;
            int linkedToExisting = 0;
            int alreadyLinked = 0;
            int notProcessed = 0;

            for (Result result : results) {
                switch (result.outcome()) {
                    case CREATED_AND_LINKED -> createdAndLinked++;
                    case LINKED_TO_EXISTING -> linkedToExisting++;
                    case ALREADY_LINKED -> alreadyLinked++;
                    case NOT_PROCESSED -> notProcessed++;
                }
            }

            return new Totals(results.size(), createdAndLinked, linkedToExisting, alreadyLinked, notProcessed);
        }
    }

    public record Result(
            int position,
            String sellerName,
            String sellerProductId,
            Outcome outcome,
            Long productId,
            RejectionReason reason,
            String detail,
            List<WarningCode> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }

        public static Result notProcessed(
                int position,
                String sellerName,
                String sellerProductId,
                RejectionReason reason,
                String detail) {
            return new Result(
                    position,
                    sellerName,
                    sellerProductId,
                    Outcome.NOT_PROCESSED,
                    null,
                    reason,
                    detail,
                    List.of());
        }

        public static Result processed(
                int position,
                String sellerName,
                String sellerProductId,
                Outcome outcome,
                long productId,
                List<WarningCode> warnings) {
            return new Result(position, sellerName, sellerProductId, outcome, productId, null, null, warnings);
        }
    }
}
