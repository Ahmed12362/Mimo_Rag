package com.mimocodes.mimorag.architecture;

import java.util.function.Supplier;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the module map in docs/architecture.md. Rules that target modules not yet implemented are allowed to
 * match nothing ({@code allowEmptyShould}) so they start protecting each module the moment it appears.
 */
@AnalyzeClasses(packages = ArchitectureRulesTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

	static final String ROOT = "com.mimocodes.mimorag";

	/** Business modules. They must stay independent of identity providers and authentication mechanics. */
	private static final String[] CORE_MODULES = { ROOT + ".integration..", ROOT + ".knowledgebase..",
			ROOT + ".ingestion..", ROOT + ".review..", ROOT + ".indexing..", ROOT + ".retrieval..", ROOT + ".tools..",
			ROOT + ".agent..", ROOT + ".evaluation.." };

	private static final String[] FEATURE_MODULES = { ROOT + ".access..", ROOT + ".integration..",
			ROOT + ".knowledgebase..", ROOT + ".ingestion..", ROOT + ".review..", ROOT + ".indexing..",
			ROOT + ".retrieval..", ROOT + ".tools..", ROOT + ".agent..", ROOT + ".evaluation.." };

	@ArchTest
	static final ArchRule modulesAreFreeOfCycles = slices().matching(ROOT + ".(*)..")
		.should()
		.beFreeOfCycles();

	@ArchTest
	static final ArchRule sharedKernelDependsOnNoFeatureModule = noClasses().that()
		.resideInAPackage(ROOT + ".shared..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage(FEATURE_MODULES);

	@ArchTest
	static final ArchRule coreModulesAreIndependentOfAuthenticationMechanics = noClasses().that()
		.resideInAnyPackage(CORE_MODULES)
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage(ROOT + ".access..", "org.springframework.security..", "com.nimbusds..",
				"io.jsonwebtoken..")
		.allowEmptyShould(true);

	/** ADR-0009: domain classes may carry jakarta.persistence mapping annotations, but nothing else framework-specific. */
	@ArchTest
	static final ArchRule domainIsFrameworkFree = noClasses().that()
		.resideInAPackage("..domain..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.springframework..", "org.hibernate..", "tools.jackson..", "com.fasterxml.jackson..",
				"jakarta.servlet..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule domainDependsOnNoOuterLayer = noClasses().that()
		.resideInAPackage("..domain..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..application..", "..api..", "..infrastructure..")
		.allowEmptyShould(true);

	/** The access module talks to the integration module only through its application layer. */
	@ArchTest
	static final ArchRule accessUsesIntegrationApplicationLayerOnly = noClasses().that()
		.resideInAPackage(ROOT + ".access..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage(ROOT + ".integration.api..", ROOT + ".integration.infrastructure..")
		.allowEmptyShould(true);

	/**
	 * Binding an access context decides which tenant (or the admin plane) the database sees. Only authentication
	 * ({@code access}), the persistence infrastructure and the future job runner may do it.
	 */
	@ArchTest
	static final ArchRule onlyTrustedCodeBindsAccessContexts = noClasses().that()
		.resideOutsideOfPackages(ROOT + ".access..", ROOT + ".shared.access..", ROOT + ".shared.persistence..",
				ROOT + ".jobs..")
		.should()
		.callMethod(AccessContextHolder.class, "set", AccessContext.class)
		.orShould()
		.callMethod(AccessContextHolder.class, "callAs", AccessContext.class, Supplier.class)
		.orShould()
		.callMethod(AccessContextHolder.class, "clear");

	/** JWT/JOSE libraries are confined to the access module. */
	@ArchTest
	static final ArchRule joseLibrariesOnlyInAccess = noClasses().that()
		.resideOutsideOfPackage(ROOT + ".access..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.nimbusds..", "org.springframework.security.oauth2..", "io.jsonwebtoken..");

	@ArchTest
	static final ArchRule apiLayerDoesNotReachIntoInfrastructure = noClasses().that()
		.resideInAPackage("..api..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..infrastructure..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule applicationLayerDoesNotDependOnApiLayer = noClasses().that()
		.resideInAPackage("..application..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..api..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule controllersLiveInApiOrAccessPackages = classes().that()
		.areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
		.should()
		.resideInAnyPackage("..api..", ROOT + ".access..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule controllersDoNotUseRepositoriesDirectly = noClasses().that()
		.areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
		.should()
		.dependOnClassesThat()
		.areAssignableTo(org.springframework.data.repository.Repository.class)
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

	@ArchTest
	static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

}
