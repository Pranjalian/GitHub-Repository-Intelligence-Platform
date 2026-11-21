# spring-multi-module: expected architecture

Evaluation fixture for MVP 2 Maven module relationships (`ArchitectureE2eTests`).

- Modules: `.` (shop-parent, pom), `core` (shop-core), `web` (shop-web).
- Module dependency: `web -> core` (web/pom.xml declares com.example:shop-core).
- Layers: `web.InventoryController` CONTROLLER (DETECTED), `core.InventoryService` SERVICE (DETECTED).
- Endpoint: `POST /inventory/{sku}/reserve`.
- DI chain: InventoryController -> InventoryService, across the two modules.
