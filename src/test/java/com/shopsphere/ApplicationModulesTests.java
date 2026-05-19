package com.shopsphere;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ApplicationModulesTests {

    private final ApplicationModules modules = ApplicationModules.of(ShopSphereApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules, "docs/modulith")
                .writeDocumentation()
                .writeIndividualModulesAsPlantUml();
    }
}
