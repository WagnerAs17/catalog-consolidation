package com.wagneralmeida.catalog.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wagneralmeida.catalog.CatalogApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CatalogApplication.class)
class SqliteTemporaryFileIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase(
            "synthetic-catalog.sql");

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Test
    void createsAFileFromBothExactSchemasAndLoadsSyntheticFixture() {
        assertThat(Files.isRegularFile(DATABASE)).isTrue();
        assertThat(environment.getProperty("catalog.db.path"))
                .isEqualTo(DATABASE.toAbsolutePath().toString());
        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = ? AND name IN (?, ?) ORDER BY name",
                String.class, "table", "Product", "SellerProduct"))
                .containsExactly("Product", "SellerProduct");
        assertThat(jdbc.queryForObject(
                "SELECT Name FROM Product WHERE NormalizedName = ?",
                String.class, "synthetic camera"))
                .isEqualTo("Synthetic Camera");
        assertThat(jdbc.queryForList("PRAGMA table_info('SellerProduct')"))
                .anySatisfy(column -> {
                    assertThat(column.get("name")).isEqualTo("SellerProductId");
                    assertThat(column.get("type")).isEqualTo("TEXT");
                });
    }

    @Test
    void createsFreshIndependentDatabasesWithoutRepositoryData() throws Exception {
        Path first = SqliteIntegrationTestSupport.createDatabase();
        Path second = SqliteIntegrationTestSupport.createDatabase();

        assertThat(first).isNotEqualTo(second);
        Path repositoryData = Path.of("data").toAbsolutePath().normalize();
        assertThat(first.toAbsolutePath().normalize().startsWith(repositoryData)).isFalse();
        assertThat(second.toAbsolutePath().normalize().startsWith(repositoryData)).isFalse();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + second.toAbsolutePath());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM Product")) {
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    void rejectsFixturePathsOutsideTheSyntheticFixtureDirectory() {
        assertThatThrownBy(() -> SqliteIntegrationTestSupport.createDatabase("../db/original-schema.sql"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
