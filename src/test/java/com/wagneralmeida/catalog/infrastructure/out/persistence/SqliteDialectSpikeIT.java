package com.wagneralmeida.catalog.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import com.wagneralmeida.catalog.CatalogApplication;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig.TransactionMode;
import org.sqlite.SQLiteConnection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CatalogApplication.class)
class SqliteDialectSpikeIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private ProductJpaRepository productRepository;

    @Autowired
    private SellerProductJpaRepository sellerProductRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void mapsAndRoundTripsBothTables() {
        ProductEntity product = productRepository.saveAndFlush(
                new ProductEntity("Notebook", "Acme", "Computers", "notebook"));
        SellerProductEntity listing = sellerProductRepository.saveAndFlush(
                new SellerProductEntity("Seller A", "SKU-1", product.getId()));

        assertThat(productRepository.findById(product.getId())).get()
                .extracting(ProductEntity::getName, ProductEntity::getBrand,
                        ProductEntity::getCategory, ProductEntity::getNormalizedName)
                .containsExactly("Notebook", "Acme", "Computers", "notebook");
        assertThat(sellerProductRepository.findById(listing.getId())).get()
                .extracting(SellerProductEntity::getSellerName,
                        SellerProductEntity::getSellerProductId,
                        SellerProductEntity::getProductId)
                .containsExactly("Seller A", "SKU-1", product.getId());
    }

    @Test
    void enforcesNamedIndexesAndConstraints() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(indexNames(jdbc, "Product"))
                .contains("UX_Product_NormalizedName");
        assertThat(indexNames(jdbc, "SellerProduct"))
                .contains("IX_SellerProduct_ProductId")
                .anyMatch(name -> name.startsWith("sqlite_autoindex_SellerProduct_"));
        assertThat(jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?",
                String.class, "table", "SellerProduct"))
                .contains("CONSTRAINT UX_SellerProduct_Seller_Code UNIQUE (SellerName, SellerProductId)");

        Long productId = jdbc.queryForObject(
                "INSERT INTO Product (Name, NormalizedName) VALUES (?, ?) RETURNING Id",
                Long.class, "Unique product", "unique product");
        jdbc.update(
                "INSERT INTO SellerProduct (SellerName, SellerProductId, ProductId) VALUES (?, ?, ?)",
                "Seller B", "SKU-2", productId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO Product (Name, NormalizedName) VALUES (?, ?)",
                "Duplicate", "unique product"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO SellerProduct (SellerName, SellerProductId, ProductId) VALUES (?, ?, ?)",
                "Seller B", "SKU-2", productId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO SellerProduct (SellerName, SellerProductId, ProductId) VALUES (?, ?, ?)",
                "Seller B", "ORPHAN", Long.MAX_VALUE))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void c14_maliciousTextIsData() {
        String maliciousText = "x'; DROP TABLE Product; --";
        ProductEntity product = productRepository.saveAndFlush(
                new ProductEntity(maliciousText, null, null, "malicious text"));

        assertThat(productRepository.findById(product.getId())).get()
                .extracting(ProductEntity::getName)
                .isEqualTo(maliciousText);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = ? AND name IN (?, ?) ORDER BY name",
                String.class, "table", "Product", "SellerProduct"))
                .containsExactly("Product", "SellerProduct");
    }

    @Test
    void configuresRequiredSqliteSettingsAndImmediateTransactions() throws SQLException {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(1);

        try (Connection firstWriter = dataSource.getConnection();
                Connection competingWriter = DriverManager.getConnection(
                        "jdbc:sqlite:" + DATABASE.toAbsolutePath() + "?busy_timeout=25")) {
            assertThat(pragma(firstWriter, "foreign_keys")).isEqualTo("1");
            assertThat(pragma(firstWriter, "journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(Integer.parseInt(pragma(firstWriter, "busy_timeout"))).isPositive();
            assertThat(firstWriter.unwrap(SQLiteConnection.class)
                    .getConnectionConfig().getTransactionMode())
                    .isEqualTo(TransactionMode.IMMEDIATE);

            firstWriter.setAutoCommit(false);
            try (Statement statement = firstWriter.createStatement()) {
                statement.executeQuery("SELECT COUNT(*) FROM Product").close();
            }

            assertThatThrownBy(() -> insertProbe(competingWriter, "blocked probe"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("SQLITE_BUSY");

            firstWriter.rollback();
            firstWriter.setAutoCommit(true);
            insertProbe(competingWriter, "released probe");
        }
    }

    private List<String> indexNames(JdbcTemplate jdbc, String table) {
        return jdbc.query("PRAGMA index_list('" + table + "')",
                (row, number) -> row.getString("name"));
    }

    private static String jdbcUrl() {
        return "jdbc:sqlite:" + DATABASE.toAbsolutePath()
                + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&transaction_mode=IMMEDIATE";
    }

    private String pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            return result.getString(1);
        }
    }

    private void insertProbe(Connection connection, String normalizedName) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO Product (Name, NormalizedName) VALUES (?, ?)")) {
            statement.setString(1, normalizedName);
            statement.setString(2, normalizedName);
            statement.executeUpdate();
        }
    }
}
