package com.wagneralmeida.catalog.infrastructure.in.web;

import com.wagneralmeida.catalog.application.service.ImportLock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ImportMetrics metrics;

    public ApiExceptionHandler(ImportMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @ExceptionHandler(ImportLock.ImportInProgressException.class)
    ResponseEntity<ProblemDetail> importInProgress() {
        LOGGER.info("Import request rejected");
        metrics.recordFileRejection("IMPORT_IN_PROGRESS");
        return problem(
                HttpStatus.CONFLICT,
                "IMPORT_IN_PROGRESS",
                "Import in progress",
                "Another import is already running.");
    }

    @ExceptionHandler(FileRejectionException.class)
    ResponseEntity<ProblemDetail> fileRejected(FileRejectionException exception) {
        return fileRejected(exception.rejection());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> fileTooLarge() {
        return fileRejected(FileRejection.FILE_TOO_LARGE);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> fileUnreadable() {
        return fileRejected(FileRejection.FILE_UNREADABLE);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMediaType() {
        LOGGER.info("Import request rejected");
        metrics.recordFileRejection("UNSUPPORTED_MEDIA_TYPE");
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported media type",
                "The request must use multipart/form-data.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> internalError() {
        LOGGER.info("Import request failed");
        metrics.recordFailure();
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal error",
                "The import could not be completed.");
    }

    private ResponseEntity<ProblemDetail> fileRejected(FileRejection rejection) {
        LOGGER.info("Import request rejected");
        metrics.recordFileRejection(rejection.name());
        return problem(rejection.status(), rejection.name(), rejection.title(), rejection.detail());
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
