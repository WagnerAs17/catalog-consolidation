package com.wagneralmeida.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import com.wagneralmeida.catalog.domain.ImportReport;
import com.wagneralmeida.catalog.domain.Outcome;
import com.wagneralmeida.catalog.domain.Product;
import com.wagneralmeida.catalog.domain.RejectionReason;
import com.wagneralmeida.catalog.domain.SellerProduct;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CatalogApplication.class)
class ImportProductsServiceTest {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private ImportProductsUseCase useCase;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM SellerProduct");
        jdbc.update("DELETE FROM Product");
        jdbc.update("DELETE FROM sqlite_sequence WHERE name IN ('Product', 'SellerProduct')");
    }

    @Test
    void c01_createAndLinkNewProduct() {
        ImportReport report = useCase.importProducts(List.of(
                record(1, "sku-1", "Acme", " Camera ", "Brand", "Photo")));

        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.position()).isEqualTo(1);
            assertThat(result.outcome()).isEqualTo(Outcome.CREATED_AND_LINKED);
            assertThat(result.productId()).isEqualTo(1L);
        });
        assertThat(products()).containsExactly(
                new Product(1L, "Camera", "Brand", "Photo", "camera"));
        assertThat(listings()).containsExactly(
                new SellerProduct(1L, "Acme", "sku-1", 1L));
    }

    @Test
    void c02_linkExistingProduct() {
        long productId = addProduct("Camera", "Brand", "Photography", "camera");

        ImportReport report = useCase.importProducts(List.of(
                record(1, "sku-2", "Other", "Câmera", "brand", "Photo")));

        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo(Outcome.LINKED_TO_EXISTING);
            assertThat(result.productId()).isEqualTo(productId);
        });
        assertThat(products()).containsExactly(
                new Product(productId, "Camera", "Brand", "Photography", "camera"));
        assertThat(listings()).containsExactly(
                new SellerProduct(1L, "Other", "sku-2", productId));
    }

    @Test
    void c17_secondSellerSeesEarlierCreation() {
        ImportReport report = useCase.importProducts(List.of(
                record(1, "a-1", "Seller A", "Router", null, null),
                record(2, "b-1", "Seller B", "Rôuter", null, null)));

        assertThat(report.results()).extracting(ImportReport.Result::outcome)
                .containsExactly(Outcome.CREATED_AND_LINKED, Outcome.LINKED_TO_EXISTING);
        assertThat(report.results()).extracting(ImportReport.Result::position)
                .containsExactly(1, 2);
        assertThat(report.results()).extracting(ImportReport.Result::productId)
                .containsExactly(1L, 1L);
        assertThat(products()).hasSize(1);
        assertThat(listings()).hasSize(2);
    }

    @Test
    void c21_laterRecordSeesEarlierBrandlessProduct() {
        ImportReport report = useCase.importProducts(List.of(
                record(1, "a-1", "Seller A", "Cable", null, null),
                record(2, "b-1", "Seller B", "Cable", "Acme", null)));

        assertThat(report.results()).extracting(ImportReport.Result::outcome)
                .containsExactly(Outcome.CREATED_AND_LINKED, Outcome.NOT_PROCESSED);
        assertThat(report.results().get(1).reason()).isEqualTo(RejectionReason.INCONCLUSIVE);
        assertThat(report.results().get(1).detail()).isEqualTo("BRAND_PRESENT_ON_ONE_SIDE_ONLY");
        assertThat(products()).hasSize(1);
        assertThat(listings()).hasSize(1);
    }

    @Test
    void c08_repeatedCodeAlreadyLinked() {
        ImportReport report = useCase.importProducts(List.of(
                record(1, "sku-1", "Acme", "Camera", "Brand", null),
                record(2, "sku-1", "Acme", "Camera", "Brand", null)));

        assertThat(report.results()).extracting(ImportReport.Result::outcome)
                .containsExactly(Outcome.CREATED_AND_LINKED, Outcome.ALREADY_LINKED);
        assertThat(products()).hasSize(1);
        assertThat(listings()).hasSize(1);
    }

    @Test
    void c09_twoCodesLinkSameProduct() {
        ImportReport report = useCase.importProducts(List.of(
                record(1, "sku-1", "Acme", "Camera", null, null),
                record(2, "sku-2", "Acme", "Camera", null, null)));

        assertThat(report.results()).extracting(ImportReport.Result::outcome)
                .containsExactly(Outcome.CREATED_AND_LINKED, Outcome.LINKED_TO_EXISTING);
        assertThat(report.results()).extracting(ImportReport.Result::productId)
                .containsExactly(1L, 1L);
        assertThat(listings()).extracting(SellerProduct::sellerProductId)
                .containsExactly("sku-1", "sku-2");
    }

    @Test
    void c10_sameCodeAcrossSellersIsIndependent() {
        ImportReport report = useCase.importProducts(List.of(
                record(1, "shared-code", "Acme", "Camera", null, null),
                record(2, "shared-code", "Other", "Camera", null, null)));

        assertThat(report.results()).extracting(ImportReport.Result::outcome)
                .containsExactly(Outcome.CREATED_AND_LINKED, Outcome.LINKED_TO_EXISTING);
        assertThat(listings()).extracting(SellerProduct::sellerName)
                .containsExactly("Acme", "Other");
    }

    @Test
    void c11_existingListingNeverRedirects() {
        long productId = addProduct("Camera", null, null, "camera");
        addListing("Acme", "sku-1", productId);

        ImportReport report = useCase.importProducts(List.of(
                record(1, "sku-1", "Acme", "Router", null, null)));

        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo(Outcome.NOT_PROCESSED);
            assertThat(result.reason()).isEqualTo(RejectionReason.INCONSISTENT);
            assertThat(result.detail()).isEqualTo("LISTING_LINKED_TO_ANOTHER_PRODUCT");
            assertThat(result.productId()).isNull();
        });
        assertThat(products()).containsExactly(
                new Product(productId, "Camera", null, null, "camera"));
        assertThat(listings()).containsExactly(
                new SellerProduct(1L, "Acme", "sku-1", productId));
    }

    @Test
    void c15_reimportCreatesNothing() {
        addProduct("Cable", null, null, "cable");
        List<ImportRecord> records = List.of(
                record(1, "sku-1", "Acme", "Camera", null, null),
                record(2, "sku-2", "Acme", "Cable", "Brand", null),
                ImportRecord.object(3, Map.of()));

        ImportReport first = useCase.importProducts(records);
        int productCountAfterFirstRun = products().size();
        int listingCountAfterFirstRun = listings().size();
        ImportReport second = useCase.importProducts(records);

        assertThat(first.results()).extracting(ImportReport.Result::outcome)
                .containsExactly(Outcome.CREATED_AND_LINKED, Outcome.NOT_PROCESSED, Outcome.NOT_PROCESSED);
        assertThat(second.results()).extracting(ImportReport.Result::outcome)
                .containsExactly(Outcome.ALREADY_LINKED, Outcome.NOT_PROCESSED, Outcome.NOT_PROCESSED);
        assertThat(second.results()).extracting(ImportReport.Result::reason)
                .containsExactly(null, RejectionReason.INCONCLUSIVE, RejectionReason.INVALID_RECORD);
        assertThat(products()).hasSize(productCountAfterFirstRun);
        assertThat(listings()).hasSize(listingCountAfterFirstRun);
    }

    private long addProduct(String name, String brand, String category, String normalizedName) {
        return jdbc.queryForObject(
                "INSERT INTO Product (Name, Brand, Category, NormalizedName) VALUES (?, ?, ?, ?) RETURNING Id",
                Long.class, name, brand, category, normalizedName);
    }

    private void addListing(String sellerName, String sellerProductId, long productId) {
        jdbc.update(
                "INSERT INTO SellerProduct (SellerName, SellerProductId, ProductId) VALUES (?, ?, ?)",
                sellerName, sellerProductId, productId);
    }

    private List<Product> products() {
        return jdbc.query(
                "SELECT Id, Name, Brand, Category, NormalizedName FROM Product ORDER BY Id",
                (result, row) -> new Product(
                        result.getLong("Id"),
                        result.getString("Name"),
                        result.getString("Brand"),
                        result.getString("Category"),
                        result.getString("NormalizedName")));
    }

    private List<SellerProduct> listings() {
        return jdbc.query(
                "SELECT Id, SellerName, SellerProductId, ProductId FROM SellerProduct ORDER BY Id",
                (result, row) -> new SellerProduct(
                        result.getLong("Id"),
                        result.getString("SellerName"),
                        result.getString("SellerProductId"),
                        result.getLong("ProductId")));
    }

    private ImportRecord record(
            int position,
            String sellerProductId,
            String sellerName,
            String name,
            String brand,
            String category) {
        Map<String, RawValue> fields = new LinkedHashMap<>();
        fields.put("Id", RawValue.string(sellerProductId));
        fields.put("SellerName", RawValue.string(sellerName));
        fields.put("Name", RawValue.string(name));
        if (brand != null) {
            fields.put("Brand", RawValue.string(brand));
        }
        if (category != null) {
            fields.put("Category", RawValue.string(category));
        }
        return ImportRecord.object(position, fields);
    }
}
