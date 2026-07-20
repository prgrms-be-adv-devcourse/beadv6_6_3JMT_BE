package com.prompthub.admin.product.infrastructure.persistence.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Converter
public class TagsConverter implements AttributeConverter<List<String>, String> {

	@Override
	public String convertToDatabaseColumn(List<String> attribute) {
		if (attribute == null || attribute.isEmpty()) {
			return null;
		}
		return String.join(",", attribute);
	}

	@Override
	public List<String> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(Arrays.asList(dbData.split(",")));
	}
}
