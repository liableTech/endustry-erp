package com.endustry.erp.platform.validation;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class GstinValidator implements ConstraintValidator<Gstin, String> {

	private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final Pattern FORMAT = Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.isBlank() || isValid(value);
	}

	public static boolean isValid(String gstin) {
		return gstin != null && FORMAT.matcher(gstin).matches() && checkDigit(gstin.substring(0, 14)) == gstin.charAt(14);
	}

	/** Completes a 14-character GSTIN body with its check digit. */
	public static String withCheckDigit(String first14) {
		return first14 + checkDigit(first14);
	}

	/** Mod-36 check digit defined by GSTN: alternate weights 1 and 2, summing quotient and remainder. */
	static char checkDigit(String first14) {
		int sum = 0;
		for (int i = 0; i < first14.length(); i++) {
			int product = ALPHABET.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
			sum += product / 36 + product % 36;
		}
		return ALPHABET.charAt((36 - sum % 36) % 36);
	}
}
