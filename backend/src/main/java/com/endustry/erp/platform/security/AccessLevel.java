package com.endustry.erp.platform.security;

public enum AccessLevel {
	NONE, VIEW, FULL;

	public boolean allows(AccessLevel required) {
		return ordinal() >= required.ordinal();
	}
}
