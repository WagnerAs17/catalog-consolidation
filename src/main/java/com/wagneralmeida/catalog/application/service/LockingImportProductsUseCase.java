package com.wagneralmeida.catalog.application.service;

import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportReport;
import java.util.List;
import java.util.Objects;

public final class LockingImportProductsUseCase implements ImportProductsUseCase {

    private final ImportProductsUseCase delegate;
    private final ImportLock importLock;

    public LockingImportProductsUseCase(ImportProductsUseCase delegate, ImportLock importLock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.importLock = Objects.requireNonNull(importLock, "importLock");
    }

    @Override
    public ImportReport importProducts(List<ImportRecord> records) {
        if (!importLock.tryAcquire()) {
            throw new ImportLock.ImportInProgressException();
        }
        try {
            return delegate.importProducts(records);
        } finally {
            importLock.release();
        }
    }
}
