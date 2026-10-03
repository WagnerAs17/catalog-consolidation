package com.wagneralmeida.catalog.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.application.port.out.SellerProductRepository;
import com.wagneralmeida.catalog.domain.SellerProduct;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CatalogApplication.class)
class SellerProductRepositoryAdapterIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private SellerProductRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private Long productId;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM SellerProduct");
        jdbc.update("DELETE FROM Product");
        productId = jdbc.queryForObject(
                "INSERT INTO Product (Name, NormalizedName) VALUES (?, ?) RETURNING Id",
                Long.class, "Camera", "camera");
    }

    @Test
    void c08_sameSellerAndCodeCannotBeCreatedTwice() {
        repository.create(new SellerProduct(null, "Acme", "SKU-1", productId));

        assertThatThrownBy(() -> repository.create(
                new SellerProduct(null, "Acme", "SKU-1", productId)))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SellerProduct", Integer.class)).isOne();
    }

    @Test
    void c09_twoCodesForOneSellerCanPointToTheSameProduct() {
        SellerProduct first = repository.create(new SellerProduct(null, "Acme", "SKU-1", productId));
        SellerProduct second = repository.create(new SellerProduct(null, "Acme", "SKU-2", productId));

        assertThat(first.id()).isPositive();
        assertThat(second.id()).isPositive().isNotEqualTo(first.id());
        assertThat(first.productId()).isEqualTo(second.productId());
    }

    @Test
    void c10_sameCodeForDifferentSellersIsIndependent() {
        SellerProduct acme = repository.create(new SellerProduct(null, "Acme", "COMMON", productId));
        SellerProduct globex = repository.create(new SellerProduct(null, "Globex", "COMMON", productId));

        assertThat(repository.findBySellerNameAndSellerProductId("Acme", "COMMON")).contains(acme);
        assertThat(repository.findBySellerNameAndSellerProductId("Globex", "COMMON")).contains(globex);
    }

    @Test
    void c11_lookupNeverRedirectsAnExistingListing() {
        SellerProduct listing = repository.create(new SellerProduct(null, "Acme", "SKU-1", productId));

        SellerProduct found = repository.findBySellerNameAndSellerProductId("Acme", "SKU-1").orElseThrow();

        assertThat(found).isEqualTo(listing);
        assertThat(jdbc.queryForObject(
                "SELECT ProductId FROM SellerProduct WHERE Id = ?", Long.class, listing.id()))
                .isEqualTo(productId);
    }

    @Test
    void sellerNameIsTrimmedThenComparedExactlyWithCaseSensitivity() {
        SellerProduct listing = repository.create(new SellerProduct(null, "  Acme  ", "  SKU-1  ", productId));

        assertThat(listing.sellerName()).isEqualTo("Acme");
        assertThat(listing.sellerProductId()).isEqualTo("SKU-1");
        assertThat(repository.findBySellerNameAndSellerProductId(" Acme ", " SKU-1 ")).contains(listing);
        assertThat(repository.findBySellerNameAndSellerProductId("acme", "SKU-1")).isEmpty();
    }

}
