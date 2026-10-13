package com.endustry.erp.platform.common;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** A failure the client can act on: carries the HTTP status and a stable machine-readable code. */
@Getter
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final transient Object details;

	public ApiException(HttpStatus status, String code, String message, Object details) {
		super(message);
		this.status = status;
		this.code = code;
		this.details = details;
	}

	public static ApiException notFound(String what, Object id) {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " was not found", null);
	}

	/** A business rule was broken (422). */
	public static ApiException rule(String code, String message) {
		return rule(code, message, null);
	}

	public static ApiException rule(String code, String message, Object details) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
	}

	public static ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message, null);
	}

	public static ApiException badRequest(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message, null);
	}

	public static ApiException forbidden(String code, String message) {
		return new ApiException(HttpStatus.FORBIDDEN, code, message, null);
	}

	public static ApiException unauthorized(String code, String message) {
		return new ApiException(HttpStatus.UNAUTHORIZED, code, message, null);
	}
}
