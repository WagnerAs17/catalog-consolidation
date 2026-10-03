package com.wagneralmeida.catalog.application.port.in;

import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportReport;
import java.util.List;

public interface ImportProductsUseCase {

    ImportReport importProducts(List<ImportRecord> records);
}
