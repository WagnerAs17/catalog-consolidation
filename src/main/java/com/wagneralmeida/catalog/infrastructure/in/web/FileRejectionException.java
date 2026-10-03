package com.wagneralmeida.catalog.infrastructure.in.web;

import java.util.Objects;

public final class FileRejectionException extends RuntimeException {

    private final FileRejection rejection;

    public FileRejectionException(FileRejection rejection) {
        super(Objects.requireNonNull(rejection, "rejection").detail());
        this.rejection = rejection;
    }

    public FileRejection rejection() {
        return rejection;
    }
}
