package com.endustry.erp.production;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocStatus;
import com.endustry.erp.documents.DocumentLine;
import com.endustry.erp.documents.DocumentRepositories.TradeDocumentRepository;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.inventory.StockMovement;
import com.endustry.erp.inventory.StockService;
import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.MasterRepositories.EmployeeRepository;
import com.endustry.erp.masters.MasterRepositories.ItemRepository;
import com.endustry.erp.masters.MasterRepositories.LookupRepository;
import com.endustry.erp.masters.MasterRepositories.WarehouseRepository;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.settings.SettingsService;
import com.endustry.erp.production.ProductionTypes.BomRepository;
import com.endustry.erp.production.ProductionTypes.JobCardRequest;
import com.endustry.erp.production.ProductionTypes.MaterialNeed;
import com.endustry.erp.production.ProductionTypes.MaterialPlanLine;
import com.endustry.erp.production.ProductionTypes.OutputRepository;
import com.endustry.erp.production.ProductionTypes.OutputRequest;
import com.endustry.erp.production.ProductionTypes.WorkOrderRepository;
import com.endustry.erp.production.ProductionTypes.WorkOrderRequest;
import com.endustry.erp.production.WorkOrder.JobCard;
import com.endustry.erp.production.WorkOrder.Status;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class WorkOrderService {

	private static final String ENTITY = "WorkOrder";

	private final WorkOrderRepository workOrders;
	private final OutputRepository outputs;
	private final BomRepository boms;
	private final BomService bomService;
	private final ItemRepository items;
	private final WarehouseRepository warehouses;
	private final EmployeeRepository employees;
	private final LookupRepository lookups;
	private final TradeDocumentRepository documents;
	private final StockService stock;
	private final SettingsService settings;
	private final AuditService audit;

	// ---- read ----

	@Transactional(readOnly = true)
	public Page<WorkOrder> list(String q, String status, Pageable pageable) {
		return workOrders.findAll((root, query, cb) -> {
			List<Predicate> all = new ArrayList<>();
			if (q != null && !q.isBlank()) {
				String like = "%" + q.trim().toLowerCase() + "%";
				all.add(cb.or(cb.like(cb.lower(root.get("woNo")), like), cb.like(cb.lower(root.get("item").get("code")), like),
						cb.like(cb.lower(root.get("item").get("name")), like)));
			}
			if (status != null && !status.isBlank()) {
				all.add(root.get("status").in(java.util.Arrays.stream(status.split(",")).map(WorkOrderService::parseStatus).toList()));
			}
			return cb.and(all.toArray(Predicate[]::new));
		}, pageable);
	}

	@Transactional(readOnly = true)
	public WorkOrder get(Long id) {
		return workOrders.findById(id).orElseThrow(() -> ApiException.notFound("Work order", id));
	}

	@Transactional(readOnly = true)
	public List<WorkOrder.Output> outputs(Long id) {
		return outputs.findByWorkOrderIdOrderByIdAsc(get(id).getId());
	}

	/** PPC-06: what the remaining quantity needs against what is available in the order's warehouse. */
	@Transactional(readOnly = true)
	public List<MaterialNeed> availability(Long id) {
		return needs(get(id));
	}

	// ---- lifecycle ----

	public WorkOrder create(WorkOrderRequest request) {
		Item item = items.findById(request.itemId())
				.orElseThrow(() -> ApiException.rule("UNKNOWN_ITEM", "The selected item does not exist"));
		Bom bom = request.bomId() == null
				? boms.findFirstByItemIdAndStatus(item.getId(), Bom.Status.APPROVED)
						.orElseThrow(() -> ApiException.rule("NO_APPROVED_BOM", item.getCode() + " has no approved BOM"))
				: boms.findById(request.bomId()).orElseThrow(() -> ApiException.rule("UNKNOWN_BOM", "The selected BOM does not exist"));
		if (!bom.getItem().getId().equals(item.getId())) {
			throw ApiException.rule("BOM_ITEM_MISMATCH", "The selected BOM is for " + bom.getItem().getCode() + ", not " + item.getCode());
		}
		if (bom.getStatus() != Bom.Status.APPROVED) {
			throw ApiException.rule("BOM_NOT_APPROVED", "Only an approved BOM version can be used in production");
		}
		LocalDate start = request.startDate() == null ? LocalDate.now() : request.startDate();
		if (request.dueDate().isBefore(start)) {
			throw ApiException.rule("DUE_BEFORE_START", "The due date cannot be before the start date");
		}
		WorkOrder wo = new WorkOrder();
		wo.setItem(item);
		wo.setBom(bom);
		wo.setPlannedQty(request.plannedQty());
		wo.setWarehouse(warehouses.findById(request.warehouseId()).filter(w -> w.isActive())
				.orElseThrow(() -> ApiException.rule("UNKNOWN_WAREHOUSE", "The selected warehouse does not exist or is inactive")));
		wo.setStartDate(start);
		wo.setDueDate(request.dueDate());
		wo.setSalesOrderNo(request.salesOrderNo());
		wo.setNotes(request.notes());
		wo.setStandardUnitCost(bomService.standardUnitCost(bom, request.plannedQty()));
		wo.setWoNo(settings.nextNumber("WORK_ORDER", start));
		WorkOrder saved = workOrders.saveAndFlush(wo);
		audit.note(ENTITY, saved.getId(), "CREATE", Map.of("woNo", saved.getWoNo(), "item", item.getCode(), "qty", saved.getPlannedQty()));
		return saved;
	}

	/** Releases to the floor, creating one job card per routing step. */
	public WorkOrder release(Long id, boolean force) {
		WorkOrder wo = get(id);
		requireStatus(wo, "release", Status.PLANNED);
		List<MaterialNeed> shortages = needs(wo).stream().filter(n -> n.shortage().signum() > 0).toList();
		if (!shortages.isEmpty() && !force) {
			throw ApiException.rule("MATERIAL_SHORTAGE",
					shortages.size() + " component(s) are short for " + wo.getWoNo() + ". Release anyway to start with what is available.",
					shortages);
		}
		for (Bom.Operation op : wo.getBom().getOperations()) {
			JobCard card = new JobCard();
			card.setSeqNo(op.getSeqNo());
			card.setOperationName(op.getName());
			card.setWorkCentre(op.getWorkCentre());
			wo.getJobCards().add(card);
		}
		wo.setStatus(Status.RELEASED);
		audit.note(ENTITY, id, "RELEASE", Map.of("woNo", wo.getWoNo(), "forcedWithShortage", !shortages.isEmpty()));
		return workOrders.saveAndFlush(wo);
	}

	public WorkOrder cancel(Long id) {
		WorkOrder wo = get(id);
		requireStatus(wo, "cancel", Status.PLANNED, Status.RELEASED);
		wo.setStatus(Status.CANCELLED);
		audit.note(ENTITY, id, "CANCEL", Map.of("woNo", wo.getWoNo()));
		return workOrders.saveAndFlush(wo);
	}

	/**
	 * Books output: consumes components per the BOM (backflush, SHP-05), receives the good quantity
	 * into stock at its actual cost (SHP-06, CST-02) and closes the order when it is fully made.
	 */
	public WorkOrder bookOutput(Long id, OutputRequest request) {
		WorkOrder wo = get(id);
		requireStatus(wo, "book output on", Status.RELEASED, Status.IN_PROGRESS);
		BigDecimal total = request.goodQty().add(request.rejectedQty());
		if (total.signum() == 0) {
			throw ApiException.badRequest("ZERO_OUTPUT", "Enter a good or rejected quantity");
		}
		if (total.compareTo(wo.getRemainingQty()) > 0) {
			throw ApiException.rule("OVER_PRODUCTION", "Only " + plain(wo.getRemainingQty()) + " remains on " + wo.getWoNo(),
					Map.of("remaining", wo.getRemainingQty(), "requested", total));
		}
		boolean rejecting = request.rejectedQty().signum() > 0;
		if (rejecting && (request.reasonCode() == null || !lookups.existsByLookupTypeAndCode("REJECTION_REASON", request.reasonCode()))) {
			throw ApiException.rule("REASON_REQUIRED", "Choose a rejection reason for the rejected quantity");
		}
		LocalDate date = request.outputDate() == null ? LocalDate.now() : request.outputDate();
		if (date.isAfter(LocalDate.now())) {
			throw ApiException.rule("FUTURE_DATE", "The output date cannot be in the future");
		}
		Bom bom = wo.getBom();
		StockService.Ref ref = new StockService.Ref("WORK_ORDER", wo.getId(), wo.getWoNo());
		BigDecimal material = BigDecimal.ZERO;
		for (Bom.Line line : bom.getLines()) {
			material = material.add(stock.issue(line.getComponent(), wo.getWarehouse(), bomService.grossQty(line, total, bom),
					StockMovement.Type.PRODUCTION_ISSUE, ref, date));
		}
		boolean firstBooking = wo.getProducedQty().add(wo.getRejectedQty()).signum() == 0;
		BigDecimal operation = bomService.operationCost(bom, total, firstBooking);
		if (request.goodQty().signum() > 0) {
			BigDecimal unitCost = material.add(operation).divide(request.goodQty(), 4, RoundingMode.HALF_UP);
			stock.receive(wo.getItem(), wo.getWarehouse(), wo.getItem().isBatchTracked() ? wo.getWoNo() : "",
					StockMovement.Status.AVAILABLE, request.goodQty(), unitCost, StockMovement.Type.PRODUCTION_RECEIPT, ref, date);
		}
		wo.setProducedQty(wo.getProducedQty().add(request.goodQty()));
		wo.setRejectedQty(wo.getRejectedQty().add(request.rejectedQty()));
		wo.setMaterialCost(wo.getMaterialCost().add(material));
		wo.setOperationCost(wo.getOperationCost().add(operation));
		if (wo.getRemainingQty().signum() == 0) {
			wo.setStatus(Status.COMPLETED);
			wo.setCompletedDate(date);
		} else {
			wo.setStatus(Status.IN_PROGRESS);
		}

		WorkOrder.Output output = new WorkOrder.Output();
		output.setWorkOrderId(wo.getId());
		output.setWoNo(wo.getWoNo());
		output.setItem(wo.getItem());
		output.setOutputDate(date);
		output.setGoodQty(request.goodQty());
		output.setRejectedQty(request.rejectedQty());
		output.setReasonCode(rejecting ? request.reasonCode() : null);
		output.setMaterialCost(material);
		output.setOperationCost(operation);
		outputs.save(output);
		audit.note(ENTITY, id, "OUTPUT", Map.of("woNo", wo.getWoNo(), "good", request.goodQty(), "rejected", request.rejectedQty()));
		return workOrders.saveAndFlush(wo);
	}

	// ---- shop floor ----

	/** SHP-02: start, pause or finish a routing step; run time is captured from the clock. */
	public WorkOrder jobCard(Long jobCardId, String action, JobCardRequest request) {
		WorkOrder wo = workOrders.findByJobCard(jobCardId).orElseThrow(() -> ApiException.notFound("Job card", jobCardId));
		if (wo.getStatus() != Status.RELEASED && wo.getStatus() != Status.IN_PROGRESS) {
			throw ApiException.conflict("INVALID_TRANSITION", wo.getWoNo() + " is " + label(wo.getStatus()) + "; its job cards are closed");
		}
		JobCard card = wo.getJobCards().stream().filter(c -> c.getId().equals(jobCardId)).findFirst().orElseThrow();
		JobCardRequest body = request == null ? new JobCardRequest(null, null, null, null) : request;
		switch (action) {
			case "start" -> {
				if (card.getStatus() != JobCard.Status.PENDING && card.getStatus() != JobCard.Status.PAUSED) {
					throw invalidJobCard(card, action);
				}
				boolean earlierOpen = wo.getJobCards().stream()
						.anyMatch(c -> c.getSeqNo() < card.getSeqNo() && c.getStatus() != JobCard.Status.DONE);
				if (earlierOpen) {
					throw ApiException.rule("OPERATION_OUT_OF_SEQUENCE", "Finish the earlier operations before starting " + card.getOperationName());
				}
				if (body.operatorId() != null) {
					card.setOperator(employees.findById(body.operatorId()).filter(e -> e.isActive())
							.orElseThrow(() -> ApiException.rule("UNKNOWN_OPERATOR", "The selected operator does not exist or is inactive")));
				}
				card.setRunningSince(Instant.now());
				card.setPauseReason(null);
				card.setStatus(JobCard.Status.RUNNING);
				if (wo.getStatus() == Status.RELEASED) {
					wo.setStatus(Status.IN_PROGRESS);
				}
			}
			case "pause" -> {
				if (card.getStatus() != JobCard.Status.RUNNING) {
					throw invalidJobCard(card, action);
				}
				if (body.reasonCode() == null || !lookups.existsByLookupTypeAndCode("DOWNTIME_REASON", body.reasonCode())) {
					throw ApiException.rule("REASON_REQUIRED", "Choose a reason for pausing");
				}
				stopClock(card);
				card.setPauseReason(body.reasonCode());
				card.setStatus(JobCard.Status.PAUSED);
			}
			case "complete" -> {
				if (card.getStatus() != JobCard.Status.RUNNING && card.getStatus() != JobCard.Status.PAUSED) {
					throw invalidJobCard(card, action);
				}
				stopClock(card);
				card.setGoodQty(body.goodQty() == null ? BigDecimal.ZERO : body.goodQty());
				card.setRejectedQty(body.rejectedQty() == null ? BigDecimal.ZERO : body.rejectedQty());
				if (card.getGoodQty().add(card.getRejectedQty()).compareTo(wo.getPlannedQty()) > 0) {
					throw ApiException.rule("OVER_PRODUCTION", "Quantity is more than the " + plain(wo.getPlannedQty()) + " planned on " + wo.getWoNo());
				}
				card.setStatus(JobCard.Status.DONE);
			}
			default -> throw ApiException.badRequest("UNKNOWN_ACTION", "'" + action + "' is not a job card action");
		}
		return workOrders.saveAndFlush(wo);
	}

	// ---- planning ----

	/**
	 * PPC-04, a simple material plan: demand from open work orders, less stock on hand and
	 * quantities already on approved purchase orders, topped up to the reorder level (INV-08).
	 */
	@Transactional(readOnly = true)
	public List<MaterialPlanLine> materialPlan() {
		Map<Long, BigDecimal> required = new LinkedHashMap<>();
		Map<Long, Item> itemsById = new LinkedHashMap<>();
		for (WorkOrder wo : workOrders.findByStatusIn(List.of(Status.PLANNED, Status.RELEASED, Status.IN_PROGRESS))) {
			for (Bom.Line line : wo.getBom().getLines()) {
				itemsById.put(line.getComponent().getId(), line.getComponent());
				required.merge(line.getComponent().getId(), bomService.grossQty(line, wo.getRemainingQty(), wo.getBom()), BigDecimal::add);
			}
		}
		items.findAll().stream().filter(i -> i.isActive() && i.getReorderLevel().signum() > 0 && !i.getType().isManufactured())
				.forEach(i -> itemsById.putIfAbsent(i.getId(), i));

		Map<Long, BigDecimal> onOrder = new LinkedHashMap<>();
		for (TradeDocument po : documents.findByKindAndStatusIn(DocKind.PURCHASE_ORDER, List.of(DocStatus.APPROVED, DocStatus.PARTLY_FULFILLED))) {
			for (DocumentLine line : po.getLines()) {
				onOrder.merge(line.getItem().getId(), line.getPendingQty(), BigDecimal::add);
			}
		}
		List<MaterialPlanLine> plan = new ArrayList<>();
		itemsById.values().forEach(item -> {
			BigDecimal need = required.getOrDefault(item.getId(), BigDecimal.ZERO);
			BigDecimal available = stock.available(item, null);
			BigDecimal incoming = onOrder.getOrDefault(item.getId(), BigDecimal.ZERO);
			BigDecimal suggested = need.add(item.getReorderLevel()).subtract(available).subtract(incoming).max(BigDecimal.ZERO);
			if (suggested.signum() > 0) {
				plan.add(new MaterialPlanLine(item.getId(), item.getCode(), item.getName(), item.getUom(), need, available, incoming,
						item.getReorderLevel(), suggested.setScale(3, RoundingMode.UP), item.getLeadTimeDays()));
			}
		});
		return plan;
	}

	// ---- internals ----

	private List<MaterialNeed> needs(WorkOrder wo) {
		return wo.getBom().getLines().stream().map(line -> {
			Item c = line.getComponent();
			BigDecimal required = bomService.grossQty(line, wo.getRemainingQty(), wo.getBom());
			BigDecimal available = stock.available(c, wo.getWarehouse());
			return new MaterialNeed(c.getId(), c.getCode(), c.getName(), c.getUom(), required, available,
					required.subtract(available).max(BigDecimal.ZERO));
		}).toList();
	}

	private static void stopClock(JobCard card) {
		if (card.getRunningSince() != null) {
			card.setMinutesWorked(card.getMinutesWorked() + Math.max(1, Duration.between(card.getRunningSince(), Instant.now()).toMinutes()));
			card.setRunningSince(null);
		}
	}

	private static void requireStatus(WorkOrder wo, String verb, Status... allowed) {
		if (!List.of(allowed).contains(wo.getStatus())) {
			throw ApiException.conflict("INVALID_TRANSITION", "Cannot " + verb + " " + wo.getWoNo() + " while it is " + label(wo.getStatus()));
		}
	}

	private static ApiException invalidJobCard(JobCard card, String action) {
		return ApiException.conflict("INVALID_TRANSITION",
				"Cannot " + action + " " + card.getOperationName() + " while it is " + card.getStatus().name().toLowerCase());
	}

	private static Status parseStatus(String value) {
		try {
			return Status.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("INVALID_FILTER", "'" + value + "' is not a work order status");
		}
	}

	private static String label(Status status) {
		return status.name().toLowerCase().replace('_', ' ');
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}
}
