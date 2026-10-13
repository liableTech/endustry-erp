package com.endustry.erp.masters;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.endustry.erp.masters.SimpleMasters.Employee;
import com.endustry.erp.masters.SimpleMasters.Lookup;
import com.endustry.erp.masters.SimpleMasters.TaxRate;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.masters.SimpleMasters.WorkCentre;
import com.endustry.erp.platform.common.BaseRepository;

public final class MasterRepositories {

	private MasterRepositories() {
	}

	public interface ItemRepository extends BaseRepository<Item> {
		Optional<Item> findByCode(String code);
	}

	public interface PartyRepository extends BaseRepository<Party> {
		List<Party> findByGstin(String gstin);

		Optional<Party> findByCode(String code);
	}

	public interface WarehouseRepository extends BaseRepository<Warehouse> {
	}

	public interface WorkCentreRepository extends BaseRepository<WorkCentre> {
	}

	public interface EmployeeRepository extends BaseRepository<Employee> {
	}

	public interface TaxRateRepository extends BaseRepository<TaxRate> {
		Optional<TaxRate> findFirstByHsnCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String hsnCode,
				LocalDate date);
	}

	public interface LookupRepository extends BaseRepository<Lookup> {
		boolean existsByLookupTypeAndCode(String lookupType, String code);
	}
}
