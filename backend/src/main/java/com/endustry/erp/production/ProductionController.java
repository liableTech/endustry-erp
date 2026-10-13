package com.endustry.erp.production;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.platform.common.PageResponse;
import com.endustry.erp.production.ProductionTypes.BomCost;
import com.endustry.erp.production.ProductionTypes.BomRequest;
import com.endustry.erp.production.ProductionTypes.JobCardRequest;
import com.endustry.erp.production.ProductionTypes.MaterialNeed;
import com.endustry.erp.production.ProductionTypes.MaterialPlanLine;
import com.endustry.erp.production.ProductionTypes.OutputRequest;
import com.endustry.erp.production.ProductionTypes.WorkOrderRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/production")
@RequiredArgsConstructor
public class ProductionController {

	private final BomService boms;
	private final WorkOrderService workOrders;

	// ---- BOM ----

	@GetMapping("/boms")
	public PageResponse<Bom> boms(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(boms.list(q, status, pageable));
	}

	@GetMapping("/boms/{id}")
	public Bom bom(@PathVariable Long id) {
		return boms.get(id);
	}

	@GetMapping("/boms/{id}/cost")
	public BomCost bomCost(@PathVariable Long id) {
		return boms.cost(boms.get(id));
	}

	@GetMapping("/boms/where-used/{itemId}")
	public List<Bom> whereUsed(@PathVariable Long itemId) {
		return boms.whereUsed(itemId);
	}

	@PostMapping("/boms")
	@ResponseStatus(HttpStatus.CREATED)
	public Bom createBom(@Valid @RequestBody BomRequest body) {
		return boms.create(body);
	}

	@PutMapping("/boms/{id}")
	public Bom updateBom(@PathVariable Long id, @Valid @RequestBody BomRequest body) {
		return boms.update(id, body);
	}

	@PostMapping("/boms/{id}/approve")
	public Bom approveBom(@PathVariable Long id) {
		return boms.approve(id);
	}

	@PostMapping("/boms/{id}/copy")
	@ResponseStatus(HttpStatus.CREATED)
	public Bom copyBom(@PathVariable Long id) {
		return boms.copy(id);
	}

	// ---- work orders ----

	@GetMapping("/work-orders")
	public PageResponse<WorkOrder> workOrders(@RequestParam(required = false) String q,
			@RequestParam(required = false) String status,
			@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(workOrders.list(q, status, pageable));
	}

	@GetMapping("/work-orders/{id}")
	public WorkOrder workOrder(@PathVariable Long id) {
		return workOrders.get(id);
	}

	@GetMapping("/work-orders/{id}/availability")
	public List<MaterialNeed> availability(@PathVariable Long id) {
		return workOrders.availability(id);
	}

	@GetMapping("/work-orders/{id}/outputs")
	public List<WorkOrder.Output> outputs(@PathVariable Long id) {
		return workOrders.outputs(id);
	}

	@PostMapping("/work-orders")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkOrder createWorkOrder(@Valid @RequestBody WorkOrderRequest body) {
		return workOrders.create(body);
	}

	@PostMapping("/work-orders/{id}/release")
	public WorkOrder release(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean force) {
		return workOrders.release(id, force);
	}

	@PostMapping("/work-orders/{id}/cancel")
	public WorkOrder cancel(@PathVariable Long id) {
		return workOrders.cancel(id);
	}

	@PostMapping("/work-orders/{id}/output")
	public WorkOrder output(@PathVariable Long id, @Valid @RequestBody OutputRequest body) {
		return workOrders.bookOutput(id, body);
	}

	@PostMapping("/job-cards/{id}/{action}")
	public WorkOrder jobCard(@PathVariable Long id, @PathVariable String action,
			@Valid @RequestBody(required = false) JobCardRequest body) {
		return workOrders.jobCard(id, action, body);
	}

	@GetMapping("/material-plan")
	public List<MaterialPlanLine> materialPlan() {
		return workOrders.materialPlan();
	}
}
