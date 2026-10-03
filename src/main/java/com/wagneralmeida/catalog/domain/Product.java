package com.wagneralmeida.catalog.domain;

import java.util.List;
import java.util.Objects;

public record Product(Long id, String name, String brand, String category, String normalizedName) {

    public static Product from(ImportRecord.Valid record) {
        Objects.requireNonNull(record, "record");

        String storedName = record.name().replaceAll("\\s+", " ").trim();
        String storedBrand = record.normalizedBrand() == null ? null : record.brand();
        return new Product(null, storedName, storedBrand, record.category(), record.normalizedName());
    }

    public List<WarningCode> warningsFor(ImportRecord.Valid record, TextNormalizer normalizer) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(normalizer, "normalizer");

        if (category == null || record.category() == null) {
            return List.of();
        }

        String storedCategory = normalizer.normalize(category);
        String recordCategory = normalizer.normalize(record.category());
        if (storedCategory.equals(recordCategory)) {
            return List.of();
        }
        return List.of(WarningCode.CATEGORY_DIVERGENCE);
    }
}
