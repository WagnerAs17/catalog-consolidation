package com.wagneralmeida.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.wagneralmeida.catalog.domain.ImportRecord.Invalid;
import com.wagneralmeida.catalog.domain.ImportRecord.JsonType;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import com.wagneralmeida.catalog.domain.ImportRecord.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportRecordTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void nonObjectProducesNotAnObjectBeforeFieldValidation() {
        ImportRecord record = ImportRecord.nonObject(1, JsonType.ARRAY);

        assertInvalid(record, "NOT_AN_OBJECT", null, null);
    }

    @Test
    void missingRequiredFieldsFollowBr01Precedence() {
        assertInvalid(ImportRecord.object(1, Map.of()), "MISSING_ID", null, null);

        Map<String, RawValue> withoutSeller = fields();
        withoutSeller.remove("SellerName");
        assertInvalid(ImportRecord.object(2, withoutSeller),
                "MISSING_SELLER_NAME", "100", null);

        Map<String, RawValue> withoutName = fields();
        withoutName.remove("Name");
        assertInvalid(ImportRecord.object(3, withoutName),
                "MISSING_NAME", "100", "Acme");
    }

    @Test
    void blankAndNullRequiredFieldsAreMissing() {
        Map<String, RawValue> blankId = fields();
        blankId.put("Id", RawValue.string("  "));
        assertInvalid(ImportRecord.object(1, blankId), "MISSING_ID", null, null);

        Map<String, RawValue> nullSeller = fields();
        nullSeller.put("SellerName", RawValue.nullValue());
        assertInvalid(ImportRecord.object(2, nullSeller),
                "MISSING_SELLER_NAME", "100", null);

        Map<String, RawValue> blankName = fields();
        blankName.put("Name", RawValue.string(" \t "));
        assertInvalid(ImportRecord.object(3, blankName),
                "MISSING_NAME", "100", "Acme");
    }

    @Test
    void idAcceptsStringOrIntegerAndPreservesExactDecimalText() {
        Valid stringId = assertValid(ImportRecord.object(1, fields()));
        assertThat(stringId.sellerProductId()).isEqualTo("100");

        Map<String, RawValue> integerFields = fields();
        integerFields.put("Id", RawValue.integer("-123456789012345678901234567890"));
        Valid integerId = assertValid(ImportRecord.object(2, integerFields));
        assertThat(integerId.sellerProductId())
                .isEqualTo("-123456789012345678901234567890");
    }

    @Test
    void idRejectsBooleanDecimalArrayAndObjectTypes() {
        for (RawValue value : List.of(
                RawValue.booleanValue(true),
                RawValue.decimal("1.0"),
                RawValue.arrayValue(),
                RawValue.objectValue())) {
            Map<String, RawValue> rawFields = fields();
            rawFields.put("Id", value);
            assertInvalid(ImportRecord.object(1, rawFields),
                    "WRONG_FIELD_TYPE", null, null);
        }
    }

    @Test
    void wrongFieldTypesFollowIdSellerNameNameBrandCategoryOrder() {
        Map<String, RawValue> sellerWrongAndNameMissing = fields();
        sellerWrongAndNameMissing.put("SellerName", RawValue.booleanValue(false));
        sellerWrongAndNameMissing.remove("Name");
        assertInvalid(ImportRecord.object(1, sellerWrongAndNameMissing),
                "WRONG_FIELD_TYPE", "100", null);

        Map<String, RawValue> nameWrongAndBrandWrong = fields();
        nameWrongAndBrandWrong.put("Name", RawValue.integer("42"));
        nameWrongAndBrandWrong.put("Brand", RawValue.booleanValue(true));
        assertInvalid(ImportRecord.object(2, nameWrongAndBrandWrong),
                "WRONG_FIELD_TYPE", "100", "Acme");

        Map<String, RawValue> brandWrongAndCategoryWrong = fields();
        brandWrongAndCategoryWrong.put("Brand", RawValue.arrayValue());
        brandWrongAndCategoryWrong.put("Category", RawValue.objectValue());
        assertInvalid(ImportRecord.object(3, brandWrongAndCategoryWrong),
                "WRONG_FIELD_TYPE", "100", "Acme");

        Map<String, RawValue> categoryWrong = fields();
        categoryWrong.put("Category", RawValue.decimal("1.5"));
        assertInvalid(ImportRecord.object(4, categoryWrong),
                "WRONG_FIELD_TYPE", "100", "Acme");
    }

    @Test
    void optionalNullMissingAndBlankValuesBecomeAbsent() {
        Map<String, RawValue> rawFields = fields();
        rawFields.put("Brand", RawValue.nullValue());
        rawFields.put("Category", RawValue.string("   "));

        Valid valid = assertValid(ImportRecord.object(1, rawFields));

        assertThat(valid.brand()).isNull();
        assertThat(valid.normalizedBrand()).isNull();
        assertThat(valid.category()).isNull();
    }

    @Test
    void nameWithEmptyNormalizedFormIsRejectedAfterFieldChecks() {
        Map<String, RawValue> rawFields = fields();
        rawFields.put("Name", RawValue.string("!!!"));

        assertInvalid(ImportRecord.object(1, rawFields),
                "NAME_NOT_NORMALIZABLE", "100", "Acme");
    }

    @Test
    void unknownJsonFieldsAreIgnored() {
        Map<String, RawValue> rawFields = fields();
        rawFields.put("Unknown", RawValue.objectValue());

        Valid valid = assertValid(ImportRecord.object(1, rawFields));

        assertThat(valid.name()).isEqualTo("Camera");
    }

    @Test
    void c13_invalidRecordContinues() {
        ImportRecord invalid = ImportRecord.object(1, Map.of());
        ImportRecord valid = ImportRecord.object(2, fields());

        List<ImportRecord.Validation> validations = List.of(invalid, valid).stream()
                .map(record -> record.validate(normalizer))
                .toList();

        assertThat(validations).hasSize(2);
        assertThat(validations.get(0)).isInstanceOf(Invalid.class);
        assertThat(validations.get(1)).isInstanceOf(Valid.class);
    }

    private Map<String, RawValue> fields() {
        Map<String, RawValue> fields = new LinkedHashMap<>();
        fields.put("Id", RawValue.string(" 100 "));
        fields.put("SellerName", RawValue.string(" Acme "));
        fields.put("Name", RawValue.string(" Camera "));
        return fields;
    }

    private Valid assertValid(ImportRecord record) {
        assertThat(record.validate(normalizer)).isInstanceOf(Valid.class);
        return (Valid) record.validate(normalizer);
    }

    private void assertInvalid(
            ImportRecord record, String detail, String sellerProductId, String sellerName) {
        assertThat(record.validate(normalizer)).isInstanceOf(Invalid.class);
        Invalid invalid = (Invalid) record.validate(normalizer);
        assertThat(invalid.result().outcome()).isEqualTo(Outcome.NOT_PROCESSED);
        assertThat(invalid.result().reason()).isEqualTo(RejectionReason.INVALID_RECORD);
        assertThat(invalid.result().detail()).isEqualTo(detail);
        assertThat(invalid.result().sellerProductId()).isEqualTo(sellerProductId);
        assertThat(invalid.result().sellerName()).isEqualTo(sellerName);
        assertThat(invalid.result().productId()).isNull();
        assertThat(invalid.result().warnings()).isEmpty();
    }
}
