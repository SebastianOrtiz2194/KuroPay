package com.kuropay.wallet;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.kuropay.wallet",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_remain_pure_and_framework_free =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "..application..",
                            "..infrastructure..",
                            "org.springframework..",
                            "jakarta.persistence..",
                            "tools.jackson.."
                    )
                    .allowEmptyShould(true)
                    .because("Domain layer in Hexagonal Architecture must be pure Java without framework dependencies");

    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..")
                    .allowEmptyShould(true)
                    .because("Application layer must communicate with infrastructure only through domain/application ports (interfaces)");
}
