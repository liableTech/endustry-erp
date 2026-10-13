package com.endustry.erp.platform.signup;

import java.util.List;

import com.endustry.erp.platform.tenancy.Tenant;

/**
 * The subscriptions a business can pick when it signs up by itself. A plan sets the monthly
 * price, how many people can sign in and which modules are switched on. White-label, dedicated
 * hosting and anything custom are not sold here: those go through the product team.
 */
public enum Plan {

	STARTER("Starter", 999, 3, List.of("sales", "purchase", "inventory", "reports")),
	GROWTH("Growth", 2499, 10, Tenant.MODULES),
	BUSINESS("Business", 4999, 25, Tenant.MODULES);

	private final String label;
	private final int monthlyPrice;
	private final int users;
	private final List<String> modules;

	Plan(String label, int monthlyPrice, int users, List<String> modules) {
		this.label = label;
		this.monthlyPrice = monthlyPrice;
		this.users = users;
		this.modules = modules;
	}

	public String label() {
		return label;
	}

	/** Rupees per month, before GST. */
	public int monthlyPrice() {
		return monthlyPrice;
	}

	public int users() {
		return users;
	}

	public List<String> modules() {
		return modules;
	}
}
