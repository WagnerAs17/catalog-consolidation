package com.wagneralmeida.catalog.infrastructure.in.web;

import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.domain.ImportReport;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ImportController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportController.class);

    private final ImportProductsUseCase useCase;
    private final ImportFileParser parser;
    private final ImportMetrics metrics;

    public ImportController(ImportProductsUseCase useCase, ImportFileParser parser, ImportMetrics metrics) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @PostMapping(
            path = "/imports",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ImportResponse importFile(@RequestPart("file") MultipartFile file) {
        UUID importId = ImportIdFilter.currentImportId();
        long startedAt = System.nanoTime();
        LOGGER.info("Import started");
        try {
            ImportReport report = useCase.importProducts(parser.parse(file));
            metrics.recordCommitted(report);
            LOGGER.info("Import completed");
            return ImportResponse.from(importId, report);
        } catch (RuntimeException exception) {
            LOGGER.info("Import failed");
            throw exception;
        } finally {
            metrics.recordDuration(System.nanoTime() - startedAt);
        }
    }
}
