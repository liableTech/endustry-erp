package com.endustry.erp.documents;

public enum DocStatus {
	DRAFT, SENT, ACCEPTED, REJECTED, CONFIRMED, APPROVED, PARTLY_FULFILLED, FULFILLED, POSTED, PARTLY_PAID, PAID,
	CANCELLED;

	public boolean isOutstanding() {
		return this == POSTED || this == PARTLY_PAID;
	}
}
