package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportRecord.JsonType;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import com.wagneralmeida.catalog.domain.ImportReport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

class ImportControllerParsingTest {

    private ImportProductsUseCase useCase;
    private ArgumentCaptor<List<ImportRecord>> records;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(ImportProductsUseCase.class);
        records = ArgumentCaptor.forClass(List.class);
        when(useCase.importProducts(anyList())).thenReturn(new ImportReport(UUID.randomUUID(), List.of()));
        ImportMetrics metrics = new ImportMetrics(new SimpleMeterRegistry());
        ImportFileParser parser = new ImportFileParser(
                new ObjectMapper(), new ImportRecordMapper(), DataSize.ofMegabytes(5), 5_000);
        ImportController controller = new ImportController(useCase, parser, metrics);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(metrics))
                .addFilters(new ImportIdFilter())
                .build();
    }

    @Test
    void parsesUtf8BomAndIgnoresPartMetadataAndUnknownFields() throws Exception {
        byte[] json = withBom("""
                [{
                  "Id": 123456789012345678901234567890,
                  "SellerName": "Lojá",
                  "Name": "Câmera",
                  "Brand": null,
                  "Category": true,
                  "Unknown": {"command": "ignored"}
                }]
                """);
        MockMultipartFile file = new MockMultipartFile(
                "file", "ignored-command.sh", "application/x-ignored", json);

        mockMvc.perform(multipart("/imports").file(file))
                .andExpect(status().isOk());

        List<ImportRecord> captured = captureRecords();
        assertThat(captured).singleElement().satisfies(record -> {
            assertThat(record.position()).isOne();
            assertThat(record.elementType()).isEqualTo(JsonType.OBJECT);
            assertThat(record.fields())
                    .containsEntry("Id", RawValue.integer("123456789012345678901234567890"))
                    .containsEntry("SellerName", RawValue.string("Lojá"))
                    .containsEntry("Name", RawValue.string("Câmera"))
                    .containsEntry("Brand", RawValue.nullValue())
                    .containsEntry("Category", RawValue.booleanValue(true))
                    .doesNotContainKey("Unknown");
        });
    }

    @Test
    void forwardsEveryElementAndRawJsonTypeWithoutDomainValidation() throws Exception {
        MockMultipartFile file = jsonFile("""
                [
                  {"Id":"ok","SellerName":"A","Name":"One","Brand":[],"Category":{}},
                  false,
                  {"Id":1.0,"SellerName":7,"Name":null,"Brand":"B","Category":"C"},
                  [1,2],
                  "text"
                ]
                """);

        mockMvc.perform(multipart("/imports").file(file))
                .andExpect(status().isOk());

        List<ImportRecord> captured = captureRecords();
        assertThat(captured).hasSize(5);
        assertThat(captured).extracting(ImportRecord::position)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(captured).extracting(ImportRecord::elementType)
                .containsExactly(JsonType.OBJECT, JsonType.BOOLEAN, JsonType.OBJECT, JsonType.ARRAY, JsonType.STRING);
        assertThat(captured.get(0).fields())
                .containsEntry("Id", RawValue.string("ok"))
                .containsEntry("Brand", RawValue.arrayValue())
                .containsEntry("Category", RawValue.objectValue());
        assertThat(captured.get(2).fields())
                .containsEntry("Id", RawValue.decimal("1.0"))
                .containsEntry("SellerName", RawValue.integer("7"))
                .containsEntry("Name", RawValue.nullValue());
    }

    @Test
    void c20_emptyArrayReturnsZeroTotals() throws Exception {
        mockMvc.perform(multipart("/imports").file(jsonFile("[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.records").value(0))
                .andExpect(jsonPath("$.totals.createdAndLinked").value(0))
                .andExpect(jsonPath("$.totals.linkedToExisting").value(0))
                .andExpect(jsonPath("$.totals.alreadyLinked").value(0))
                .andExpect(jsonPath("$.totals.notProcessed").value(0))
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results").isEmpty());

        assertThat(captureRecords()).isEmpty();
    }

    private MockMultipartFile jsonFile(String json) {
        return new MockMultipartFile(
                "file", "anything.dat", "text/plain", json.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] withBom(String json) {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[content.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(content, 0, bytes, 3, content.length);
        return bytes;
    }

    private List<ImportRecord> captureRecords() {
        org.mockito.Mockito.verify(useCase).importProducts(records.capture());
        return records.getValue();
    }
}
