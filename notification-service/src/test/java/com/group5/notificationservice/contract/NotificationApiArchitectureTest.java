package com.group5.notificationservice.contract;

import com.group5.common_web.architecture.ApiContractRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.group5.notificationservice", importOptions = ImportOption.DoNotIncludeTests.class)
class NotificationApiArchitectureTest {
    @ArchTest
    static final ArchRule responses = ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE;
}
