package com.endustry.erp.platform.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amounts as people in India read them: rupee symbol with lakh and crore grouping. */
public final class Amounts {

	private static final char RUPEE = '₹';

	private Amounts() {
	}

	/** 1234567.5 becomes "₹12,34,567.50"; whole amounts drop the paise. */
	public static String inr(BigDecimal amount) {
		BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
		String digits = rounded.abs().toPlainString();
		int point = digits.indexOf('.');
		String whole = digits.substring(0, point);
		String paise = digits.substring(point + 1);
		// Indian grouping: the last three digits, then pairs.
		StringBuilder grouped = new StringBuilder();
		int head = whole.length() - 3;
		for (int i = 0; i < head; i++) {
			grouped.append(whole.charAt(i));
			if ((head - i) % 2 == 1) {
				grouped.append(',');
			}
		}
		grouped.append(whole.substring(Math.max(head, 0)));
		return (rounded.signum() < 0 ? "-" : "") + RUPEE + grouped + (paise.equals("00") ? "" : "." + paise);
	}
}
