package com.wagneralmeida.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void c03_normalizationRecognizesEquivalentText() {
        assertThat(normalizer.normalize("  iPhône   15 “Pro” "))
                .isEqualTo("iphone 15 pro");
        assertThat(normalizer.normalize("Levi's"))
                .isEqualTo(normalizer.normalize("Levis"));
        assertThat(normalizer.normalize("Levi’s"))
                .isEqualTo(normalizer.normalize("Levis"));
    }

    @Test
    void decimalSeparatorDoesNotCollapseNumbersTogether() {
        assertThat(normalizer.normalize("5.5")).isEqualTo("5 5");
        assertThat(normalizer.normalize("5.5"))
                .isNotEqualTo(normalizer.normalize("55"));
    }

    @Test
    void c19_symbolOnlyNameIsNotNormalizable() {
        assertThat(normalizer.normalize("!!!")).isEmpty();
    }

    @Test
    void emptyNormalizedBrandIsAbsent() {
        assertThat(normalizer.normalizeOptional(null)).isNull();
        assertThat(normalizer.normalizeOptional(" “!!!” ")).isNull();
        assertThat(normalizer.normalizeOptional(" Acmé ")).isEqualTo("acme");
    }
}
