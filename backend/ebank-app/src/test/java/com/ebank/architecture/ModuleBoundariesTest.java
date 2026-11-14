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
        noClasses().that().resideInAnyPackage(
                        "com.ebank.accounts..", "com.ebank.transactions..", "com.ebank.notifications..", "com.ebank.analytics..",
                        "com.ebank.chatbot..", "com.ebank.realtime..")
                .should().dependOnClassesThat().resideInAPackage("com.ebank.auth.security..")
                .check(CLASSES);
    }

    @Test
    void commonDependsOnNoDomainModule() {
        noClasses().that().resideInAPackage("com.ebank.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ebank.accounts..", "com.ebank.transactions..", "com.ebank.auth..", "com.ebank.notifications..", "com.ebank.analytics..",
                        "com.ebank.chatbot..", "com.ebank.realtime..")
                .check(CLASSES);
    }

    /** Notifications learn about transactions from their events only, never from their tables or services. */
    @Test
    void notificationsOnlyUsesTheTransactionsEvents() {
        noClasses().that().resideInAPackage("com.ebank.notifications..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ebank.transactions.application..",
                        "com.ebank.transactions.domain..",
                        "com.ebank.transactions.infrastructure..",
                        "com.ebank.transactions.web..",
                        "com.ebank.accounts..")
                .check(CLASSES);
    }

    /** Analytics is a read model fed by events: it never reads another module's data directly. */
    @Test
    void analyticsOnlyUsesTheTransactionsEvents() {
        noClasses().that().resideInAPackage("com.ebank.analytics..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ebank.transactions.application..",
                        "com.ebank.transactions.domain..",
                        "com.ebank.transactions.infrastructure..",
                        "com.ebank.transactions.web..",
                        "com.ebank.accounts..",
                        "com.ebank.notifications..")
                .check(CLASSES);
    }

    @Test
    void noModuleDependsOnAnalytics() {
        noClasses().that().resideInAnyPackage(
                        "com.ebank.accounts..", "com.ebank.transactions..", "com.ebank.notifications..", "com.ebank.common..", "com.ebank.auth..")
                .should().dependOnClassesThat().resideInAPackage("com.ebank.analytics..")
                .check(CLASSES);
    }

    /** The assistant's tools reach banking data only through the read-only public APIs. */
    @Test
    void chatbotOnlyUsesPublicApis() {
        noClasses().that().resideInAPackage("com.ebank.chatbot..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ebank.accounts.application..", "com.ebank.accounts.domain..",
                        "com.ebank.accounts.infrastructure..", "com.ebank.accounts.web..",
                        "com.ebank.transactions.application..", "com.ebank.transactions.domain..",
                        "com.ebank.transactions.infrastructure..", "com.ebank.transactions.web..",
                        "com.ebank.realtime.stomp..", "com.ebank.notifications..", "com.ebank.analytics..")
                .check(CLASSES);
    }

    @Test
    void notificationsOnlyUsesTheChatbotEvents() {
        noClasses().that().resideInAPackage("com.ebank.notifications..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ebank.chatbot.application..", "com.ebank.chatbot.assistant..", "com.ebank.chatbot.conversation..",
                        "com.ebank.chatbot.web..", "com.ebank.chatbot.audit..", "com.ebank.chatbot.realtime..",
                        "com.ebank.chatbot.ratelimit..", "com.ebank.realtime.stomp..")
                .check(CLASSES);
    }

    @Test
    void lowerModulesDoNotDependOnChatOrRealtime() {
        noClasses().that().resideInAnyPackage(
                        "com.ebank.accounts..", "com.ebank.transactions..", "com.ebank.common..", "com.ebank.auth..", "com.ebank.analytics..")
                .should().dependOnClassesThat().resideInAnyPackage("com.ebank.chatbot..", "com.ebank.realtime..")
                .check(CLASSES);
    }

    @Test
    void sourceModulesDoNotDependOnNotifications() {
        noClasses().that().resideInAnyPackage("com.ebank.accounts..", "com.ebank.transactions..")
                .should().dependOnClassesThat().resideInAPackage("com.ebank.notifications..")
                .check(CLASSES);
    }
}
