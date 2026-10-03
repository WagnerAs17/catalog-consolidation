package com.wagneralmeida.catalog.infrastructure.out.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class SqliteIntegrationTestSupport {

    private static final Path ORIGINAL_SCHEMA = Path.of("src/test/resources/db/original-schema.sql");
    private static final Path CATALOG_SCHEMA = Path.of("src/main/resources/db/catalog-schema.sql");
    private static final Path FIXTURE_DIRECTORY = Path.of("src/test/resources/fixtures")
            .toAbsolutePath()
            .normalize();
    private static final Path DATABASE_TEMPLATE = createTemplate();

    private SqliteIntegrationTestSupport() {
    }

    public static Path createDatabase(String... fixtureNames) {
        try {
            Path database = Files.createTempFile("catalog-integration-", ".db");
            database.toFile().deleteOnExit();
            Files.copy(DATABASE_TEMPLATE, database, StandardCopyOption.REPLACE_EXISTING);
            if (fixtureNames.length > 0) {
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                    for (String fixtureName : fixtureNames) {
                        applySql(connection, fixture(fixtureName));
                    }
                }
            }
            return database;
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not initialize a temporary SQLite database", exception);
        }
    }

    private static Path createTemplate() {
        try {
            Path template = Files.createTempFile("catalog-integration-template-", ".db");
            template.toFile().deleteOnExit();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + template.toAbsolutePath())) {
                applySql(connection, ORIGINAL_SCHEMA);
                applySql(connection, CATALOG_SCHEMA);
            }
            return template;
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not initialize the SQLite database template", exception);
        }
    }

    public static void registerCatalogDbPath(DynamicPropertyRegistry registry, Path database) {
        registry.add("catalog.db.path", () -> database.toAbsolutePath().toString());
    }

    private static Path fixture(String fixtureName) {
        Path fixture = FIXTURE_DIRECTORY.resolve(fixtureName).normalize();
        if (!fixture.startsWith(FIXTURE_DIRECTORY) || !Files.isRegularFile(fixture)) {
            throw new IllegalArgumentException("Fixture must be a file under src/test/resources/fixtures");
        }
        return fixture;
    }

    private static void applySql(Connection connection, Path resource) throws IOException, SQLException {
        String sql = Files.readString(resource, StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
    }
}
