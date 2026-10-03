package com.wagneralmeida.catalog.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SchemaScriptConsistencyTest {

    private static final Path SPEC = Path.of("docs/spec.md");

    @Test
    void catalogSchemaMatchesSection11Exactly() throws IOException {
        assertThat(read("src/main/resources/db/catalog-schema.sql"))
                .isEqualTo(extractSqlBlock("### Schema script"));
    }

    @Test
    void originalSchemaMatchesSection11Exactly() throws IOException {
        assertThat(read("src/test/resources/db/original-schema.sql"))
                .isEqualTo(extractSqlBlock("### Original DDL (for test fixtures)"));
    }

    private String extractSqlBlock(String heading) throws IOException {
        String specification = read(SPEC.toString());
        int headingStart = specification.indexOf(heading);
        assertThat(headingStart).as("heading %s", heading).isNotNegative();

        String fence = "```sql\n";
        int sqlStart = specification.indexOf(fence, headingStart);
        assertThat(sqlStart).as("SQL fence after %s", heading).isNotNegative();
        sqlStart += fence.length();

        int sqlEnd = specification.indexOf("\n```", sqlStart);
        assertThat(sqlEnd).as("closing SQL fence after %s", heading).isNotNegative();
        return specification.substring(sqlStart, sqlEnd) + "\n";
    }

    private String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
