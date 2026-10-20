package com.axnavigator.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSONB는 문자열로 저장하고 HTTP에서는 JSON 객체로 반환한다. */
@Component
public class JsonSupport {
    private final JsonMapper mapper = JsonMapper.builder().build();
    public JsonNode read(String value) { return value == null ? null : mapper.readTree(value); }
    public String write(Object value) { return mapper.writeValueAsString(value); }
    public ObjectNode object() { return mapper.createObjectNode(); }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
}
