package com.ebank.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** "Modules only talk through public APIs or domain events": the build fails if one doesn't. */
class ModuleBoundariesTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.ebank");

    @Test
    void transactionsOnlyUsesTheAccountsPublicApi() {
        noClasses().that().resideInAPackage("com.ebank.transactions..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ebank.accounts.application..",
                        "com.ebank.accounts.domain..",
                        "com.ebank.accounts.infrastructure..",
                        "com.ebank.accounts.web..")
                .check(CLASSES);
    }

    @Test
    void accountsDoesNotDependOnTransactions() {
        noClasses().that().resideInAPackage("com.ebank.accounts..")
                .should().dependOnClassesThat().resideInAPackage("com.ebank.transactions..")
                .check(CLASSES);
    }

    @Test
    void modulesOnlyUseTheAuthPublicApi() {
        noClasses().that().resideInAnyPackage("com.ebank.accounts..", "com.ebank.transactions..")
                .should().dependOnClassesThat().resideInAPackage("com.ebank.auth.security..")
                .check(CLASSES);
    }

    @Test
    void commonDependsOnNoDomainModule() {
        noClasses().that().resideInAPackage("com.ebank.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ebank.accounts..", "com.ebank.transactions..", "com.ebank.auth..")
                .check(CLASSES);
    }
}
