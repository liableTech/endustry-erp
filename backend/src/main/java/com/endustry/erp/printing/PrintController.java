package com.endustry.erp.printing;

import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.printing.PrintService.Rendered;
import com.endustry.erp.printing.PrintService.TemplateRequest;
import com.endustry.erp.printing.PrintService.TemplateSummary;
import com.endustry.erp.printing.PrintService.TypeInfo;

import lombok.RequiredArgsConstructor;

/**
 * Printing sits under each document's own module path, so anyone who can view a document can
 * print it. Managing templates sits under /api/settings.
 */
@RestController
@RequiredArgsConstructor
public class PrintController {

	private final PrintService service;

	public record PreviewRequest(String docType, String content) {
	}

	public record DefaultRequest(Long templateId) {
	}

	// ---- quotations, orders, receipts and invoices ----

	@GetMapping("/api/{module}/docs/{kind}/{id}/print")
	public ResponseEntity<byte[]> printDocument(@PathVariable String module, @PathVariable String kind, @PathVariable Long id,
			@RequestParam(required = false) Long templateId, @RequestParam(defaultValue = "false") boolean download) {
		return pdf(service.printTrade(DocKind.fromSlug(module, kind), id, templateId, true), download);
	}

	@GetMapping("/api/{module}/docs/{kind}/{id}/print-data")
	public Map<String, Object> documentData(@PathVariable String module, @PathVariable String kind, @PathVariable Long id) {
		return service.tradeData(DocKind.fromSlug(module, kind), id);
	}

	@GetMapping("/api/{module}/docs/{kind}/print-templates")
	public List<TemplateSummary> documentTemplates(@PathVariable String module, @PathVariable String kind) {
		return service.list(PrintDocType.of(DocKind.fromSlug(module, kind)));
	}

	// ---- work orders with their job cards ----

	@GetMapping("/api/production/work-orders/{id}/print")
	public ResponseEntity<byte[]> printWorkOrder(@PathVariable Long id, @RequestParam(required = false) Long templateId,
			@RequestParam(defaultValue = "false") boolean download) {
		return pdf(service.printWorkOrder(id, templateId, true), download);
	}

	@GetMapping("/api/production/work-orders/{id}/print-data")
	public Map<String, Object> workOrderData(@PathVariable Long id) {
		return service.workOrderData(id);
	}

	@GetMapping("/api/production/work-orders/print-templates")
	public List<TemplateSummary> workOrderTemplates() {
		return service.list(PrintDocType.WORK_ORDER);
	}

	// ---- bills of material ----

	@GetMapping("/api/production/boms/{id}/print")
	public ResponseEntity<byte[]> printBom(@PathVariable Long id, @RequestParam(required = false) Long templateId,
			@RequestParam(defaultValue = "false") boolean download) {
		return pdf(service.printBom(id, templateId, true), download);
	}

	@GetMapping("/api/production/boms/{id}/print-data")
	public Map<String, Object> bomData(@PathVariable Long id) {
		return service.bomData(id);
	}

	@GetMapping("/api/production/boms/print-templates")
	public List<TemplateSummary> bomTemplates() {
		return service.list(PrintDocType.BOM);
	}

	// ---- template management ----

	@GetMapping("/api/settings/print-templates")
	public List<TemplateSummary> templates(@RequestParam(required = false) String docType) {
		return service.list(docType == null || docType.isBlank() ? null : PrintDocType.parse(docType));
	}

	@GetMapping("/api/settings/print-templates/types")
	public List<TypeInfo> types() {
		return service.types();
	}

	@GetMapping("/api/settings/print-templates/{id}")
	public PrintTemplate template(@PathVariable Long id) {
		return service.get(id);
	}

	@PostMapping("/api/settings/print-templates")
	@ResponseStatus(HttpStatus.CREATED)
	public TemplateSummary create(@RequestBody TemplateRequest body) {
		return service.create(body);
	}

	@PutMapping("/api/settings/print-templates/{id}")
	public TemplateSummary update(@PathVariable Long id, @RequestBody TemplateRequest body) {
		return service.update(id, body);
	}

	@DeleteMapping("/api/settings/print-templates/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}

	/** Chooses the template a document type prints with; an empty templateId means the built-in one. */
	@PutMapping("/api/settings/print-templates/default/{docType}")
	public List<TypeInfo> setDefault(@PathVariable String docType, @RequestBody DefaultRequest body) {
		service.setDefault(PrintDocType.parse(docType), body.templateId());
		return service.types();
	}

	/** The built-in layout's HTML, as a starting point for a template of your own. */
	@GetMapping(value = "/api/settings/print-templates/built-in/{docType}", produces = "text/plain;charset=UTF-8")
	public String builtIn(@PathVariable String docType) {
		return service.builtIn(PrintDocType.parse(docType));
	}

	/** The JSON a template of this type is filled from, using the latest real document. */
	@GetMapping("/api/settings/print-templates/sample-data/{docType}")
	public Map<String, Object> sampleData(@PathVariable String docType) {
		return service.sampleData(PrintDocType.parse(docType));
	}

	@PostMapping("/api/settings/print-templates/preview")
	public ResponseEntity<byte[]> preview(@RequestBody PreviewRequest body) {
		PrintDocType type = PrintDocType.parse(body.docType());
		return pdf(new Rendered("Preview.pdf", null, service.preview(type, body.content() == null ? "" : body.content())), false);
	}

	private static ResponseEntity<byte[]> pdf(Rendered rendered, boolean download) {
		ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
				.filename(rendered.fileName()).build();
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(rendered.pdf());
	}
}
