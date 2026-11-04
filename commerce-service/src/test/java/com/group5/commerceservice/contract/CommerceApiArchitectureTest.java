package com.group5.commerceservice.contract;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.group5.common_web.architecture.ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE;

class CommerceApiArchitectureTest {
    @Test void controllersFollowTheResponseContract() {
        CONTROLLERS_RETURN_API_RESPONSE.check(new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.group5.commerceservice"));
    }
}
