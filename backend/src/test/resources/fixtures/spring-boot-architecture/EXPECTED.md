# spring-boot-architecture: expected architecture

Evaluation fixture for MVP 2. `SpringArchitectureAnalyzerTests` and the architecture end-to-end test
assert exactly this; change both when the fixture changes.

## Layers (26 of 27 types)

| Type | Layer | Confidence | Method |
|---|---|---|---|
| ShopApplication | CONFIGURATION | DETECTED | ANNOTATION (`@SpringBootApplication`) |
| config.ShopConfig | CONFIGURATION | DETECTED | ANNOTATION |
| web.OrderController | CONTROLLER | DETECTED | ANNOTATION |
| web.CreateOrderRequest, web.OrderResponse | DTO | LIKELY | API_SIGNATURE (request body / response) |
| service.OrderService, service.AuditService, service.ReportService | SERVICE | DETECTED | ANNOTATION |
| service.* without `@Service` (PriceCalculator, DiscountPolicy, SeasonalDiscount, LoyaltyDiscount, PaymentFactory, Notifier, EmailNotifier, SmsNotifier, NotifierProvider, PaymentGateway, LegacyPaymentClient, LegacyPaymentBridge) | SERVICE | LIKELY | PACKAGE_HEURISTIC (overrides a POSSIBLE `@Component`) |
| repository.OrderRepository, repository.CustomerRepository | REPOSITORY | DETECTED | TYPE_HIERARCHY |
| domain.Order, domain.OrderLine, domain.Customer | ENTITY | DETECTED | ANNOTATION |
| support.IdGenerator | INFRASTRUCTURE | POSSIBLE | ANNOTATION (`@Component`) |

`web.ApiPaths` has no layer: it only holds constants.

## Endpoints (7)

| Method | Route | Request | Response |
|---|---|---|---|
| GET | /api/orders | | OrderResponse (from `List<OrderResponse>`) |
| GET | /api/orders/{id} | | OrderResponse (from `ResponseEntity<OrderResponse>`) |
| POST | /api/orders | CreateOrderRequest | OrderResponse |
| DELETE | /api/orders/{id} | | |
| GET, POST | /api/orders/search | CreateOrderRequest | OrderResponse |
| GET | /api/orders/{ExternalRoutes.LEGACY} (unresolved) | | String |

The class path comes from the constant `ApiPaths.ORDERS`.

## Entities (4 relationships, 2 repository mappings)

Order.customer MANY_TO_ONE Customer (fetch LAZY), Order.lines ONE_TO_MANY OrderLine (mappedBy order),
OrderLine.order MANY_TO_ONE Order (joinColumn order_id), Customer.orders ONE_TO_MANY Order (mappedBy customer).
OrderRepository REPOSITORY_FOR Order, CustomerRepository REPOSITORY_FOR Customer (idType Long).

## Dependency injection

OrderController -> OrderService -> OrderRepository, CustomerRepository, DiscountPolicy (as `List`);
AuditService -> ReportService -> AuditService.

## Cycles (1)

injection-cycle between AuditService and ReportService (MEDIUM, DETECTED). The bidirectional JPA
associations are not reported.

## Pattern candidates (5)

STRATEGY DiscountPolicy (LIKELY), FACTORY NotifierProvider.create (POSSIBLE), ADAPTER
LegacyPaymentBridge over LegacyPaymentClient (POSSIBLE), REPOSITORY OrderRepository and
CustomerRepository (DETECTED). `PaymentFactory` is named like a factory but is not one.
