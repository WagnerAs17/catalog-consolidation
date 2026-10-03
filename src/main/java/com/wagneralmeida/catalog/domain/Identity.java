package com.wagneralmeida.catalog.domain;

import java.util.Objects;

public sealed interface Identity permits Identity.New, Identity.Existing, Identity.Inconclusive {

    public static Identity decide(Product candidate, String recordNormalizedBrand, TextNormalizer normalizer) {
        Objects.requireNonNull(normalizer, "normalizer");

        if (candidate == null) {
            return new New();
        }

        String candidateNormalizedBrand = normalizer.normalizeOptional(candidate.brand());
        if (candidateNormalizedBrand == null && recordNormalizedBrand == null) {
            return new Existing(candidate);
        }
        if (candidateNormalizedBrand == null || recordNormalizedBrand == null) {
            return new Inconclusive("BRAND_PRESENT_ON_ONE_SIDE_ONLY");
        }
        if (candidateNormalizedBrand.equals(recordNormalizedBrand)) {
            return new Existing(candidate);
        }
        return new Inconclusive("BRAND_MISMATCH");
    }

    record New() implements Identity {
    }

    record Existing(Product product) implements Identity {

        public Existing {
            Objects.requireNonNull(product, "product");
        }
    }

    record Inconclusive(String reason) implements Identity {

        public Inconclusive {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
