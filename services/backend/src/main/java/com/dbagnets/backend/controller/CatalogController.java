package com.dbagnets.backend.controller;

import com.dbagnets.backend.model.SupportedDatabasesResponse;
import com.dbagnets.backend.service.DatabaseCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final DatabaseCatalog databaseCatalog;

    public CatalogController(DatabaseCatalog databaseCatalog) {
        this.databaseCatalog = databaseCatalog;
    }

    @GetMapping("/databases")
    public SupportedDatabasesResponse getSupportedDatabases() {
        return databaseCatalog.getSupportedDatabases();
    }
}
