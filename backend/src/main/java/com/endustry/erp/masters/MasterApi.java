package com.endustry.erp.masters;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.masters.MasterRepositories.EmployeeRepository;
import com.endustry.erp.masters.MasterRepositories.ItemRepository;
import com.endustry.erp.masters.MasterRepositories.LookupRepository;
import com.endustry.erp.masters.MasterRepositories.PartyRepository;
import com.endustry.erp.masters.MasterRepositories.TaxRateRepository;
import com.endustry.erp.masters.MasterRepositories.WarehouseRepository;
import com.endustry.erp.masters.MasterRepositories.WorkCentreRepository;
import com.endustry.erp.masters.SimpleMasters.Employee;
import com.endustry.erp.masters.SimpleMasters.Lookup;
import com.endustry.erp.masters.SimpleMasters.TaxRate;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.masters.SimpleMasters.WorkCentre;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.common.CrudController;
import com.endustry.erp.platform.common.CrudService;

// Each master is an entity plus the few lines below; behaviour lives in CrudService / CrudController.

@Service
class ItemService extends CrudService<Item> {

	private final LookupRepository lookups;

	ItemService(ItemRepository repo, LookupRepository lookups) {
		super(repo, "Item", List.of("code", "name", "hsnCode"));
		this.lookups = lookups;
	}

	@Override
	protected String[] protectedFields() {
		return new String[] { "avgCost" };
	}

	@Override
	protected void validate(Item incoming, Item existing) {
		incoming.setCode(incoming.getCode().trim().toUpperCase());
		if (!lookups.existsByLookupTypeAndCode("UOM", incoming.getUom())) {
			throw ApiException.rule("UNKNOWN_UOM", "Unit '" + incoming.getUom() + "' is not in the unit master");
		}
		if (incoming.getType() == Item.Type.SERVICE && (incoming.isBatchTracked() || incoming.isQcRequired())) {
			throw ApiException.rule("SERVICE_NOT_STOCKED", "A service item cannot be batch tracked or quality checked");
		}
		if (existing != null && existing.getType() != incoming.getType() && existing.getAvgCost().signum() != 0) {
			throw ApiException.rule("ITEM_TYPE_LOCKED", "The item type cannot change once stock has been received");
		}
	}
}

@Service
class PartyService extends CrudService<Party> {

	private final PartyRepository parties;

	PartyService(PartyRepository repo) {
		super(repo, "Party", List.of("code", "name", "gstin", "city"));
		this.parties = repo;
	}

	@Override
	protected void validate(Party incoming, Party existing) {
		incoming.setCode(incoming.getCode().trim().toUpperCase());
		String gstin = incoming.getGstin();
		if (gstin == null || gstin.isBlank()) {
			incoming.setGstin(null);
		} else {
			if (!gstin.startsWith(incoming.getStateCode())) {
				throw ApiException.rule("GSTIN_STATE_MISMATCH", "The first two digits of the GSTIN must match the state code");
			}
			if (incoming.getPan() != null && !incoming.getPan().isBlank() && !gstin.substring(2, 12).equals(incoming.getPan())) {
				throw ApiException.rule("GSTIN_PAN_MISMATCH", "Characters 3 to 12 of the GSTIN must be the party's PAN");
			}
			// ORG-10: the same GSTIN on two parties is almost always an accidental duplicate.
			parties.findByGstin(gstin).stream()
					.filter(other -> existing == null || !other.getId().equals(existing.getId())).findFirst()
					.ifPresent(other -> {
						throw ApiException.conflict("DUPLICATE_GSTIN",
								"GSTIN " + gstin + " is already used by " + other.getCode() + " " + other.getName());
					});
		}
		if (incoming.isMsmeRegistered() && !incoming.getType().canBe(Party.Type.VENDOR)) {
			throw ApiException.rule("MSME_VENDOR_ONLY", "MSME registration is tracked for vendors only");
		}
		if (incoming.isMsmeRegistered() && (incoming.getUdyamNumber() == null || incoming.getUdyamNumber().isBlank())) {
			throw ApiException.rule("UDYAM_REQUIRED", "An Udyam number is required for an MSME-registered vendor");
		}
	}
}

@Service
class WarehouseService extends CrudService<Warehouse> {
	WarehouseService(WarehouseRepository repo) {
		super(repo, "Warehouse", List.of("code", "name"));
	}
}

@Service
class WorkCentreService extends CrudService<WorkCentre> {
	WorkCentreService(WorkCentreRepository repo) {
		super(repo, "WorkCentre", List.of("code", "name"));
	}
}

@Service
class EmployeeService extends CrudService<Employee> {
	EmployeeService(EmployeeRepository repo) {
		super(repo, "Employee", List.of("code", "name", "department"));
	}
}

@Service
class TaxRateService extends CrudService<TaxRate> {
	TaxRateService(TaxRateRepository repo) {
		super(repo, "TaxRate", List.of("hsnCode", "description"));
	}
}

@Service
class LookupService extends CrudService<Lookup> {
	LookupService(LookupRepository repo) {
		super(repo, "Lookup", List.of("code", "name"));
	}
}

@RestController
@RequestMapping("/api/masters/items")
class ItemController extends CrudController<Item> {
	ItemController(ItemService service) {
		super(service);
	}
}

@RestController
@RequestMapping("/api/masters/parties")
class PartyController extends CrudController<Party> {
	PartyController(PartyService service) {
		super(service);
	}
}

@RestController
@RequestMapping("/api/masters/warehouses")
class WarehouseController extends CrudController<Warehouse> {
	WarehouseController(WarehouseService service) {
		super(service);
	}
}

@RestController
@RequestMapping("/api/masters/work-centres")
class WorkCentreController extends CrudController<WorkCentre> {
	WorkCentreController(WorkCentreService service) {
		super(service);
	}
}

@RestController
@RequestMapping("/api/masters/employees")
class EmployeeController extends CrudController<Employee> {
	EmployeeController(EmployeeService service) {
		super(service);
	}
}

@RestController
@RequestMapping("/api/masters/tax-rates")
class TaxRateController extends CrudController<TaxRate> {
	TaxRateController(TaxRateService service) {
		super(service);
	}
}

@RestController
@RequestMapping("/api/masters/lookups")
class LookupController extends CrudController<Lookup> {
	LookupController(LookupService service) {
		super(service);
	}
}
