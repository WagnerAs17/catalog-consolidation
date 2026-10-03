package com.wagneralmeida.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DocumentationConsistencyTest {

    @Test
    void readmeContainsTheRequiredOperationalContract() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertThat(readme).contains(
                "data/catalog.db",
                "data/products.json",
                "data/work/catalog.db",
                "catalog.db.path",
                "./data/work/catalog.db",
                "catalog.import.max-body-size",
                "5 MB",
                "catalog.import.max-records",
                "5000",
                "Authentication and authorization are deliberately out of scope",
                "400 `FILE_UNREADABLE`",
                "409 `IMPORT_IN_PROGRESS`",
                "413 `FILE_TOO_LARGE`",
                "415 `UNSUPPORTED_MEDIA_TYPE`",
                "422 `NOT_A_JSON_ARRAY`",
                "422 `TOO_MANY_RECORDS`",
                "500 `INTERNAL_ERROR`",
                "catalog.import.records",
                "catalog.import.rejections",
                "catalog.import.duration",
                "catalog.import.failures",
                "catalog.import.file.rejections",
                "269 records",
                "3 `CREATED_AND_LINKED`",
                "265 `LINKED_TO_EXISTING`",
                "1 `ALREADY_LINKED`",
                "0 `NOT_PROCESSED`",
                "1 `CATEGORY_DIVERGENCE`",
                "269 `ALREADY_LINKED`");
    }

    @Test
    void workingDatabaseDirectoryIsIgnored() throws IOException {
        String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);
        assertThat(gitignore.lines()).contains("data/work/");
    }
}
