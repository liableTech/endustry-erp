package com.endustry.erp.platform.labels;

import java.util.LinkedHashMap;
import java.util.Map;

import com.endustry.erp.platform.tenancy.Industry;

/**
 * The business words the application uses for its records, and how each industry template says
 * them. A clinic or consultancy has clients, not customers; a shop sells products from a store.
 * A client can rename any of them further under Settings > Labels.
 */
public final class Terms {

	/** One word as it appears in the menu and on screens. */
	public record Term(String singular, String plural) {
	}

	/** What a term is for, shown beside it on the settings screen. */
	public record Definition(String key, String description, Term standard) {
	}

	/** Every renameable term, in the order the settings screen lists them. */
	public static final Map<String, Definition> ALL = new LinkedHashMap<>();

	static {
		define("customer", "Who you sell to", "Customer", "Customers");
		define("vendor", "Who you buy from", "Vendor", "Vendors");
		define("item", "What you buy, make, stock or sell", "Item", "Items");
		define("warehouse", "Where stock is kept", "Warehouse", "Warehouses");
		define("machine", "Where work is done, and what an hour there costs", "Machine", "Machines");
		define("employee", "Operators and staff", "Employee", "Employees");
		define("quotation", "An offer to a customer", "Quotation", "Quotations");
		define("salesOrder", "A confirmed customer order", "Sales order", "Sales orders");
		define("salesInvoice", "The tax invoice for a sale", "Sales invoice", "Sales invoices");
		define("purchaseOrder", "An order placed with a vendor", "Purchase order", "Purchase orders");
		define("goodsReceipt", "Material received into stock", "Goods receipt", "Goods receipts");
		define("purchaseInvoice", "A vendor's bill", "Purchase invoice", "Purchase invoices");
		define("workOrder", "An instruction to make something", "Work order", "Work orders");
		define("bom", "What goes into a product and how it is made", "Bill of material", "Bills of material");
		define("inspection", "A quality check", "Inspection", "Inspections");
	}

	private Terms() {
	}

	private static void define(String key, String description, String singular, String plural) {
		ALL.put(key, new Definition(key, description, new Term(singular, plural)));
	}

	/** The words an industry template starts with: the standard ones with that industry's own swapped in. */
	public static Map<String, Term> defaults(Industry industry) {
		Map<String, Term> terms = new LinkedHashMap<>();
		ALL.values().forEach(d -> terms.put(d.key(), d.standard()));
		if (industry != null) {
			terms.putAll(presets(industry));
		}
		return terms;
	}

	private static Map<String, Term> presets(Industry industry) {
		return switch (industry) {
			case TRADING -> Map.of(
					"vendor", new Term("Supplier", "Suppliers"),
					"item", new Term("Product", "Products"));
			case RETAIL -> Map.of(
					"vendor", new Term("Supplier", "Suppliers"),
					"item", new Term("Product", "Products"),
					"warehouse", new Term("Store", "Stores"),
					"salesInvoice", new Term("Bill", "Bills"));
			case SERVICES -> Map.of(
					"customer", new Term("Client", "Clients"),
					"vendor", new Term("Supplier", "Suppliers"),
					"item", new Term("Service", "Services"),
					"quotation", new Term("Proposal", "Proposals"),
					"machine", new Term("Equipment", "Equipment"));
			case FOOD -> Map.of(
					"item", new Term("Product", "Products"),
					"workOrder", new Term("Production batch", "Production batches"),
					"bom", new Term("Recipe", "Recipes"));
			case TEXTILES -> Map.of(
					"workOrder", new Term("Production order", "Production orders"),
					"bom", new Term("Style BOM", "Style BOMs"));
			case AGRICULTURE -> Map.of(
					"vendor", new Term("Supplier", "Suppliers"),
					"warehouse", new Term("Godown", "Godowns"),
					"workOrder", new Term("Processing batch", "Processing batches"));
			case CHEMICALS -> Map.of(
					"item", new Term("Product", "Products"),
					"workOrder", new Term("Production batch", "Production batches"),
					"bom", new Term("Formulation", "Formulations"));
			case PHARMA -> Map.of(
					"item", new Term("Product", "Products"),
					"workOrder", new Term("Production batch", "Production batches"),
					"bom", new Term("Formulation", "Formulations"));
			case PLASTICS -> Map.of(
					"machine", new Term("Moulding machine", "Moulding machines"));
			default -> Map.of();
		};
	}
}
