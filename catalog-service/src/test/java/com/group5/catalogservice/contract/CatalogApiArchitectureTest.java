package com.group5.catalogservice.contract;

import com.group5.common_web.architecture.ApiContractRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.group5.catalogservice", importOptions = ImportOption.DoNotIncludeTests.class)
class CatalogApiArchitectureTest {
    @ArchTest
    static final ArchRule responses = ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE;
}
