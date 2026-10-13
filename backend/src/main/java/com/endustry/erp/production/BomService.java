package com.endustry.erp.production;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.MasterRepositories.ItemRepository;
import com.endustry.erp.masters.MasterRepositories.WorkCentreRepository;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.production.ProductionTypes.BomCost;
import com.endustry.erp.production.ProductionTypes.BomLineRequest;
import com.endustry.erp.production.ProductionTypes.BomOperationRequest;
import com.endustry.erp.production.ProductionTypes.BomRepository;
import com.endustry.erp.production.ProductionTypes.BomRequest;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class BomService {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

	private final BomRepository boms;
	private final ItemRepository items;
	private final WorkCentreRepository workCentres;
	private final AuditService audit;

	@Transactional(readOnly = true)
	public Page<Bom> list(String q, String status, Pageable pageable) {
		return boms.findAll((root, query, cb) -> {
			List<Predicate> all = new ArrayList<>();
			if (q != null && !q.isBlank()) {
				String like = "%" + q.trim().toLowerCase() + "%";
				all.add(cb.or(cb.like(cb.lower(root.get("item").get("code")), like),
						cb.like(cb.lower(root.get("item").get("name")), like)));
			}
			if (status != null && !status.isBlank()) {
				all.add(cb.equal(root.get("status"), parseStatus(status)));
			}
			return cb.and(all.toArray(Predicate[]::new));
		}, pageable);
	}

	@Transactional(readOnly = true)
	public Bom get(Long id) {
		return boms.findById(id).orElseThrow(() -> ApiException.notFound("BOM", id));
	}

	public Bom create(BomRequest request) {
		Bom bom = new Bom();
		apply(bom, request);
		bom.setVersionNo(boms.findByItemId(bom.getItem().getId()).stream().mapToInt(Bom::getVersionNo).max().orElse(0) + 1);
		Bom saved = boms.saveAndFlush(bom);
		audit.note("BOM", saved.getId(), "CREATE", Map.of("item", saved.getItem().getCode(), "version", saved.getVersionNo()));
		return saved;
	}

	public Bom update(Long id, BomRequest request) {
		Bom bom = get(id);
		if (bom.getStatus() != Bom.Status.DRAFT) {
			// BOM-02: an approved version is frozen; changes go into a new version.
			throw ApiException.conflict("NOT_EDITABLE", "Only a draft BOM can be edited. Copy this one to make a new version.");
		}
		if (!bom.getItem().getId().equals(request.itemId())) {
			throw ApiException.rule("BOM_ITEM_LOCKED", "The item of an existing BOM cannot be changed");
		}
		apply(bom, request);
		audit.note("BOM", id, "UPDATE", Map.of("item", bom.getItem().getCode(), "version", bom.getVersionNo()));
		return boms.saveAndFlush(bom);
	}

	/** Makes this the one approved version for its item and retires the previous one. */
	public Bom approve(Long id) {
		Bom bom = get(id);
		if (bom.getStatus() != Bom.Status.DRAFT) {
			throw ApiException.conflict("INVALID_TRANSITION", "Only a draft BOM can be approved");
		}
		boms.findByItemId(bom.getItem().getId()).stream().filter(b -> b.getStatus() == Bom.Status.APPROVED)
				.forEach(b -> b.setStatus(Bom.Status.OBSOLETE));
		bom.setStatus(Bom.Status.APPROVED);
		audit.note("BOM", id, "APPROVE", Map.of("item", bom.getItem().getCode(), "version", bom.getVersionNo()));
		return boms.saveAndFlush(bom);
	}

	/** BOM-10: starts a new draft version from an existing BOM. */
	public Bom copy(Long id) {
		Bom from = get(id);
		Bom bom = new Bom();
		bom.setItem(from.getItem());
		bom.setOutputQty(from.getOutputQty());
		bom.setNotes(from.getNotes());
		bom.setVersionNo(boms.findByItemId(from.getItem().getId()).stream().mapToInt(Bom::getVersionNo).max().orElse(0) + 1);
		for (Bom.Line l : from.getLines()) {
			Bom.Line line = new Bom.Line();
			line.setLineNo(l.getLineNo());
			line.setComponent(l.getComponent());
			line.setQty(l.getQty());
			line.setScrapPct(l.getScrapPct());
			bom.getLines().add(line);
		}
		for (Bom.Operation o : from.getOperations()) {
			Bom.Operation op = new Bom.Operation();
			op.setSeqNo(o.getSeqNo());
			op.setName(o.getName());
			op.setWorkCentre(o.getWorkCentre());
			op.setSetupMinutes(o.getSetupMinutes());
			op.setRunMinutesPerUnit(o.getRunMinutesPerUnit());
			bom.getOperations().add(op);
		}
		return boms.saveAndFlush(bom);
	}

	/** BOM-07: material at current average cost plus scrap, and operations at work-centre rates. */
	@Transactional(readOnly = true)
	public BomCost cost(Bom bom) {
		BigDecimal material = BigDecimal.ZERO;
		for (Bom.Line line : bom.getLines()) {
			material = material.add(grossQty(line, bom.getOutputQty(), bom).multiply(costRate(line.getComponent())));
		}
		BigDecimal operation = operationCost(bom, bom.getOutputQty(), true);
		BigDecimal total = material.add(operation).setScale(2, RoundingMode.HALF_UP);
		return new BomCost(material.setScale(2, RoundingMode.HALF_UP), operation, total,
				total.divide(bom.getOutputQty(), 4, RoundingMode.HALF_UP));
	}

	/**
	 * CST-01: expected cost per unit when making {@code qty} in one order. Setup time is spread over
	 * the whole quantity, so a longer run has a lower standard cost per unit.
	 */
	@Transactional(readOnly = true)
	public BigDecimal standardUnitCost(Bom bom, BigDecimal qty) {
		BigDecimal material = BigDecimal.ZERO;
		for (Bom.Line line : bom.getLines()) {
			material = material.add(grossQty(line, qty, bom).multiply(costRate(line.getComponent())));
		}
		return material.add(operationCost(bom, qty, true)).divide(qty, 4, RoundingMode.HALF_UP);
	}

	/** BOM-08: every BOM that consumes the given item. */
	@Transactional(readOnly = true)
	public List<Bom> whereUsed(Long componentId) {
		return boms.findUsing(componentId);
	}

	/** Quantity of a component, including scrap allowance, needed to make {@code outputQty}. */
	BigDecimal grossQty(Bom.Line line, BigDecimal outputQty, Bom bom) {
		return line.getQty().multiply(HUNDRED.add(line.getScrapPct())).divide(HUNDRED)
				.multiply(outputQty).divide(bom.getOutputQty(), 4, RoundingMode.HALF_UP);
	}

	/** Standard cost of running the routing for {@code qty} units; setup is charged once. */
	BigDecimal operationCost(Bom bom, BigDecimal qty, boolean includeSetup) {
		BigDecimal cost = BigDecimal.ZERO;
		for (Bom.Operation op : bom.getOperations()) {
			BigDecimal minutes = op.getRunMinutesPerUnit().multiply(qty);
			if (includeSetup) {
				minutes = minutes.add(op.getSetupMinutes());
			}
			cost = cost.add(minutes.multiply(op.getWorkCentre().getHourlyCost()).divide(SIXTY, 4, RoundingMode.HALF_UP));
		}
		return cost.setScale(2, RoundingMode.HALF_UP);
	}

	private static BigDecimal costRate(Item component) {
		if (component.getAvgCost().signum() > 0) {
			return component.getAvgCost();
		}
		return component.getStandardCost().signum() > 0 ? component.getStandardCost() : component.getPurchasePrice();
	}

	private void apply(Bom bom, BomRequest request) {
		Item item = items.findById(request.itemId())
				.orElseThrow(() -> ApiException.rule("UNKNOWN_ITEM", "The selected item does not exist"));
		if (!item.getType().isManufactured()) {
			throw ApiException.rule("BOM_ITEM_NOT_MANUFACTURED", "A BOM can be defined only for a finished or semi-finished item");
		}
		bom.setItem(item);
		bom.setOutputQty(request.outputQty());
		bom.setNotes(request.notes());

		bom.getLines().clear();
		Set<Long> seen = new HashSet<>();
		int lineNo = 1;
		for (BomLineRequest in : request.lines()) {
			Item component = items.findById(in.componentId())
					.orElseThrow(() -> ApiException.rule("UNKNOWN_ITEM", "A selected component does not exist"));
			if (!seen.add(component.getId())) {
				throw ApiException.rule("DUPLICATE_COMPONENT", component.getCode() + " is listed more than once");
			}
			if (!component.getType().isStocked()) {
				throw ApiException.rule("NOT_A_STOCK_ITEM", component.getCode() + " is a service and cannot be a component");
			}
			if (component.getId().equals(item.getId()) || reaches(component, item.getId())) {
				throw ApiException.rule("CIRCULAR_BOM", component.getCode() + " would make " + item.getCode() + " a component of itself");
			}
			Bom.Line line = new Bom.Line();
			line.setLineNo(lineNo++);
			line.setComponent(component);
			line.setQty(in.qty());
			line.setScrapPct(in.scrapPct() == null ? BigDecimal.ZERO : in.scrapPct());
			bom.getLines().add(line);
		}

		bom.getOperations().clear();
		int seqNo = 10;
		for (BomOperationRequest in : request.operations() == null ? List.<BomOperationRequest>of() : request.operations()) {
			Bom.Operation op = new Bom.Operation();
			op.setSeqNo(seqNo);
			seqNo += 10;
			op.setName(in.name().trim());
			op.setWorkCentre(workCentres.findById(in.workCentreId()).filter(w -> w.isActive())
					.orElseThrow(() -> ApiException.rule("UNKNOWN_WORK_CENTRE", "A selected work centre does not exist or is inactive")));
			op.setSetupMinutes(in.setupMinutes() == null ? BigDecimal.ZERO : in.setupMinutes());
			op.setRunMinutesPerUnit(in.runMinutesPerUnit());
			bom.getOperations().add(op);
		}
	}

	/** Whether {@code target} appears anywhere beneath {@code start} in the approved BOM tree. */
	private boolean reaches(Item start, Long target) {
		Deque<Long> queue = new ArrayDeque<>(List.of(start.getId()));
		Set<Long> visited = new HashSet<>();
		while (!queue.isEmpty()) {
			Long current = queue.poll();
			if (!visited.add(current)) {
				continue;
			}
			for (Bom child : boms.findByItemId(current)) {
				if (child.getStatus() == Bom.Status.OBSOLETE) {
					continue;
				}
				for (Bom.Line line : child.getLines()) {
					if (line.getComponent().getId().equals(target)) {
						return true;
					}
					queue.add(line.getComponent().getId());
				}
			}
		}
		return false;
	}

	private static Bom.Status parseStatus(String value) {
		try {
			return Bom.Status.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("INVALID_FILTER", "'" + value + "' is not a BOM status");
		}
	}
}
