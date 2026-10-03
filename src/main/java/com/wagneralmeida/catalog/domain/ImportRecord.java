package com.wagneralmeida.catalog.domain;

import java.util.Map;
import java.util.Objects;

public record ImportRecord(int position, JsonType elementType, Map<String, RawValue> fields) {

    public ImportRecord {
        fields = Map.copyOf(fields);
    }

    public static ImportRecord object(int position, Map<String, RawValue> fields) {
        return new ImportRecord(position, JsonType.OBJECT, fields);
    }

    public static ImportRecord nonObject(int position, JsonType type) {
        if (type == JsonType.OBJECT) {
            throw new IllegalArgumentException("Use object for an object element");
        }
        return new ImportRecord(position, type, Map.of());
    }

    public Validation validate(TextNormalizer normalizer) {
        Objects.requireNonNull(normalizer, "normalizer");

        if (elementType != JsonType.OBJECT) {
            return invalid(null, null, "NOT_AN_OBJECT");
        }

        RawValue idValue = field("Id");
        if (idValue.type() == JsonType.MISSING || idValue.type() == JsonType.NULL
                || idValue.type() == JsonType.STRING && idValue.text().trim().isEmpty()) {
            return invalid(null, null, "MISSING_ID");
        }
        if (idValue.type() != JsonType.STRING && idValue.type() != JsonType.INTEGER) {
            return invalid(null, null, "WRONG_FIELD_TYPE");
        }
        String sellerProductId = idValue.type() == JsonType.STRING ? idValue.text().trim() : idValue.text();

        RawValue sellerNameValue = field("SellerName");
        if (sellerNameValue.type() == JsonType.MISSING || sellerNameValue.type() == JsonType.NULL
                || sellerNameValue.type() == JsonType.STRING && sellerNameValue.text().trim().isEmpty()) {
            return invalid(sellerProductId, null, "MISSING_SELLER_NAME");
        }
        if (sellerNameValue.type() != JsonType.STRING) {
            return invalid(sellerProductId, null, "WRONG_FIELD_TYPE");
        }
        String sellerName = sellerNameValue.text().trim();

        RawValue nameValue = field("Name");
        if (nameValue.type() == JsonType.MISSING || nameValue.type() == JsonType.NULL
                || nameValue.type() == JsonType.STRING && nameValue.text().trim().isEmpty()) {
            return invalid(sellerProductId, sellerName, "MISSING_NAME");
        }
        if (nameValue.type() != JsonType.STRING) {
            return invalid(sellerProductId, sellerName, "WRONG_FIELD_TYPE");
        }
        String name = nameValue.text().trim();

        OptionalText brand = optionalText("Brand", sellerProductId, sellerName);
        if (brand.invalid() != null) {
            return brand.invalid();
        }

        OptionalText category = optionalText("Category", sellerProductId, sellerName);
        if (category.invalid() != null) {
            return category.invalid();
        }

        String normalizedName = normalizer.normalize(name);
        if (normalizedName.isEmpty()) {
            return invalid(sellerProductId, sellerName, "NAME_NOT_NORMALIZABLE");
        }

        return new Valid(
                position,
                sellerProductId,
                sellerName,
                name,
                brand.value(),
                category.value(),
                normalizedName,
                normalizer.normalizeOptional(brand.value()));
    }

    private RawValue field(String name) {
        return fields.getOrDefault(name, RawValue.missing());
    }

    private OptionalText optionalText(String fieldName, String sellerProductId, String sellerName) {
        RawValue value = field(fieldName);
        if (value.type() == JsonType.MISSING || value.type() == JsonType.NULL) {
            return new OptionalText(null, null);
        }
        if (value.type() != JsonType.STRING) {
            return new OptionalText(null, invalid(sellerProductId, sellerName, "WRONG_FIELD_TYPE"));
        }

        String trimmed = value.text().trim();
        return new OptionalText(trimmed.isEmpty() ? null : trimmed, null);
    }

    private Invalid invalid(String sellerProductId, String sellerName, String detail) {
        return new Invalid(ImportReport.Result.notProcessed(
                position,
                sellerName,
                sellerProductId,
                RejectionReason.INVALID_RECORD,
                detail));
    }

    public enum JsonType {
        MISSING,
        NULL,
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        ARRAY,
        OBJECT
    }

    public record RawValue(JsonType type, String text) {

        public static RawValue missing() {
            return new RawValue(JsonType.MISSING, null);
        }

        public static RawValue nullValue() {
            return new RawValue(JsonType.NULL, null);
        }

        public static RawValue string(String value) {
            return new RawValue(JsonType.STRING, value);
        }

        public static RawValue integer(String exactDecimalText) {
            return new RawValue(JsonType.INTEGER, exactDecimalText);
        }

        public static RawValue decimal(String exactDecimalText) {
            return new RawValue(JsonType.DECIMAL, exactDecimalText);
        }

        public static RawValue booleanValue(boolean value) {
            return new RawValue(JsonType.BOOLEAN, Boolean.toString(value));
        }

        public static RawValue arrayValue() {
            return new RawValue(JsonType.ARRAY, null);
        }

        public static RawValue objectValue() {
            return new RawValue(JsonType.OBJECT, null);
        }
    }

    public sealed interface Validation permits Valid, Invalid {
    }

    public record Valid(
            int position,
            String sellerProductId,
            String sellerName,
            String name,
            String brand,
            String category,
            String normalizedName,
            String normalizedBrand) implements Validation {
    }

    public record Invalid(ImportReport.Result result) implements Validation {
    }

    private record OptionalText(String value, Invalid invalid) {
    }
}
