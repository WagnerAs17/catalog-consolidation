package com.wagneralmeida.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductRulesTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void c12_categoryDivergencePreservesCatalog() {
        Product existing = new Product(42L, "Camera", "Acme", "Photography", "camera");
        ImportRecord.Valid record = valid("Camera", "Acme", "Photo");

        List<WarningCode> warnings = existing.warningsFor(record, normalizer);

        assertThat(warnings).containsExactly(WarningCode.CATEGORY_DIVERGENCE);
        assertThat(existing).isEqualTo(new Product(42L, "Camera", "Acme", "Photography", "camera"));
    }

    @Test
    void categoryDivergenceRequiresTwoPresentDifferentCategories() {
        ImportRecord.Valid absentCategory = valid("Camera", "Acme", null);
        ImportRecord.Valid equivalentCategory = valid("Camera", "Acme", "Phôtography");
        Product withoutCategory = new Product(42L, "Camera", "Acme", null, "camera");
        Product withCategory = new Product(43L, "Camera", "Acme", "Photography", "camera");

        assertThat(withoutCategory.warningsFor(equivalentCategory, normalizer)).isEmpty();
        assertThat(withCategory.warningsFor(absentCategory, normalizer)).isEmpty();
        assertThat(withCategory.warningsFor(equivalentCategory, normalizer)).isEmpty();
    }

    @Test
    void firstOccurrenceDefinesStoredNewProductText() {
        ImportRecord.Valid first = valid("  Fancy \t Product\n XL  ", "  Acme  ", "  Accessories  ");

        Product product = Product.from(first);

        assertThat(product).isEqualTo(new Product(null, "Fancy Product XL", "Acme", "Accessories", "fancy product xl"));
    }

    @Test
    void normalizedEmptyBrandIsStoredAsAbsent() {
        ImportRecord.Valid first = valid("Cable", "!!!", null);

        Product product = Product.from(first);

        assertThat(product.brand()).isNull();
    }

    @Test
    void sellerProductUsesTheFixedListingNames() {
        SellerProduct listing = new SellerProduct(null, "Acme", "000123", 42L);

        assertThat(listing.sellerName()).isEqualTo("Acme");
        assertThat(listing.sellerProductId()).isEqualTo("000123");
        assertThat(listing.productId()).isEqualTo(42L);
    }

    @Test
    void importReportTotalsMatchItsDefensivelyCopiedResults() {
        List<ImportReport.Result> results = new ArrayList<>(List.of(
                ImportReport.Result.processed(1, "Acme", "1", Outcome.CREATED_AND_LINKED, 41L, List.of()),
                ImportReport.Result.processed(2, "Acme", "2", Outcome.LINKED_TO_EXISTING, 42L,
                        List.of(WarningCode.CATEGORY_DIVERGENCE)),
                ImportReport.Result.processed(3, "Acme", "3", Outcome.ALREADY_LINKED, 42L, List.of()),
                ImportReport.Result.notProcessed(4, "Acme", "4", RejectionReason.INCONCLUSIVE, "BRAND_MISMATCH")));

        ImportReport report = new ImportReport(UUID.randomUUID(), results);
        results.clear();

        assertThat(report.totals()).isEqualTo(new ImportReport.Totals(4, 1, 1, 1, 1));
        assertThat(report.results()).hasSize(4);
        assertThat(report.results().get(1).warnings()).containsExactly(WarningCode.CATEGORY_DIVERGENCE);
    }

    private ImportRecord.Valid valid(String name, String brand, String category) {
        Map<String, ImportRecord.RawValue> fields = new java.util.HashMap<>();
        fields.put("Id", ImportRecord.RawValue.string("sku-1"));
        fields.put("SellerName", ImportRecord.RawValue.string("Acme"));
        fields.put("Name", ImportRecord.RawValue.string(name));
        if (brand != null) {
            fields.put("Brand", ImportRecord.RawValue.string(brand));
        }
        if (category != null) {
            fields.put("Category", ImportRecord.RawValue.string(category));
        }
        return (ImportRecord.Valid) ImportRecord.object(1, fields).validate(normalizer);
    }
}
