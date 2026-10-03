package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

class ImportFileParserTest {

    @Test
    void c18_fileLargerThanConfiguredMaximumIsRejected() {
        ImportFileParser parser = parser(DataSize.ofBytes(2), 5_000);

        assertRejection(parser, file(new byte[3]), FileRejection.FILE_TOO_LARGE);
    }

    @Test
    void c18_emptyMalformedUnreadableAndTrailingInputAreRejectedAsFileUnreadable() throws IOException {
        ImportFileParser parser = parser(DataSize.ofMegabytes(5), 5_000);
        MultipartFile unreadable = mock(MultipartFile.class);
        when(unreadable.getSize()).thenReturn(1L);
        when(unreadable.getBytes()).thenThrow(new IOException("internal detail"));

        assertRejection(parser, file(new byte[0]), FileRejection.FILE_UNREADABLE);
        assertRejection(parser, file(new byte[] {(byte) 0xC3, 0x28}), FileRejection.FILE_UNREADABLE);
        assertRejection(parser, json("[{"), FileRejection.FILE_UNREADABLE);
        assertRejection(parser, json("[] trailing-garbage"), FileRejection.FILE_UNREADABLE);
        assertRejection(parser, unreadable, FileRejection.FILE_UNREADABLE);
    }

    @Test
    void c18_validJsonThatIsNotAnArrayIsRejected() {
        assertRejection(
                parser(DataSize.ofMegabytes(5), 5_000),
                json("{\"Id\":\"1\"}"),
                FileRejection.NOT_A_JSON_ARRAY);
    }

    @Test
    void c18_arrayOverConfiguredRecordMaximumIsRejected() {
        assertRejection(
                parser(DataSize.ofMegabytes(5), 1),
                json("[{},{}]"),
                FileRejection.TOO_MANY_RECORDS);
    }

    @Test
    void parsesBomAndDelegatesEveryElementToTheRecordMapper() {
        ImportFileParser parser = parser(DataSize.ofMegabytes(5), 5_000);
        byte[] json = "[{\"Id\":123456789012345678901234567890},false]"
                .getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);

        var records = parser.parse(file(withBom));

        assertThat(records).hasSize(2);
        assertThat(records).extracting(record -> record.position()).containsExactly(1, 2);
        assertThat(records.getFirst().fields())
                .containsEntry("Id", RawValue.integer("123456789012345678901234567890"));
    }

    @Test
    void c20_emptyArrayReturnsNoRecords() {
        assertThat(parser(DataSize.ofMegabytes(5), 5_000).parse(json("[]"))).isEmpty();
    }

    private ImportFileParser parser(DataSize maxBodySize, int maxRecords) {
        return new ImportFileParser(new ObjectMapper(), new ImportRecordMapper(), maxBodySize, maxRecords);
    }

    private void assertRejection(ImportFileParser parser, MultipartFile file, FileRejection expected) {
        assertThatExceptionOfType(FileRejectionException.class)
                .isThrownBy(() -> parser.parse(file))
                .satisfies(exception -> assertThat(exception.rejection()).isEqualTo(expected));
    }

    private MockMultipartFile json(String json) {
        return file(json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "products.json", "application/json", bytes);
    }
}
