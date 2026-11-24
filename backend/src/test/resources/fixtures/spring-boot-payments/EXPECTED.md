# spring-boot-payments: expected impact analysis

Evaluation fixture for MVP 5 (the plan's `PaymentService.calculatePayment()` example). `ImpactE2eTests` and
`ImpactAiE2eTests` assert exactly this; change both when the fixture changes.

## Graph facts

- `PaymentController.pay`, `OrderService.checkout` and the second overload `PaymentService.calculatePayment(Order, String)`
  call `PaymentService.calculatePayment(Order)`; `OrderController.place` and `BillingJob.runNightly` call `OrderService.checkout`.
- `PaymentService.calculatePayment(Order)` calls `DiscountPolicy.apply` (interface, implemented by `LoyaltyDiscount`) and `PaymentRepository.save`.
- Endpoints: `POST /payments` (`pay`), `POST /orders` (`place`).
- Entity `Payment`, repository `PaymentRepository`.

## Impact of `PaymentService.calculatePayment(Order)` (depth 3)

| Affected | Depth | Confidence |
|---|---|---|
| `PaymentController.pay`, `OrderService.checkout`, `PaymentService.calculatePayment(Order, String)` | 1 | DETECTED |
| `OrderController.place`, `BillingJob.runNightly` | 2 | DETECTED |

Endpoints: `POST /payments`, `POST /orders`. Related: `PaymentRepository` (used), `Payment` (entity it manages).
Tests: `PaymentServiceTests` (DIRECT_CALL, methods `computesTheTotal`), `PaymentRefundTests` (TYPE_REFERENCE, possible),
`OrderControllerTests` (calls `OrderController.place`, an affected component).

## Impact of `LoyaltyDiscount.apply(Order)`

Dynamic dispatch: callers of `DiscountPolicy.apply` (`PaymentService.calculatePayment(Order)`) are POSSIBLE, and so are
their own callers.
