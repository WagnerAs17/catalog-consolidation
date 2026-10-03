package com.wagneralmeida.catalog.infrastructure.in.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.domain.ImportRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@Component
public final class ImportFileParser {

    private final ObjectMapper objectMapper;
    private final ImportRecordMapper recordMapper;
    private final DataSize maxBodySize;
    private final int maxRecords;

    public ImportFileParser(
            ObjectMapper objectMapper,
            ImportRecordMapper recordMapper,
            @Value("${catalog.import.max-body-size:5MB}") DataSize maxBodySize,
            @Value("${catalog.import.max-records:5000}") int maxRecords) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper");
        this.maxBodySize = Objects.requireNonNull(maxBodySize, "maxBodySize");
        this.maxRecords = maxRecords;
    }

    public List<ImportRecord> parse(MultipartFile file) {
        if (file.getSize() > maxBodySize.toBytes()) {
            throw new FileRejectionException(FileRejection.FILE_TOO_LARGE);
        }

        JsonNode root;
        try {
            String json = decodeUtf8(file.getBytes());
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
        } catch (IOException exception) {
            throw new FileRejectionException(FileRejection.FILE_UNREADABLE);
        }

        if (root == null || root.isMissingNode()) {
            throw new FileRejectionException(FileRejection.FILE_UNREADABLE);
        }
        if (!root.isArray()) {
            throw new FileRejectionException(FileRejection.NOT_A_JSON_ARRAY);
        }
        if (root.size() > maxRecords) {
            throw new FileRejectionException(FileRejection.TOO_MANY_RECORDS);
        }

        List<ImportRecord> records = new ArrayList<>(root.size());
        int position = 1;
        for (JsonNode element : root) {
            records.add(recordMapper.map(position++, element));
        }
        return records;
    }

    private String decodeUtf8(byte[] bytes) throws IOException {
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
    }
}
