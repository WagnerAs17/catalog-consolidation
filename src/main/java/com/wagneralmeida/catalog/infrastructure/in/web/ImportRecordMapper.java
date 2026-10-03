package com.wagneralmeida.catalog.infrastructure.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportRecord.JsonType;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class ImportRecordMapper {

    private static final List<String> FIELD_NAMES = List.of("Id", "SellerName", "Name", "Brand", "Category");

    public ImportRecord map(int position, JsonNode element) {
        JsonType elementType = jsonType(element);
        if (!element.isObject()) {
            return ImportRecord.nonObject(position, elementType);
        }

        Map<String, RawValue> fields = new LinkedHashMap<>();
        for (String fieldName : FIELD_NAMES) {
            if (element.has(fieldName)) {
                fields.put(fieldName, rawValue(element.get(fieldName)));
            }
        }
        return ImportRecord.object(position, fields);
    }

    private RawValue rawValue(JsonNode value) {
        return switch (value.getNodeType()) {
            case NULL -> RawValue.nullValue();
            case STRING -> RawValue.string(value.textValue());
            case NUMBER -> value.isIntegralNumber()
                    ? RawValue.integer(value.asText())
                    : RawValue.decimal(value.asText());
            case BOOLEAN -> RawValue.booleanValue(value.booleanValue());
            case ARRAY -> RawValue.arrayValue();
            case OBJECT -> RawValue.objectValue();
            case MISSING -> RawValue.missing();
            case BINARY, POJO -> throw new IllegalStateException("Unsupported JSON node type: " + value.getNodeType());
        };
    }

    private JsonType jsonType(JsonNode value) {
        return switch (value.getNodeType()) {
            case ARRAY -> JsonType.ARRAY;
            case BOOLEAN -> JsonType.BOOLEAN;
            case MISSING -> JsonType.MISSING;
            case NULL -> JsonType.NULL;
            case NUMBER -> value.isIntegralNumber() ? JsonType.INTEGER : JsonType.DECIMAL;
            case OBJECT -> JsonType.OBJECT;
            case STRING -> JsonType.STRING;
            case BINARY, POJO -> throw new IllegalStateException("Unsupported JSON node type: " + value.getNodeType());
        };
    }
}
