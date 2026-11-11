package com.group5.shipmentservice.contract;

import com.group5.common_web.architecture.ApiContractRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.group5.shipmentservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ShipmentApiArchitectureTest {
    @ArchTest
    static final ArchRule responses = ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE;

    @ArchTest
    static final ArchRule servicesDoNotAccessJdbc = noClasses()
            .that().resideInAPackage("..shipping.service..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
            .because("shipment services delegate database access to repositories");
}
