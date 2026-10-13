package com.endustry.erp.platform.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The single error shape every endpoint returns. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, Map<String, String> fieldErrors, Object details) {

	public static ApiError of(int status, String code, String message) {
		return new ApiError(status, code, message, null, null);
	}
}
