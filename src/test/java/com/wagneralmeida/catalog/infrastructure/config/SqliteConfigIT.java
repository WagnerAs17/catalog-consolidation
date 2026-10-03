package com.wagneralmeida.catalog.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.application.port.out.ProductRepository;
import com.wagneralmeida.catalog.application.port.out.SellerProductRepository;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig.TransactionMode;
import org.sqlite.SQLiteConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CatalogApplication.class)
class SqliteConfigIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private SqliteConfig sqliteConfig;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SellerProductRepository sellerProductRepository;

    @Test
    void configuresTheSelectedPathHibernateValidationAndAdapters() throws IOException {
        assertThat(sqliteConfig).isNotNull();
        assertThat(environment.getProperty("catalog.db.path"))
                .isEqualTo(DATABASE.toAbsolutePath().toString());
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(productRepository).isNotNull();
        assertThat(sellerProductRepository).isNotNull();

        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(applicationYaml).contains("path: ./data/work/catalog.db");
    }

    @Test
    void everyConnectionUsesRequiredPragmasAndImmediateTransactions() throws SQLException {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isOne();

        assertRequiredSettings(dataSource.getConnection());
        assertRequiredSettings(dataSource.getConnection());
    }

    private void assertRequiredSettings(Connection connection) throws SQLException {
        try (connection) {
            assertThat(pragma(connection, "foreign_keys")).isEqualTo("1");
            assertThat(pragma(connection, "journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(Integer.parseInt(pragma(connection, "busy_timeout"))).isPositive();
            assertThat(connection.unwrap(SQLiteConnection.class)
                    .getConnectionConfig().getTransactionMode())
                    .isEqualTo(TransactionMode.IMMEDIATE);
        }
    }

    private String pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            return result.getString(1);
        }
    }

}
