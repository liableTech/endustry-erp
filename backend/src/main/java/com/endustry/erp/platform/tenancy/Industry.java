package com.endustry.erp.platform.tenancy;

import java.util.List;

/**
 * Industry templates (BRD 8.21). Choosing one when a client is created pre-loads the modules,
 * units, item categories and rejection reasons that kind of business expects, so the client can
 * start straight away. Everything it loads is ordinary data the client can change afterwards.
 * List entries are code, name pairs.
 */
public enum Industry {

	ENGINEERING(Group.MANUFACTURING, "Engineering, fabrication and machining", allModules(), factoryUnits(),
			List.of("STEEL", "Steel and alloys", "CASTING", "Castings and forgings", "BOUGHT_OUT", "Bought-out parts", "CONSUMABLE", "Consumables",
					"PACKING", "Packing material", "MACHINED", "Machined components", "FABRICATED", "Fabricated assemblies", "SERVICE", "Services"),
			List.of("DIMENSION", "Dimension out of tolerance", "SURFACE", "Surface defect", "MATERIAL", "Material defect", "HARDNESS", "Hardness not to spec",
					"DAMAGE", "Damaged in transit")),

	AUTO_COMPONENTS(Group.MANUFACTURING, "Auto components", allModules(), factoryUnits(),
			List.of("STEEL", "Steel and alloys", "SHEET", "Sheet metal", "BOUGHT_OUT", "Bought-out parts", "CONSUMABLE", "Consumables", "PACKING", "Packing material",
					"PRESSED", "Pressed parts", "MACHINED", "Machined parts", "ASSEMBLY", "Assemblies", "SERVICE", "Services"),
			List.of("DIMENSION", "Dimension out of tolerance", "SURFACE", "Surface defect", "MATERIAL", "Material defect", "BURR", "Burr or sharp edge",
					"FITMENT", "Fitment failure", "DAMAGE", "Damaged in transit")),

	PLASTICS(Group.MANUFACTURING, "Plastics, moulding and packaging", allModules(), factoryUnits(),
			List.of("RESIN", "Polymer granules", "MASTERBATCH", "Masterbatch and additives", "REGRIND", "Regrind", "INSERT", "Inserts and bought-out parts",
					"PACKING", "Packing material", "MOULDED", "Moulded parts", "ASSEMBLY", "Assemblies", "SERVICE", "Services"),
			List.of("SHORT_SHOT", "Short shot", "FLASH", "Flash", "SINK", "Sink mark", "WARPAGE", "Warpage", "COLOUR", "Colour mismatch", "DAMAGE", "Damaged in transit")),

	FOOD(Group.MANUFACTURING, "Food processing and formulations", allModules(), factoryUnits(),
			List.of("INGREDIENT", "Ingredients", "ADDITIVE", "Additives and flavours", "PACKING", "Packing material", "CONSUMABLE", "Consumables",
					"SEMI", "Semi-finished", "FINISHED", "Finished products", "SERVICE", "Services"),
			List.of("CONTAMINATION", "Contamination", "MOISTURE", "Moisture out of range", "TASTE", "Taste or odour", "WEIGHT", "Under or over weight",
					"PACKAGING", "Packaging defect", "EXPIRY", "Near or past expiry")),

	TEXTILES(Group.MANUFACTURING, "Textiles and garments", allModules(), factoryUnits(),
			List.of("YARN", "Yarn", "FABRIC", "Fabric", "DYE", "Dyes and chemicals", "TRIM", "Trims and accessories", "PACKING", "Packing material",
					"GARMENT", "Garments", "SERVICE", "Services"),
			List.of("SHADE", "Shade variation", "WEAVE", "Weaving defect", "STITCH", "Stitching defect", "STAIN", "Stain", "SIZE", "Size out of tolerance",
					"DAMAGE", "Damaged in transit")),

	AGRICULTURE(Group.MANUFACTURING, "Agriculture and agro-processing", allModules(),
			List.of("KG", "Kilograms", "QTL", "Quintals", "TON", "Tonnes", "BAG", "Bags", "LTR", "Litres", "NOS", "Numbers", "ACRE", "Acres"),
			List.of("SEED", "Seeds", "FERTILISER", "Fertilisers", "CROP_CARE", "Crop protection", "FEED", "Animal feed", "PRODUCE", "Farm produce",
					"PROCESSED", "Processed produce", "PACKING", "Packing material", "SERVICE", "Services"),
			List.of("MOISTURE", "Moisture out of range", "FOREIGN_MATTER", "Foreign matter", "PEST", "Pest or fungus damage", "GRADE", "Below grade",
					"WEIGHT", "Under or over weight", "EXPIRY", "Near or past expiry")),

	CHEMICALS(Group.MANUFACTURING, "Chemicals, paints and coatings", allModules(),
			List.of("KG", "Kilograms", "LTR", "Litres", "TON", "Tonnes", "DRUM", "Drums", "BAG", "Bags", "NOS", "Numbers"),
			List.of("RAW_CHEMICAL", "Raw chemicals", "SOLVENT", "Solvents", "ADDITIVE", "Catalysts and additives", "INTERMEDIATE", "Intermediates",
					"FINISHED", "Finished products", "PACKING", "Packing material", "CONSUMABLE", "Consumables", "SERVICE", "Services"),
			List.of("PURITY", "Purity below spec", "PH", "pH out of range", "MOISTURE", "Moisture out of range", "CONTAMINATION", "Contamination",
					"COLOUR", "Colour mismatch", "LEAK", "Leaking or damaged container")),

	PHARMA(Group.MANUFACTURING, "Pharmaceuticals and healthcare products", allModules(),
			List.of("NOS", "Numbers", "KG", "Kilograms", "GM", "Grams", "LTR", "Litres", "STRIP", "Strips", "BTL", "Bottles", "BOX", "Boxes"),
			List.of("API", "Active ingredients (API)", "EXCIPIENT", "Excipients", "PACKING", "Packing material", "CONSUMABLE", "Consumables",
					"SEMI", "Semi-finished", "FINISHED", "Finished products", "SERVICE", "Services"),
			List.of("ASSAY", "Assay out of range", "CONTAMINATION", "Contamination", "DISSOLUTION", "Dissolution failure", "LABEL", "Labelling error",
					"PACKAGING", "Packaging defect", "EXPIRY", "Near or past expiry")),

	ELECTRONICS(Group.MANUFACTURING, "Electrical and electronics", allModules(), factoryUnits(),
			List.of("COMPONENT", "Electronic components", "PCB", "Printed circuit boards", "WIRE", "Wires and cables", "ENCLOSURE", "Enclosures and hardware",
					"PACKING", "Packing material", "ASSEMBLY", "Assemblies", "FINISHED", "Finished products", "SERVICE", "Services"),
			List.of("SOLDER", "Soldering defect", "FUNCTIONAL", "Failed functional test", "COMPONENT", "Wrong or missing component", "COSMETIC", "Cosmetic defect",
					"DAMAGE", "Damaged in transit")),

	GENERAL(Group.MANUFACTURING, "General manufacturing", allModules(), factoryUnits(),
			List.of("RAW", "Raw materials", "BOUGHT_OUT", "Bought-out parts", "CONSUMABLE", "Consumables", "PACKING", "Packing material", "FINISHED", "Finished goods",
					"SERVICE", "Services"),
			List.of("DIMENSION", "Dimension out of tolerance", "SURFACE", "Surface defect", "MATERIAL", "Material defect", "DAMAGE", "Damaged in transit")),

	TRADING(Group.TRADE_AND_SERVICES, "Trading and distribution", List.of("sales", "purchase", "inventory", "reports"),
			List.of("NOS", "Numbers", "PCS", "Pieces", "BOX", "Boxes", "CTN", "Cartons", "KG", "Kilograms", "LTR", "Litres", "MTR", "Metres", "SET", "Sets"),
			List.of("GOODS", "Trading goods", "SPARES", "Spares and accessories", "PACKING", "Packing material", "CONSUMABLE", "Consumables",
					"SERVICE", "Services"),
			List.of("DAMAGE", "Damaged in transit", "SHORT", "Short supply", "WRONG_ITEM", "Wrong item supplied", "EXPIRY", "Near or past expiry",
					"QUALITY", "Not as per sample")),

	RETAIL(Group.TRADE_AND_SERVICES, "Retail shop or store", List.of("sales", "purchase", "inventory", "reports"),
			List.of("PCS", "Pieces", "NOS", "Numbers", "PKT", "Packets", "BOX", "Boxes", "DOZ", "Dozens", "KG", "Kilograms", "GM", "Grams", "LTR", "Litres"),
			List.of("GROCERY", "Grocery", "PERSONAL_CARE", "Personal care", "HOUSEHOLD", "Household", "ELECTRONICS", "Electronics", "APPAREL", "Apparel",
					"STATIONERY", "Stationery", "OTHER", "Other goods"),
			List.of("DAMAGE", "Damaged", "EXPIRY", "Near or past expiry", "SHORT", "Short supply", "WRONG_ITEM", "Wrong item supplied",
					"CUSTOMER_RETURN", "Returned by customer")),

	SERVICES(Group.TRADE_AND_SERVICES, "Services and consulting", List.of("sales", "purchase", "reports"),
			List.of("HRS", "Hours", "DAY", "Days", "MON", "Months", "NOS", "Numbers", "JOB", "Jobs", "VISIT", "Visits"),
			List.of("CONSULTING", "Consulting", "INSTALLATION", "Installation and commissioning", "AMC", "Maintenance contracts (AMC)",
					"REPAIR", "Repair and servicing", "TRAINING", "Training", "SPARES", "Spares used in service", "OTHER", "Other services"),
			List.of("INCOMPLETE", "Work incomplete", "REWORK", "Rework needed", "DELAY", "Delivered late", "NOT_AS_AGREED", "Not as agreed")),

	OTHER(Group.TRADE_AND_SERVICES, "Any other business", List.of("sales", "purchase", "inventory", "reports"),
			List.of("NOS", "Numbers", "PCS", "Pieces", "KG", "Kilograms", "LTR", "Litres", "MTR", "Metres", "BOX", "Boxes", "HRS", "Hours"),
			List.of("GOODS", "Goods", "CONSUMABLE", "Consumables", "SERVICE", "Services", "OTHER", "Other"),
			List.of("DAMAGE", "Damaged", "SHORT", "Short supply", "WRONG_ITEM", "Wrong item supplied", "QUALITY", "Quality not acceptable"));

	/** How the industry list is grouped on the new client form. */
	public enum Group {
		MANUFACTURING, TRADE_AND_SERVICES
	}

	private final Group group;
	private final String label;
	private final List<String> modules;
	private final List<String> units;
	private final List<String> itemCategories;
	private final List<String> rejectionReasons;

	Industry(Group group, String label, List<String> modules, List<String> units, List<String> itemCategories, List<String> rejectionReasons) {
		this.group = group;
		this.label = label;
		this.modules = modules;
		this.units = units;
		this.itemCategories = itemCategories;
		this.rejectionReasons = rejectionReasons;
	}

	// Methods rather than constants: an enum's constants are created before its static fields.
	private static List<String> allModules() {
		return Tenant.MODULES;
	}

	private static List<String> factoryUnits() {
		return List.of("NOS", "Numbers", "KG", "Kilograms", "MTR", "Metres", "LTR", "Litres", "SET", "Sets", "BOX", "Boxes");
	}

	public Group group() {
		return group;
	}

	public String label() {
		return label;
	}

	/** The modules switched on for a new client of this industry when the admin does not pick them. */
	public List<String> modules() {
		return modules;
	}

	public List<String> units() {
		return units;
	}

	public List<String> itemCategories() {
		return itemCategories;
	}

	public List<String> rejectionReasons() {
		return rejectionReasons;
	}
}
