package com.greennode.api_gateway.utilities;

import java.lang.reflect.Type;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.AbstractJsonFormatMapper;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.json.JsonMapper;

public final class CustomHibernateJsonFormatter extends AbstractJsonFormatMapper {

    private final JsonMapper jsonMapper;

    public CustomHibernateJsonFormatter() {
        this(JsonMapper.builder().build());
    }

    public CustomHibernateJsonFormatter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public <T> void writeToTarget(
            T value, JavaType<T> javaType, Object target, WrapperOptions options) {
        jsonMapper
                .writerFor(jsonMapper.constructType(javaType.getJavaType()))
                .writeValue((JsonGenerator) target, value);
    }

    @Override
    public <T> T readFromSource(JavaType<T> javaType, Object source, WrapperOptions options) {
        return jsonMapper.readValue(
                (JsonParser) source, jsonMapper.constructType(javaType.getJavaType()));
    }

    @Override
    public boolean supportsSourceType(Class<?> sourceType) {
        return JsonParser.class.isAssignableFrom(sourceType);
    }

    @Override
    public boolean supportsTargetType(Class<?> targetType) {
        return JsonGenerator.class.isAssignableFrom(targetType);
    }

    @Override
    public <T> T fromString(CharSequence charSequence, Type type) {
        return jsonMapper.readValue(charSequence.toString(), jsonMapper.constructType(type));
    }

    @Override
    public <T> String toString(T value, Type type) {
        return jsonMapper.writerFor(jsonMapper.constructType(type)).writeValueAsString(value);
    }
}
