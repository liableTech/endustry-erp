package com.endustry.erp.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.documents.DocumentRepositories.PaymentRepository;
import com.endustry.erp.documents.DocumentRepositories.TradeDocumentRepository;
import com.endustry.erp.documents.DocumentRequests.ActionRequest;
import com.endustry.erp.documents.DocumentRequests.DocumentRequest;
import com.endustry.erp.documents.DocumentRequests.Line;
import com.endustry.erp.documents.DocumentRequests.PaymentRequest;
import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.MasterRepositories.ItemRepository;
import com.endustry.erp.masters.MasterRepositories.PartyRepository;
import com.endustry.erp.masters.MasterRepositories.WarehouseRepository;
import com.endustry.erp.masters.Party;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.Amounts;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.common.Filters;
import com.endustry.erp.platform.security.PermissionService;
import com.endustry.erp.platform.settings.SettingsService;

import jakarta.persistence.criteria.Predicate;

/** Create, edit, progress and settle every kind of {@link TradeDocument}. */
@Service
@Transactional
public class DocumentService {

	private static final String ENTITY = "Document";
	/** Columns of the document list that take a from-to filter. */
	private static final List<String> FILTER_FIELDS = List.of("docDate", "dueDate", "grandTotal");

	private final TradeDocumentRepository documents;
	private final PaymentRepository payments;
	private final PartyRepository parties;
	private final ItemRepository items;
	private final WarehouseRepository warehouses;
	private final SettingsService settings;
	private final TaxCalculator tax;
	private final AuditService audit;
	private final PermissionService permissions;
	private final Map<DocKind, DocumentPolicy> policies = new EnumMap<>(DocKind.class);

	public DocumentService(TradeDocumentRepository documents, PaymentRepository payments, PartyRepository parties,
			ItemRepository items, WarehouseRepository warehouses, SettingsService settings, TaxCalculator tax,
			AuditService audit, PermissionService permissions, List<DocumentPolicy> policyBeans) {
		this.documents = documents;
		this.payments = payments;
		this.parties = parties;
		this.items = items;
		this.warehouses = warehouses;
		this.settings = settings;
		this.permissions = permissions;
		this.tax = tax;
		this.audit = audit;
		policyBeans.forEach(p -> policies.put(p.kind(), p));
	}

	// ---- read ----

	@Transactional(readOnly = true)
	public Page<TradeDocument> list(DocKind kind, String q, String status, String partyId, Map<String, String> filters, Pageable pageable) {
		Specification<TradeDocument> spec = (root, query, cb) -> {
			List<Predicate> all = new ArrayList<>();
			all.add(cb.equal(root.get("kind"), kind));
			if (q != null && !q.isBlank()) {
				String like = "%" + q.trim().toLowerCase() + "%";
				all.add(cb.or(cb.like(cb.lower(root.get("docNo")), like),
						cb.like(cb.lower(root.get("party").get("name")), like),
						cb.like(cb.lower(root.get("referenceNo")), like)));
			}
			if (status != null && !status.isBlank()) {
				all.add(root.get("status").in(Arrays.stream(status.split(",")).map(DocumentService::parseStatus).toList()));
			}
			if (partyId != null && !partyId.isBlank()) {
				all.add(Filters.predicate(cb, root.get("party").get("id"), "in", partyId));
			}
			// The table filters: a range of dates, due dates or totals.
			for (String field : FILTER_FIELDS) {
				for (String operator : List.of("from", "to")) {
					String value = filters == null ? null : filters.get(field + "." + operator);
					if (value != null && !value.isBlank()) {
						all.add(Filters.predicate(cb, root.get(field), operator, value));
					}
				}
			}
			return cb.and(all.toArray(Predicate[]::new));
		};
		return documents.findAll(spec, pageable).map(this::decorate);
	}

	@Transactional(readOnly = true)
	public TradeDocument get(DocKind kind, Long id) {
		return decorate(load(kind, id));
	}

	@Transactional(readOnly = true)
	public List<Payment> payments(DocKind kind, Long id) {
		return payments.findByDocumentIdOrderByPaymentDateAscIdAsc(load(kind, id).getId());
	}

	// ---- write ----

	public TradeDocument create(DocKind kind, DocumentRequest request) {
		TradeDocument doc = new TradeDocument();
		doc.setKind(kind);
		apply(doc, request);
		doc.setDocNo(settings.nextNumber(kind.name(), request.docDate()));
		TradeDocument saved = documents.saveAndFlush(doc);
		audit.note(ENTITY, saved.getId(), "CREATE", Map.of("docNo", saved.getDocNo(), "total", saved.getGrandTotal()));
		return decorate(saved);
	}

	public TradeDocument update(DocKind kind, Long id, DocumentRequest request) {
		TradeDocument doc = load(kind, id);
		if (doc.getStatus() != DocStatus.DRAFT) {
			throw ApiException.conflict("NOT_EDITABLE", doc.getDocNo() + " is " + label(doc.getStatus())
					+ " and can no longer be edited");
		}
		BigDecimal before = doc.getGrandTotal();
		apply(doc, request);
		TradeDocument saved = documents.saveAndFlush(doc);
		audit.note(ENTITY, id, "UPDATE", Map.of("docNo", saved.getDocNo(), "totalFrom", before, "totalTo", saved.getGrandTotal()));
		return decorate(saved);
	}

	/** Starts a draft of {@code targetKind} carrying whatever is still pending on the source. */
	public TradeDocument derive(DocKind targetKind, Long sourceId) {
		if (targetKind.source() == null) {
			throw ApiException.rule("NOT_DERIVABLE", "This document is not raised from another document");
		}
		if (!permissions.moduleEnabled(targetKind.source().module())) {
			throw ApiException.forbidden("MODULE_DISABLED", "The " + targetKind.source().module() + " module is not part of your subscription");
		}
		TradeDocument source = load(targetKind.source(), sourceId);
		if (!targetKind.canDeriveFrom(source)) {
			throw ApiException.conflict("INVALID_TRANSITION", source.getDocNo() + " is " + label(source.getStatus())
					+ "; it cannot be converted at this stage");
		}
		List<DocumentLine> pending = source.getLines().stream().filter(l -> l.getPendingQty().signum() > 0).toList();
		if (pending.isEmpty()) {
			throw ApiException.rule("NOTHING_PENDING", "Nothing is pending on " + source.getDocNo());
		}
		TradeDocument doc = new TradeDocument();
		doc.setKind(targetKind);
		doc.setParty(source.getParty());
		// A quotation usually has no warehouse; an order raised from it starts with the first active one, as a new order does.
		doc.setWarehouse(source.getWarehouse() == null && source.getKind() == DocKind.QUOTATION ? defaultWarehouse() : source.getWarehouse());
		doc.setSourceDoc(source);
		doc.setPlaceOfSupply(source.getPlaceOfSupply());
		doc.setDocDate(LocalDate.now());
		doc.setReferenceNo(targetKind.partyRole() == Party.Type.CUSTOMER ? source.getReferenceNo() : null);
		int lineNo = 1;
		for (DocumentLine from : pending) {
			DocumentLine line = new DocumentLine();
			line.setLineNo(lineNo++);
			line.setItem(from.getItem());
			line.setDescription(from.getDescription());
			line.setUom(from.getUom());
			line.setQty(from.getPendingQty());
			line.setRate(from.getRate());
			line.setDiscountPct(from.getDiscountPct());
			line.setBatchNo(from.getBatchNo());
			line.setSourceLineId(from.getId());
			doc.getLines().add(line);
		}
		tax.compute(doc, settings.company().getStateCode());
		doc.setDocNo(settings.nextNumber(targetKind.name(), doc.getDocDate()));
		TradeDocument saved = documents.saveAndFlush(doc);
		audit.note(ENTITY, saved.getId(), "CREATE", Map.of("docNo", saved.getDocNo(), "from", source.getDocNo()));
		return decorate(saved);
	}

	private Warehouse defaultWarehouse() {
		return warehouses.findAll(Sort.by("id")).stream().filter(Warehouse::isActive).findFirst().orElse(null);
	}

	public TradeDocument act(DocKind kind, Long id, String action, ActionRequest request) {
		TradeDocument doc = load(kind, id);
		DocumentPolicy policy = policies.get(kind);
		if (!policy.actions(doc).contains(action)) {
			throw ApiException.conflict("INVALID_TRANSITION",
					"'" + action + "' is not possible while " + doc.getDocNo() + " is " + label(doc.getStatus()));
		}
		DocStatus before = doc.getStatus();
		policy.execute(action, doc, request == null ? new ActionRequest(false, null) : request);
		TradeDocument saved = documents.saveAndFlush(doc);
		audit.note(ENTITY, id, action.toUpperCase(),
				Map.of("docNo", saved.getDocNo(), "from", before, "to", saved.getStatus()));
		return decorate(saved);
	}

	public TradeDocument pay(DocKind kind, Long id, PaymentRequest request) {
		TradeDocument doc = load(kind, id);
		if (!kind.isInvoice() || !doc.getStatus().isOutstanding()) {
			throw ApiException.conflict("NOT_PAYABLE", doc.getDocNo() + " is " + label(doc.getStatus())
					+ "; payments can be recorded only against a posted, unpaid invoice");
		}
		if (request.paymentDate().isBefore(doc.getDocDate())) {
			throw ApiException.rule("PAYMENT_BEFORE_INVOICE", "The payment date cannot be before the invoice date");
		}
		if (request.amount().compareTo(doc.getBalance()) > 0) {
			throw ApiException.rule("OVERPAYMENT", "Amount is more than the balance due of " + Amounts.inr(doc.getBalance()),
					Map.of("balance", doc.getBalance()));
		}
		Payment payment = new Payment();
		payment.setDocument(doc);
		payment.setPaymentDate(request.paymentDate());
		payment.setAmount(request.amount());
		payment.setMode(request.mode());
		payment.setReference(request.reference());
		payments.save(payment);
		doc.setPaidAmount(doc.getPaidAmount().add(request.amount()));
		doc.setStatus(doc.getBalance().signum() == 0 ? DocStatus.PAID : DocStatus.PARTLY_PAID);
		audit.note(ENTITY, id, "PAYMENT", Map.of("docNo", doc.getDocNo(), "amount", request.amount()));
		return decorate(documents.saveAndFlush(doc));
	}

	// ---- internals ----

	private TradeDocument load(DocKind kind, Long id) {
		return documents.findById(id).filter(d -> d.getKind() == kind)
				.orElseThrow(() -> ApiException.notFound(kind.slug(), id));
	}

	/** Copies a validated request onto a draft and recomputes all amounts. */
	private void apply(TradeDocument doc, DocumentRequest request) {
		DocKind kind = doc.getKind();
		Party party = parties.findById(request.partyId())
				.orElseThrow(() -> ApiException.rule("UNKNOWN_PARTY", "The selected party does not exist"));
		if (!party.isActive()) {
			throw ApiException.rule("PARTY_INACTIVE", party.getName() + " is inactive");
		}
		if (!party.getType().canBe(kind.partyRole())) {
			throw ApiException.rule("WRONG_PARTY_TYPE",
					party.getName() + " is not a " + kind.partyRole().name().toLowerCase());
		}
		if (doc.getSourceDoc() != null && !doc.getSourceDoc().getParty().getId().equals(party.getId())) {
			throw ApiException.rule("PARTY_MISMATCH", "The party must stay the same as on " + doc.getSourceDoc().getDocNo());
		}
		if (request.dueDate() != null && request.dueDate().isBefore(request.docDate())) {
			throw ApiException.rule("DUE_BEFORE_DATE", "The due date cannot be before the document date");
		}
		doc.setParty(party);
		doc.setDocDate(request.docDate());
		doc.setDueDate(request.dueDate());
		doc.setReferenceNo(blankToNull(request.referenceNo()));
		doc.setNotes(blankToNull(request.notes()));
		doc.setPlaceOfSupply(party.getStateCode());
		doc.setWarehouse(request.warehouseId() == null ? null
				: warehouses.findById(request.warehouseId()).filter(w -> w.isActive())
						.orElseThrow(() -> ApiException.rule("UNKNOWN_WAREHOUSE", "The selected warehouse does not exist or is inactive")));

		Map<Long, DocumentLine> sourceLines = new java.util.HashMap<>();
		if (doc.getSourceDoc() != null) {
			doc.getSourceDoc().getLines().forEach(l -> sourceLines.put(l.getId(), l));
		}
		doc.getLines().clear();
		int lineNo = 1;
		for (Line in : request.lines()) {
			Item item = items.findById(in.itemId())
					.orElseThrow(() -> ApiException.rule("UNKNOWN_ITEM", "A selected item does not exist"));
			if (!item.isActive()) {
				throw ApiException.rule("ITEM_INACTIVE", item.getCode() + " is inactive");
			}
			DocumentLine line = new DocumentLine();
			line.setLineNo(lineNo++);
			line.setItem(item);
			line.setDescription(blankToNull(in.description()));
			line.setUom(item.getUom());
			line.setQty(in.qty());
			line.setRate(in.rate());
			line.setDiscountPct(in.discountPct() == null ? BigDecimal.ZERO : in.discountPct());
			line.setBatchNo(blankToNull(in.batchNo()));
			if (in.sourceLineId() != null) {
				DocumentLine source = sourceLines.get(in.sourceLineId());
				if (source == null || !source.getItem().getId().equals(item.getId())) {
					throw ApiException.rule("LINE_NOT_ON_SOURCE", item.getCode() + " does not match a line on the source document");
				}
				line.setSourceLineId(source.getId());
				if (kind == DocKind.GRN) {
					// Goods are received at the ordered price; price differences surface on the invoice.
					line.setRate(source.getRate());
					line.setDiscountPct(source.getDiscountPct());
				}
			}
			doc.getLines().add(line);
		}
		tax.compute(doc, settings.company().getStateCode());
	}

	private TradeDocument decorate(TradeDocument doc) {
		List<String> actions = new ArrayList<>(policies.get(doc.getKind()).actions(doc));
		if (doc.getKind().isInvoice() && doc.getStatus().isOutstanding()) {
			actions.add("pay");
		}
		doc.setAllowedActions(actions);
		doc.setDerivations(Arrays.stream(DocKind.values()).filter(k -> k.canDeriveFrom(doc) && permissions.moduleEnabled(k.module())).map(DocKind::slug).toList());
		return doc;
	}

	private static DocStatus parseStatus(String value) {
		try {
			return DocStatus.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("INVALID_FILTER", "'" + value + "' is not a document status");
		}
	}

	private static String label(DocStatus status) {
		return status.name().toLowerCase().replace('_', ' ');
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
