package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** Print and download of saved documents, and client-uploaded print templates. */
class PrintingTest extends ApiTest {

	private static final String INVOICES = "/api/sales/docs/sales-invoice";
	private static final String ORDERS = "/api/purchase/docs/purchase-order";
	private static final String TEMPLATES = "/api/settings/print-templates";

	@Test
	void anyDocumentPrintsWithTheBuiltInLayoutAndItsData() throws Exception {
		JsonNode invoice = invoice();
		long id = invoice.path("id").asLong();

		String text = pdfText(200, Role.SALES, INVOICES + "/" + id + "/print");
		assertThat(text).contains("TAX INVOICE", invoice.path("docNo").asString(), "₹", "Rupees Ten Thousand Six Hundred Twenty Only");

		JsonNode data = get(Role.SALES, INVOICES + "/" + id + "/print-data");
		assertThat(data.path("documentType").asString()).isEqualTo("SALES_INVOICE");
		assertThat(data.path("document").path("number").asString()).isEqualTo(invoice.path("docNo").asString());
		assertThat(data.path("lines").get(0).path("taxableAmount").asString()).isEqualTo("9,000.00");
		assertThat(data.path("totals").path("grandTotal").asString()).isEqualTo("10,620.00");
		assertThat(data.path("totals").path("interState").asBoolean()).isFalse();
		assertThat(data.path("taxSummary").get(0).path("cgstAmount").asString()).isEqualTo("810.00");

		// Download sends the same PDF as an attachment with a readable file name.
		MvcResult download = raw(Role.SALES, INVOICES + "/" + id + "/print?download=true");
		assertThat(download.getResponse().getHeader("Content-Disposition")).startsWith("attachment").contains("Tax-Invoice-");

		// Printing is reading: whoever can view the module can print, nobody else.
		assertCode(call(403, Role.PURCHASE, HttpMethod.GET, INVOICES + "/" + id + "/print-data", null), "FORBIDDEN");
		Files.createDirectories(Path.of("target/print-samples"));
		Files.writeString(Path.of("target/print-samples/sales-invoice.json"), data.toPrettyString());
	}

	@Test
	void anUploadedTemplateCanBeTheDefaultOrPickedPerPrint() throws Exception {
		long id = invoice().path("id").asLong();
		String content = "<html><body><h1>Our layout {{document.number}}</h1>{{#lines}}<p>{{itemCode}} x {{qty}}</p>{{/lines}}"
				+ "<p>{{totals.amountInWords}}</p></body></html>";

		JsonNode mine = post(201, Role.OWNER, TEMPLATES, obj("name", "Our invoice", "docType", "SALES_INVOICE", "content", content));
		long templateId = mine.path("id").asLong();
		assertThat(mine.path("defaultForType").asBoolean()).isFalse();
		assertThat(get(Role.SALES, INVOICES + "/print-templates").get(0).path("name").asString()).isEqualTo("Our invoice");

		// Not the default yet: the built-in layout prints unless the template is picked.
		assertThat(pdfText(200, Role.SALES, INVOICES + "/" + id + "/print")).doesNotContain("Our layout");
		assertThat(pdfText(200, Role.SALES, INVOICES + "/" + id + "/print?templateId=" + templateId)).contains("Our layout");

		put(200, Role.OWNER, TEMPLATES + "/default/SALES_INVOICE", obj("templateId", templateId));
		assertThat(pdfText(200, Role.SALES, INVOICES + "/" + id + "/print")).contains("Our layout", "Rupees");
		JsonNode types = get(Role.OWNER, TEMPLATES + "/types");
		assertThat(types.toString()).contains("\"usesTemplate\":\"Our invoice\"");
		assertThat(pdfText(200, Role.SALES, INVOICES + "/" + id + "/print?templateId=0")).doesNotContain("Our layout");

		// Back to the built-in layout.
		put(200, Role.OWNER, TEMPLATES + "/default/SALES_INVOICE", obj("templateId", null));
		assertThat(pdfText(200, Role.SALES, INVOICES + "/" + id + "/print")).doesNotContain("Our layout");

		// An invoice template cannot print a purchase order.
		long po = post(201, Role.PURCHASE, ORDERS, docBody(party("VENDOR", "27"), null, line(item("RAW_MATERIAL"), 2, 50))).path("id").asLong();
		assertCode(call(422, Role.PURCHASE, HttpMethod.GET, ORDERS + "/" + po + "/print?templateId=" + templateId, null), "TEMPLATE_WRONG_TYPE");
	}

	@Test
	void aBrokenTemplateIsRefusedWhenSavedAndOnlySettingsUsersManageTemplates() throws Exception {
		assertCode(post(422, Role.OWNER, TEMPLATES, obj("name", "Broken", "docType", "QUOTATION", "content", "<p>{{#lines}} no end</p>")), "TEMPLATE_INVALID");
		assertCode(post(422, Role.OWNER, TEMPLATES, obj("name", "", "docType", "QUOTATION", "content", "<p>x</p>")), "NAME_REQUIRED");
		assertCode(post(400, Role.OWNER, TEMPLATES, obj("name", "X", "docType", "PAYSLIP", "content", "<p>x</p>")), "UNKNOWN_DOC_TYPE");
		assertCode(post(403, Role.SALES, TEMPLATES, obj("name", "Mine", "docType", "QUOTATION", "content", "<p>x</p>")), "FORBIDDEN");

		// The built-in layout and sample data are there to start a template from.
		MvcResult builtIn = raw(Role.OWNER, TEMPLATES + "/built-in/QUOTATION");
		assertThat(builtIn.getResponse().getContentAsString()).contains("{{document.number}}");
		assertThat(get(Role.OWNER, TEMPLATES + "/sample-data/WORK_ORDER").path("jobCards").isArray()).isTrue();
		assertThat(get(Role.OWNER, TEMPLATES + "/sample-data/PURCHASE_INVOICE").path("documentTitle").asString()).isEqualTo("Purchase Invoice");
	}

	@Test
	void aWorkOrderPrintsWithItsMaterialsAndJobCards() throws Exception {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED");
		long warehouse = warehouse();
		long lathe = workCentre(600);
		long bom = post(201, Role.PRODUCTION, "/api/production/boms", obj("itemId", shaft, "outputQty", 1,
				"lines", List.of(obj("componentId", steel, "qty", 2)),
				"operations", List.of(obj("name", "Turning", "workCentreId", lathe, "setupMinutes", 30, "runMinutesPerUnit", 6)))).path("id").asLong();
		post(200, Role.PRODUCTION, "/api/production/boms/" + bom + "/approve", null);
		stockIn(steel, warehouse, 100);
		JsonNode wo = post(201, Role.PRODUCTION, "/api/production/work-orders",
				obj("itemId", shaft, "plannedQty", 10, "warehouseId", warehouse, "dueDate", TODAY.plusDays(7).toString()));
		long id = wo.path("id").asLong();
		post(200, Role.PRODUCTION, "/api/production/work-orders/" + id + "/release", null);

		// The bill of material prints too, with its components, routing and cost.
		String bomText = pdfText(200, Role.PRODUCTION, "/api/production/boms/" + bom + "/print");
		assertThat(bomText).contains("BILL OF MATERIAL", "Turning", "COMPONENTS", "Approved");
		JsonNode bomData = get(Role.PRODUCTION, "/api/production/boms/" + bom + "/print-data");
		assertThat(bomData.path("documentType").asString()).isEqualTo("BOM");
		assertThat(bomData.path("components").get(0).path("qty").asString()).isEqualTo("2");
		assertThat(bomData.path("operations").get(0).path("workCentre").asString()).isNotBlank();
		assertThat(get(Role.OWNER, TEMPLATES + "/sample-data/BOM").path("components").isArray()).isTrue();
		assertThat(get(Role.OWNER, TEMPLATES + "/types").toString()).contains("\"BOM\"");
		assertThat(raw(Role.OWNER, TEMPLATES + "/built-in/BOM").getResponse().getContentAsString()).contains("Bill of material", "{{#components}}");

		String text = pdfText(200, Role.PRODUCTION, "/api/production/work-orders/" + id + "/print");
		assertThat(text).contains(wo.path("woNo").asString(), "Turning", "JOB CARDS");
		JsonNode data = get(Role.PRODUCTION, "/api/production/work-orders/" + id + "/print-data");
		assertThat(data.path("materials").get(0).path("required").asString()).isEqualTo("20");
		assertThat(data.path("jobCards").get(0).path("operationName").asString()).isEqualTo("Turning");
		Files.createDirectories(Path.of("target/print-samples"));
		Files.writeString(Path.of("target/print-samples/work-order.json"), data.toPrettyString());
	}

	private JsonNode invoice() {
		long warehouse = warehouse();
		long shaft = item("FINISHED");
		stockIn(shaft, warehouse, 100);
		Map<String, Object> body = docBody(party("CUSTOMER", "27"), warehouse, obj("itemId", shaft, "qty", 10, "rate", 1000, "discountPct", 10));
		return post(201, Role.SALES, INVOICES, body);
	}

	private MvcResult raw(Role role, String path) throws Exception {
		MvcResult result = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token(role))).andReturn();
		assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
		return result;
	}

	private String pdfText(int status, Role role, String path) throws Exception {
		MvcResult result = raw(role, path);
		assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
		try (PDDocument pdf = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
			return new PDFTextStripper().getText(pdf);
		}
	}
}
