package com.endustry.erp.platform.common;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
	/** H2: "ON PUBLIC.ITEM(TENANT_ID NULLS FIRST, CODE NULLS FIRST)"; PostgreSQL: "Key (tenant_id, code)=". */
	private static final Pattern DUPLICATE_KEY = Pattern.compile("(?:ON PUBLIC\\.\\w+|Key )\\(([^)]*)\\)");
	/** H2: Value not permitted for column "...": "SERVICES" */
	private static final Pattern REFUSED_VALUE = Pattern.compile("\": \"([^\"]+)\"");

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> api(ApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new ApiError(e.getStatus().value(), e.getCode(), e.getMessage(), null, e.getDetails()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
		e.getBindingResult().getGlobalErrors().forEach(g -> fields.putIfAbsent("_", g.getDefaultMessage()));
		return validation(fields);
	}

	/** Raised when an entity fails bean validation at flush time rather than at the controller. */
	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ApiError> invalidEntity(ConstraintViolationException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getConstraintViolations().forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
		return validation(fields);
	}

	private ResponseEntity<ApiError> validation(Map<String, String> fields) {
		return ResponseEntity.badRequest().body(new ApiError(400, "VALIDATION_FAILED",
				"Please correct the highlighted fields", fields, null));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class })
	ResponseEntity<ApiError> malformed(Exception e) {
		return ResponseEntity.badRequest()
				.body(ApiError.of(400, "MALFORMED_REQUEST", "The request could not be read. Check field types and formats."));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
		String raw = String.valueOf(e.getMostSpecificCause().getMessage());
		String cause = raw.toLowerCase();
		if (cause.contains("unique") || cause.contains("duplicate")) {
			return error(HttpStatus.CONFLICT, "DUPLICATE", "A record with the same " + duplicateField(raw) + " already exists");
		}
		if (cause.contains("value not permitted") || cause.contains("check constraint")) {
			log.warn("Value refused by the database", e);
			Matcher value = REFUSED_VALUE.matcher(raw);
			return error(HttpStatus.CONFLICT, "VALUE_NOT_ALLOWED", "The database does not accept " + (value.find() ? "'" + value.group(1) + "'" : "one of the values")
					+ " yet. Restart the server so it can update itself, then try again.");
		}
		if (cause.contains("referential") || cause.contains("foreign key")) {
			return error(HttpStatus.CONFLICT, "REFERENCE_CONFLICT",
					"This record is linked to other records, or refers to one that does not exist");
		}
		log.warn("Data integrity violation", e);
		return error(HttpStatus.CONFLICT, "DATA_CONFLICT", "The change conflicts with existing data");
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ApiError> stale(OptimisticLockingFailureException e) {
		return error(HttpStatus.CONFLICT, "STALE_RECORD", "Someone else changed this record. Reload and try again.");
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ApiError> denied(AccessDeniedException e) {
		return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have access to this action");
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
		return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
		return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> unexpected(Exception e) {
		log.error("Unhandled error", e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side");
	}

	/** The columns of the unique key a duplicate broke, in words ("code", "doc no"), from the H2 or PostgreSQL message. */
	public static String duplicateField(String message) {
		Matcher key = DUPLICATE_KEY.matcher(message);
		if (!key.find()) {
			return "code or number";
		}
		String fields = Arrays.stream(key.group(1).split(","))
				.map(c -> c.trim().split("\\s+")[0].replace("\"", "").toLowerCase())
				.filter(c -> !c.equals("tenant_id") && !c.isEmpty())
				.map(c -> c.replace('_', ' '))
				.collect(Collectors.joining(" and "));
		return fields.isEmpty() ? "code or number" : fields;
	}

	private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message));
	}
}
