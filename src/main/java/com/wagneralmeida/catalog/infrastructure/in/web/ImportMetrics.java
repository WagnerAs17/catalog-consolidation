package com.wagneralmeida.catalog.infrastructure.in.web;

import com.wagneralmeida.catalog.domain.Outcome;
import com.wagneralmeida.catalog.domain.ImportReport;
import com.wagneralmeida.catalog.domain.RejectionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ImportMetrics {

    static final Set<String> FILE_REJECTION_CODES = Set.of(
            "FILE_UNREADABLE",
            "IMPORT_IN_PROGRESS",
            "FILE_TOO_LARGE",
            "UNSUPPORTED_MEDIA_TYPE",
            "NOT_A_JSON_ARRAY",
            "TOO_MANY_RECORDS");

    private final Map<Outcome, Counter> records = new EnumMap<>(Outcome.class);
    private final Map<RejectionReason, Counter> rejections = new EnumMap<>(RejectionReason.class);
    private final Map<String, Counter> fileRejections;
    private final Timer duration;
    private final Counter failures;

    public ImportMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            records.put(outcome, registry.counter("catalog.import.records", "outcome", outcome.name()));
        }
        for (RejectionReason reason : RejectionReason.values()) {
            rejections.put(reason, registry.counter("catalog.import.rejections", "reason", reason.name()));
        }
        fileRejections = FILE_REJECTION_CODES.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                code -> code,
                code -> registry.counter("catalog.import.file.rejections", "code", code)));
        duration = registry.timer("catalog.import.duration");
        failures = registry.counter("catalog.import.failures");
    }

    void recordCommitted(ImportReport report) {
        for (ImportReport.Result result : report.results()) {
            records.get(result.outcome()).increment();
            if (result.reason() != null) {
                rejections.get(result.reason()).increment();
            }
        }
    }

    void recordFileRejection(String code) {
        Counter counter = fileRejections.get(code);
        if (counter == null) {
            throw new IllegalArgumentException("Unknown file rejection code");
        }
        counter.increment();
    }

    void recordFailure() {
        failures.increment();
    }

    void recordDuration(long elapsedNanoseconds) {
        duration.record(elapsedNanoseconds, TimeUnit.NANOSECONDS);
    }

}
