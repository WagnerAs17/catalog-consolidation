package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.wagneralmeida.catalog.domain.ImportRecord.JsonType;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ImportRecordMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ImportRecordMapper mapper = new ImportRecordMapper();

    @Test
    void mapsOnlyTheFiveContractFieldsAndPreservesRawValueTypes() throws Exception {
        JsonNode element = objectMapper.readTree("""
                {
                  "Id": 123456789012345678901234567890,
                  "SellerName": true,
                  "Name": 1.25,
                  "Brand": ["value"],
                  "Category": {"value":"data"},
                  "Unknown": null
                }
                """);

        var record = mapper.map(7, element);

        assertThat(record.position()).isEqualTo(7);
        assertThat(record.elementType()).isEqualTo(JsonType.OBJECT);
        assertThat(record.fields())
                .containsEntry("Id", RawValue.integer("123456789012345678901234567890"))
                .containsEntry("SellerName", RawValue.booleanValue(true))
                .containsEntry("Name", RawValue.decimal("1.25"))
                .containsEntry("Brand", RawValue.arrayValue())
                .containsEntry("Category", RawValue.objectValue())
                .doesNotContainKey("Unknown");
    }

    @Test
    void mapsNullStringIntegralDecimalBooleanArrayObjectAndMissingNodeTypes() throws Exception {
        assertThat(mapper.map(1, NullNode.getInstance()).elementType()).isEqualTo(JsonType.NULL);
        assertThat(mapper.map(2, TextNode.valueOf("text")).elementType()).isEqualTo(JsonType.STRING);
        assertThat(mapper.map(3, IntNode.valueOf(1)).elementType()).isEqualTo(JsonType.INTEGER);
        assertThat(mapper.map(4, DecimalNode.valueOf(new BigDecimal("1.0"))).elementType())
                .isEqualTo(JsonType.DECIMAL);
        assertThat(mapper.map(5, BooleanNode.TRUE).elementType()).isEqualTo(JsonType.BOOLEAN);
        assertThat(mapper.map(6, objectMapper.readTree("[]")).elementType()).isEqualTo(JsonType.ARRAY);
        assertThat(mapper.map(7, objectMapper.readTree("{}")).elementType()).isEqualTo(JsonType.OBJECT);
        assertThat(mapper.map(8, MissingNode.getInstance()).elementType()).isEqualTo(JsonType.MISSING);

        JsonNode nullField = objectMapper.readTree("{\"Brand\":null}");
        assertThat(mapper.map(9, nullField).fields()).containsEntry("Brand", RawValue.nullValue());
    }

    @Test
    void rejectsBinaryAndPojoNodesInsteadOfGuessingAJsonType() {
        assertThatIllegalStateException().isThrownBy(() -> mapper.map(1, BinaryNode.valueOf(new byte[] {1})));
        assertThatIllegalStateException().isThrownBy(() -> mapper.map(1, new POJONode(new Object())));
    }
}
