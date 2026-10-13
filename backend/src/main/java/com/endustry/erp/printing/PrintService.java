package com.endustry.erp.printing;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocumentRepositories.TradeDocumentRepository;
import com.endustry.erp.documents.DocumentService;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.security.PermissionService;
import com.endustry.erp.production.Bom;
import com.endustry.erp.production.BomService;
import com.endustry.erp.production.WorkOrder;
import com.endustry.erp.production.WorkOrderService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** Prints saved documents, with the client's own template or the built-in one, and manages those templates. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PrintService {

	/** Uploaded templates may carry an embedded logo, so allow a couple of megabytes. */
	public static final int MAX_TEMPLATE_CHARS = 3_000_000;
	private static final String ENTITY = "PrintTemplate";

	private final PrintTemplateRepository templates;
	private final PrintDataBuilder dataBuilder;
	private final PrintRenderer renderer;
	private final DocumentService documents;
	private final TradeDocumentRepository documentRepo;
	private final WorkOrderService workOrders;
	private final BomService boms;
	private final AuditService audit;
	private final ObjectMapper mapper;
	private final PermissionService permissions;

	public record Rendered(String fileName, String html, byte[] pdf) {
	}

	public record TemplateRequest(String name, String docType, String description, Boolean defaultForType, String content) {
	}

	public record TemplateSummary(Long id, String name, PrintDocType docType, String description, boolean defaultForType,
			int size, String updatedBy, java.time.Instant updatedAt) {
		static TemplateSummary of(PrintTemplate t) {
			return new TemplateSummary(t.getId(), t.getName(), t.getDocType(), t.getDescription(), t.isDefaultForType(),
					t.getContent().length(), t.getUpdatedBy(), t.getUpdatedAt());
		}
	}

	/** A document type with the template its prints currently use. */
	public record TypeInfo(PrintDocType docType, String title, String module, String usesTemplate) {
	}

	// ---- printing ----

	public Map<String, Object> tradeData(DocKind kind, Long id) {
		return dataBuilder.trade(documents.get(kind, id));
	}

	public Map<String, Object> workOrderData(Long id) {
		WorkOrder wo = workOrders.get(id);
		return dataBuilder.workOrder(wo, workOrders.availability(id));
	}

	public Map<String, Object> bomData(Long id) {
		Bom bom = boms.get(id);
		return dataBuilder.bom(bom, boms.cost(bom));
	}

	public Rendered printBom(Long id, Long templateId, boolean pdf) {
		return render(PrintDocType.BOM, bomData(id), templateId, pdf);
	}

	public Rendered printTrade(DocKind kind, Long id, Long templateId, boolean pdf) {
		Map<String, Object> data = tradeData(kind, id);
		return render(PrintDocType.of(kind), data, templateId, pdf);
	}

	public Rendered printWorkOrder(Long id, Long templateId, boolean pdf) {
		return render(PrintDocType.WORK_ORDER, workOrderData(id), templateId, pdf);
	}

	private Rendered render(PrintDocType type, Map<String, Object> data, Long templateId, boolean pdf) {
		String html = renderer.html(templateFor(type, templateId), data);
		@SuppressWarnings("unchecked")
		String number = String.valueOf(((Map<String, Object>) data.get("document")).get("number"));
		String fileName = (type.title().replaceAll("[^A-Za-z0-9]+", "-") + "-" + number.replaceAll("[^A-Za-z0-9_-]+", "-")) + ".pdf";
		return new Rendered(fileName, html, pdf ? renderer.pdf(html) : null);
	}

	/** The picked template (0 picks the built-in one), else the type's default, else the built-in layout. */
	private String templateFor(PrintDocType type, Long templateId) {
		if (templateId != null && templateId == 0) {
			return builtIn(type);
		}
		if (templateId != null) {
			PrintTemplate t = load(templateId);
			if (t.getDocType() != type) {
				throw ApiException.rule("TEMPLATE_WRONG_TYPE", "'" + t.getName() + "' is a template for " + t.getDocType().title());
			}
			return t.getContent();
		}
		return templates.findByDocTypeAndDefaultForTypeTrue(type).stream().findFirst().map(PrintTemplate::getContent)
				.orElseGet(() -> builtIn(type));
	}

	public String builtIn(PrintDocType type) {
		return PrintRenderer.resource("print-templates/" + (type.isTrade() ? "default-document.html" : type == PrintDocType.BOM ? "default-bom.html" : "default-work-order.html"));
	}

	/**
	 * Real data from the latest document of the type, so a template can be tried on something
	 * familiar; the bundled example when the client has none yet.
	 */
	public Map<String, Object> sampleData(PrintDocType type) {
		if (type.isTrade()) {
			DocKind kind = DocKind.valueOf(type.name());
			var latest = documentRepo.findAll((root, q, cb) -> cb.equal(root.get("kind"), kind),
					org.springframework.data.domain.PageRequest.of(0, 1, org.springframework.data.domain.Sort.by("id").descending()));
			if (latest.hasContent()) {
				return tradeData(kind, latest.getContent().get(0).getId());
			}
		} else if (type == PrintDocType.BOM) {
			var latest = boms.list(null, null,
					org.springframework.data.domain.PageRequest.of(0, 1, org.springframework.data.domain.Sort.by("id").descending()));
			if (latest.hasContent()) {
				return bomData(latest.getContent().get(0).getId());
			}
		} else {
			var latest = workOrders.list(null, null,
					org.springframework.data.domain.PageRequest.of(0, 1, org.springframework.data.domain.Sort.by("id").descending()));
			if (latest.hasContent()) {
				return workOrderData(latest.getContent().get(0).getId());
			}
		}
		return exampleData(type);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> exampleData(PrintDocType type) {
		String json = PrintRenderer.resource("print-templates/" + (type.isTrade() ? "sample-document-data.json" : type == PrintDocType.BOM ? "sample-bom-data.json" : "sample-work-order-data.json"));
		Map<String, Object> data = mapper.readValue(json, Map.class);
		if (type.isTrade()) {
			data.put("documentType", type.name());
			data.put("documentTitle", type.title());
		}
		return data;
	}

	/** Renders template text against sample data, as a check before saving or as a preview. */
	public byte[] preview(PrintDocType type, String content) {
		return renderer.pdf(renderer.html(content, sampleData(type)));
	}

	// ---- templates ----

	public List<TemplateSummary> list(PrintDocType type) {
		return (type == null ? templates.findAllByOrderByDocTypeAscNameAsc() : templates.findByDocTypeOrderByName(type))
				.stream().filter(t -> permissions.moduleEnabled(t.getDocType().module())).map(TemplateSummary::of).toList();
	}

	/** The document types of the modules switched on for the client. */
	public List<TypeInfo> types() {
		return java.util.Arrays.stream(PrintDocType.values()).filter(t -> permissions.moduleEnabled(t.module())).map(t -> new TypeInfo(t, t.title(), t.module(),
				templates.findByDocTypeAndDefaultForTypeTrue(t).stream().findFirst().map(PrintTemplate::getName).orElse(null)))
				.toList();
	}

	public PrintTemplate get(Long id) {
		return load(id);
	}

	@Transactional
	public TemplateSummary create(TemplateRequest request) {
		PrintTemplate t = new PrintTemplate();
		apply(t, request);
		PrintTemplate saved = templates.saveAndFlush(t);
		audit.note(ENTITY, saved.getId(), "CREATE", Map.of("name", saved.getName(), "docType", saved.getDocType()));
		return TemplateSummary.of(saved);
	}

	@Transactional
	public TemplateSummary update(Long id, TemplateRequest request) {
		PrintTemplate t = load(id);
		apply(t, request);
		PrintTemplate saved = templates.saveAndFlush(t);
		audit.note(ENTITY, id, "UPDATE", Map.of("name", saved.getName(), "docType", saved.getDocType()));
		return TemplateSummary.of(saved);
	}

	@Transactional
	public void delete(Long id) {
		PrintTemplate t = load(id);
		templates.delete(t);
		audit.note(ENTITY, id, "DELETE", Map.of("name", t.getName(), "docType", t.getDocType()));
	}

	/** Makes this the template used for its type; null id goes back to the built-in layout. */
	@Transactional
	public void setDefault(PrintDocType type, Long id) {
		PrintTemplate chosen = id == null ? null : load(id);
		if (chosen != null && chosen.getDocType() != type) {
			throw ApiException.rule("TEMPLATE_WRONG_TYPE", "'" + chosen.getName() + "' is a template for " + chosen.getDocType().title());
		}
		templates.findByDocTypeOrderByName(type).forEach(t -> t.setDefaultForType(chosen != null && t.getId().equals(chosen.getId())));
		audit.note(ENTITY, id, "SET_DEFAULT", Map.of("docType", type, "template", chosen == null ? "built-in" : chosen.getName()));
	}

	private void apply(PrintTemplate t, TemplateRequest request) {
		if (request.name() == null || request.name().isBlank()) {
			throw ApiException.rule("NAME_REQUIRED", "Give the template a name");
		}
		if (request.name().trim().length() > 80) {
			throw ApiException.rule("NAME_TOO_LONG", "The name can be at most 80 characters");
		}
		if (request.docType() == null) {
			throw ApiException.rule("DOC_TYPE_REQUIRED", "Choose which document this template is for");
		}
		String content = request.content();
		if (content == null || content.isBlank()) {
			throw ApiException.rule("CONTENT_REQUIRED", "Upload or paste the template HTML");
		}
		if (content.length() > MAX_TEMPLATE_CHARS) {
			throw ApiException.rule("TEMPLATE_TOO_LARGE", "The template is too large; keep it under 3 MB including any embedded images");
		}
		PrintDocType type = PrintDocType.parse(request.docType());
		// Fail now, with the reason, rather than the first time someone prints.
		preview(type, content);
		t.setName(request.name().trim());
		t.setDescription(request.description() == null || request.description().isBlank() ? null : request.description().trim());
		t.setContent(content);
		if (t.getDocType() != null && t.getDocType() != type) {
			t.setDefaultForType(false);
		}
		t.setDocType(type);
		if (request.defaultForType() != null) {
			if (request.defaultForType()) {
				templates.findByDocTypeOrderByName(type).forEach(o -> o.setDefaultForType(false));
			}
			t.setDefaultForType(request.defaultForType());
		}
	}

	private PrintTemplate load(Long id) {
		return templates.findById(id).orElseThrow(() -> ApiException.notFound("Print template", id));
	}
}
