package com.wagneralmeida.catalog.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public final class TextNormalizer {

    public String normalize(String value) {
        Objects.requireNonNull(value, "value");

        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("['’\"“”]", "")
                .replaceAll("[^a-z0-9]", " ")
                .replaceAll(" +", " ")
                .trim();

        return normalized;
    }

    public String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }
}
