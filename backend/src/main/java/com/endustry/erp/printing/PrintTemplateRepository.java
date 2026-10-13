package com.endustry.erp.printing;

import java.util.List;

import com.endustry.erp.platform.common.BaseRepository;

public interface PrintTemplateRepository extends BaseRepository<PrintTemplate> {

	List<PrintTemplate> findByDocTypeOrderByName(PrintDocType docType);

	List<PrintTemplate> findAllByOrderByDocTypeAscNameAsc();

	List<PrintTemplate> findByDocTypeAndDefaultForTypeTrue(PrintDocType docType);
}
