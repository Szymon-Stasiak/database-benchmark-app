package com.dbagnets.backend.benchmark.setup.api;

import com.dbagnets.backend.benchmark.setup.api.dto.SupportedDatabasesResponse;
import com.dbagnets.backend.benchmark.setup.application.DatabaseCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final DatabaseCatalog databaseCatalog;

    @GetMapping("/databases")
    public SupportedDatabasesResponse getSupportedDatabases() {
        return databaseCatalog.getSupportedDatabases();
    }
}