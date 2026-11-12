package com.group5.auctionservice.contract;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionStateMachine;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.common_web.architecture.ApiContractRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.group5.auctionservice", importOptions = ImportOption.DoNotIncludeTests.class)
class AuctionApiArchitectureTest {
    @ArchTest
    static final ArchRule responses = ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE;

    @ArchTest
    static final ArchRule auctionStatusWritesGoThroughStateMachine = noClasses()
            .that().doNotHaveFullyQualifiedName(AuctionStateMachine.class.getName())
            .should().callMethod(Auction.class, "setStatus", AuctionStatus.class);
}
