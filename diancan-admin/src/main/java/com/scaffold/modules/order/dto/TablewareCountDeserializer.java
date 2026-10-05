package com.scaffold.modules.order.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Refuse fractional guest counts rather than silently truncating them. */
public class TablewareCountDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) return parser.getIntValue();
        if (parser.currentToken() == JsonToken.VALUE_STRING && parser.getText().matches("[0-9]{1,2}")) return Integer.valueOf(parser.getText());
        return context.reportInputMismatch(Integer.class, "人数和餐具套数必须为整数");
    }
}
