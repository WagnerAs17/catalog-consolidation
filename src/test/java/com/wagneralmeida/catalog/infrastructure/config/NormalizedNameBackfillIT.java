package com.wagneralmeida.catalog.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CatalogApplication.class)
class NormalizedNameBackfillIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private NormalizedNameBackfill backfill;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearProducts() {
        jdbc.update("DELETE FROM SellerProduct");
        jdbc.update("DELETE FROM Product");
    }

    @Test
    void fillsEveryNullNormalizedNameWithTextNormalizer() throws Exception {
        jdbc.update("INSERT INTO Product (Name, Brand, Category) VALUES (?, ?, ?)",
                "  iPhône   15 “Pro” ", "Apple", "Phones");
        jdbc.update("INSERT INTO Product (Name) VALUES (?)", "Levi's");

        backfill.run(new DefaultApplicationArguments(new String[0]));

        assertThat(jdbc.queryForList(
                "SELECT NormalizedName FROM Product ORDER BY Id", String.class))
                .containsExactly("iphone 15 pro", "levis");
    }

    @Test
    void repeatRunIsIdempotent() throws Exception {
        jdbc.update("INSERT INTO Product (Name) VALUES (?)", "Câmera");

        backfill.run(new DefaultApplicationArguments(new String[0]));
        String firstValue = jdbc.queryForObject(
                "SELECT NormalizedName FROM Product", String.class);
        backfill.run(new DefaultApplicationArguments(new String[0]));

        assertThat(jdbc.queryForObject("SELECT NormalizedName FROM Product", String.class))
                .isEqualTo(firstValue)
                .isEqualTo("camera");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM Product WHERE NormalizedName IS NULL", Integer.class))
                .isZero();
    }

    @Test
    void collisionRollsBackEveryNormalizedNameChange() {
        jdbc.update("INSERT INTO Product (Name) VALUES (?)", "Café");
        jdbc.update("INSERT INTO Product (Name) VALUES (?)", "Cafe");

        assertThatThrownBy(() -> backfill.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM Product WHERE NormalizedName IS NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void leftoverNullFailsApplicationStartupAndRollsBack() throws Exception {
        Path startupDatabase = SqliteIntegrationTestSupport.createDatabase();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + startupDatabase.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO Product (Name) VALUES ('Blocked')");
            statement.execute("""
                    CREATE TRIGGER keep_normalized_name_null
                    AFTER UPDATE OF NormalizedName ON Product
                    WHEN NEW.Name = 'Blocked'
                    BEGIN
                        UPDATE Product SET NormalizedName = NULL WHERE Id = NEW.Id;
                    END
                    """);
        }

        assertThatThrownBy(() -> new SpringApplicationBuilder(CatalogApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--catalog.db.path=" + startupDatabase.toAbsolutePath(),
                        "--spring.main.banner-mode=off"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NormalizedName backfill left NULL values");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + startupDatabase.toAbsolutePath());
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM Product WHERE NormalizedName IS NULL")) {
            assertThat(result.getInt(1)).isOne();
        }
    }
}
