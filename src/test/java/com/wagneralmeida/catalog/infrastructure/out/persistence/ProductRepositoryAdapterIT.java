package com.wagneralmeida.catalog.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.application.port.out.ProductRepository;
import com.wagneralmeida.catalog.domain.Product;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CatalogApplication.class)
class ProductRepositoryAdapterIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private ProductRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM SellerProduct");
        jdbc.update("DELETE FROM Product");
    }

    @Test
    void c02_findsByNormalizedNameAndPreservesExistingProductData() {
        Long id = jdbc.queryForObject(
                "INSERT INTO Product (Name, Brand, Category, NormalizedName) VALUES (?, ?, ?, ?) RETURNING Id",
                Long.class, "Catalog Name", "Catalog Brand", "Photography", "catalog name");

        Product found = repository.findByNormalizedName("catalog name").orElseThrow();

        assertThat(found).isEqualTo(
                new Product(id, "Catalog Name", "Catalog Brand", "Photography", "catalog name"));
        assertThat(productRow(id)).containsAllEntriesOf(Map.of(
                "Name", "Catalog Name",
                "Brand", "Catalog Brand",
                "Category", "Photography",
                "NormalizedName", "catalog name"));
    }

    @Test
    void c12_lookupOfDivergentInputDoesNotChangeTheCatalogProduct() {
        Long id = jdbc.queryForObject(
                "INSERT INTO Product (Name, Brand, Category, NormalizedName) VALUES (?, ?, ?, ?) RETURNING Id",
                Long.class, "Camera", "Acme", "Photography", "camera");

        assertThat(repository.findByNormalizedName("camera")).contains(
                new Product(id, "Camera", "Acme", "Photography", "camera"));

        assertThat(jdbc.queryForObject("SELECT Category FROM Product WHERE Id = ?", String.class, id))
                .isEqualTo("Photography");
    }

    @Test
    void createsNewProductAndReturnsItsGeneratedIdentifier() {
        Product created = repository.create(
                new Product(null, "Notebook", "Acme", "Computers", "notebook"));

        assertThat(created.id()).isPositive();
        assertThat(repository.findByNormalizedName("notebook")).contains(created);
    }

    private Map<String, Object> productRow(Long id) {
        return jdbc.queryForMap(
                "SELECT Name, Brand, Category, NormalizedName FROM Product WHERE Id = ?", id);
    }

}
