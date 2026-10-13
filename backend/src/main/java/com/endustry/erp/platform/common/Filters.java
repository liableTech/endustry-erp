package com.endustry.erp.platform.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/**
 * Turns one list filter from the address into a query condition. The screens send every table
 * filter the same way: {@code field=a,b} for any of several values, {@code field.like=text} for
 * text containing it, and {@code field.from} / {@code field.to} for a range of numbers or dates.
 */
public final class Filters {

	private Filters() {
	}

	/** {@code operator} is "in", "like", "from" or "to". */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Predicate predicate(CriteriaBuilder cb, Path<?> path, String operator, String value) {
		Class<?> type = path.getJavaType();
		return switch (operator) {
			case "like" -> cb.like(cb.lower(path.as(String.class)), "%" + value.trim().toLowerCase() + "%");
			case "from" -> cb.greaterThanOrEqualTo((Path<Comparable>) path, (Comparable) convert(value.trim(), type));
			case "to" -> cb.lessThanOrEqualTo((Path<Comparable>) path, (Comparable) convert(value.trim(), type));
			default -> path.in(Arrays.stream(value.split(",")).map(v -> convert(v.trim(), type)).toList());
		};
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object convert(String value, Class<?> type) {
		try {
			if (type.isEnum()) {
				return Enum.valueOf((Class<Enum>) type, value.toUpperCase());
			}
			if (type == Boolean.class || type == boolean.class) {
				return Boolean.valueOf(value);
			}
			if (type == Long.class || type == long.class) {
				return Long.valueOf(value);
			}
			if (type == Integer.class || type == int.class) {
				return Integer.valueOf(value);
			}
			if (type == BigDecimal.class) {
				return new BigDecimal(value);
			}
			if (type == Double.class || type == double.class) {
				return Double.valueOf(value);
			}
			if (type == LocalDate.class) {
				return LocalDate.parse(value);
			}
			return value;
		} catch (RuntimeException e) {
			throw ApiException.badRequest("INVALID_FILTER", "'" + value + "' is not a valid filter value");
		}
	}
}
