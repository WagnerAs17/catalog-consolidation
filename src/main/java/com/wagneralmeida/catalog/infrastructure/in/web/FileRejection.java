package com.wagneralmeida.catalog.infrastructure.in.web;

import org.springframework.http.HttpStatus;

public enum FileRejection {
    FILE_TOO_LARGE(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "File too large",
            "The uploaded file exceeds the configured maximum."),
    FILE_UNREADABLE(
            HttpStatus.BAD_REQUEST,
            "File unreadable",
            "The uploaded file could not be read."),
    NOT_A_JSON_ARRAY(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Not a JSON array",
            "The uploaded JSON root must be an array."),
    TOO_MANY_RECORDS(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Too many records",
            "The uploaded file contains more than the configured maximum number of records.");

    private final HttpStatus status;
    private final String title;
    private final String detail;

    FileRejection(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }
}
