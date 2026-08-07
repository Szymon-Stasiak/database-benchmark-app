package com.dbagnets.backend.engine.driver.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class DriverResolutionTest {

    @Test
    void resolvedCarriesDriver() {
        EngineDriver driver = mock(EngineDriver.class);
        DriverResolution resolution = new DriverResolution.Resolved(driver);

        assertThat(resolution).isInstanceOf(DriverResolution.Resolved.class);
        assertThat(((DriverResolution.Resolved) resolution).driver()).isSameAs(driver);
    }

    @Test
    void skippedCarriesReason() {
        DriverResolution resolution = new DriverResolution.Skipped("engine not supported");

        assertThat(resolution).isInstanceOf(DriverResolution.Skipped.class);
        assertThat(((DriverResolution.Skipped) resolution).reason())
                .isEqualTo("engine not supported");
    }

    @Test
    void patternMatchingWorks() {
        DriverResolution resolution = new DriverResolution.Skipped("reason");

        String result;
        if (resolution instanceof DriverResolution.Resolved r) {
            result = "resolved";
        } else if (resolution instanceof DriverResolution.Skipped s) {
            result = "skipped:" + s.reason();
        } else {
            result = "unknown";
        }

        assertThat(result).isEqualTo("skipped:reason");
    }
}
