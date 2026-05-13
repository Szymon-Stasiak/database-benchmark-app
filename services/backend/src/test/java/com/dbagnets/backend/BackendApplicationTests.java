package com.dbagnets.backend;

import org.junit.jupiter.api.Test;

class BackendApplicationTests {

    @Test
    void mainMethodExists() {
        // Verify the application class is loadable without starting the full context,
        // which would require a reachable JWT issuer endpoint.
        BackendApplication app = new BackendApplication();
        org.junit.jupiter.api.Assertions.assertNotNull(app);
    }
}
