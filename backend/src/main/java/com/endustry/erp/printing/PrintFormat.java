package com.endustry.erp.printing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** How values look on paper: DD-MM-YYYY, lakh/crore grouping and the amount in words. */
final class PrintFormat {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
	private static final String[] ONES = { "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
			"Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen" };
	private static final String[] TENS = { "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety" };

	private PrintFormat() {
	}

	static String date(LocalDate date) {
		return date == null ? "" : date.format(DATE);
	}

	/** 1234567.5 becomes "12,34,567.50". The template adds the rupee sign where it wants one. */
	static String money(BigDecimal amount) {
		return group(nz(amount).setScale(2, RoundingMode.HALF_UP));
	}

	/** Up to three decimals, trailing zeros dropped: 12.500 becomes "12.5". */
	static String qty(BigDecimal value) {
		BigDecimal v = nz(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
		return group(v.scale() < 0 ? v.setScale(0) : v);
	}

	/** Percentages such as 18 or 2.5. */
	static String pct(BigDecimal value) {
		BigDecimal v = nz(value).stripTrailingZeros();
		return (v.scale() < 0 ? v.setScale(0) : v).toPlainString();
	}

	private static String group(BigDecimal value) {
		String digits = value.abs().toPlainString();
		int point = digits.indexOf('.');
		String whole = point < 0 ? digits : digits.substring(0, point);
		String fraction = point < 0 ? "" : digits.substring(point);
		StringBuilder grouped = new StringBuilder();
		int head = whole.length() - 3;
		for (int i = 0; i < head; i++) {
			grouped.append(whole.charAt(i));
			if ((head - i) % 2 == 1) {
				grouped.append(',');
			}
		}
		grouped.append(whole.substring(Math.max(head, 0)));
		return (value.signum() < 0 ? "-" : "") + grouped + fraction;
	}

	/** "Rupees One Lakh Twenty Thousand Five Hundred and Fifty Paise Only". */
	static String rupeesInWords(BigDecimal amount) {
		BigDecimal rounded = nz(amount).abs().setScale(2, RoundingMode.HALF_UP);
		long rupees = rounded.longValue();
		int paise = rounded.remainder(BigDecimal.ONE).movePointRight(2).intValue();
		String words = "Rupees " + (rupees == 0 ? "Zero" : words(rupees));
		if (paise > 0) {
			words += " and " + words(paise) + " Paise";
		}
		return words + " Only";
	}

	private static String words(long n) {
		StringBuilder out = new StringBuilder();
		long[] units = { 10_000_000L, 100_000L, 1_000L, 100L };
		String[] names = { "Crore", "Lakh", "Thousand", "Hundred" };
		for (int i = 0; i < units.length; i++) {
			if (n >= units[i]) {
				long count = n / units[i];
				// Above 99 crore the crore count itself is spelt out in the same system.
				out.append(count >= 100 ? words(count) : below100((int) count)).append(' ').append(names[i]).append(' ');
				n %= units[i];
			}
		}
		if (n > 0) {
			out.append(below100((int) n));
		}
		return out.toString().trim();
	}

	private static String below100(int n) {
		return n < 20 ? ONES[n] : (TENS[n / 10] + (n % 10 > 0 ? " " + ONES[n % 10] : ""));
	}

	static String humanize(String code) {
		if (code == null || code.isEmpty()) {
			return "";
		}
		String spaced = code.replace('_', ' ').toLowerCase();
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}

	static BigDecimal nz(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	static String text(String value) {
		return value == null ? "" : value;
	}
}
