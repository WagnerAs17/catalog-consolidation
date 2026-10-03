package com.wagneralmeida.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentityTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void c02_existingEquivalentProduct() {
        Product candidate = product(42L, "iPhone 15", "Apple", "iphone 15");

        Identity identity = Identity.decide(candidate, "apple", normalizer);

        assertThat(identity).isEqualTo(new Identity.Existing(candidate));
    }

    @Test
    void c04_bothBrandsAbsent() {
        Product candidate = product(42L, "Generic Cable", null, "generic cable");

        Identity identity = Identity.decide(candidate, null, normalizer);

        assertThat(identity).isEqualTo(new Identity.Existing(candidate));
    }

    @Test
    void c05_brandPresentOnOneSideOnly() {
        Product brandlessCandidate = product(42L, "Generic Cable", null, "generic cable");
        Product brandedCandidate = product(43L, "Other Cable", "Acme", "other cable");

        assertThat(Identity.decide(brandlessCandidate, "acme", normalizer))
                .isEqualTo(new Identity.Inconclusive("BRAND_PRESENT_ON_ONE_SIDE_ONLY"));
        assertThat(Identity.decide(brandedCandidate, null, normalizer))
                .isEqualTo(new Identity.Inconclusive("BRAND_PRESENT_ON_ONE_SIDE_ONLY"));
    }

    @Test
    void c06_brandMismatch() {
        Product candidate = product(42L, "Router", "Acme", "router");

        Identity identity = Identity.decide(candidate, "globex", normalizer);

        assertThat(identity).isEqualTo(new Identity.Inconclusive("BRAND_MISMATCH"));
    }

    @Test
    void c07_distinctNamesRemainDistinct() {
        assertThat(Identity.decide(null, "apple", normalizer)).isInstanceOf(Identity.New.class);
        assertThat(Identity.decide(null, null, normalizer)).isInstanceOf(Identity.New.class);
    }

    @Test
    void c21_laterBrandMakesIdentityInconclusive() {
        Product earlierInFile = product(null, "  Generic Cable  ", null, "generic cable");

        Identity identity = Identity.decide(earlierInFile, "acme", normalizer);

        assertThat(identity).isEqualTo(new Identity.Inconclusive("BRAND_PRESENT_ON_ONE_SIDE_ONLY"));
    }

    private Product product(Long id, String name, String brand, String normalizedName) {
        return new Product(id, name, brand, null, normalizedName);
    }
}
