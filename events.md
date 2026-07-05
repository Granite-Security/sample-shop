# Event taxonomy & topic conventions

## Topic naming pattern

```
<domain>.<type>
```

| Domain | Type | Topic |
|---|---|---|
| orders | events | `orders.events` |
| payments | events | `payments.events` |
| shipments | events | `shipments.events` |
| delivery | events | `delivery.events` |

## Event envelope

Every event published to Kafka uses the following envelope stored as Avro in the Schema Registry:

```avro
{
  "type": "record",
  "name": "EventEnvelope",
  "namespace": "com.granitesecurity.event",
  "fields": [
    { "name": "id",            "type": "string",  "doc": "Unique event identifier (UUID)" },
    { "name": "type",          "type": "string",  "doc": "Event type name, e.g. OrderPlaced" },
    { "name": "version",       "type": "int",     "doc": "Schema version" },
    { "name": "occurredAt",    "type": "string",  "doc": "ISO-8601 instant when the event occurred" },
    { "name": "correlationId", "type": "string",  "doc": "Root-cause identifier, stable across a saga" },
    { "name": "causationId",   "type": "string",  "doc": "Identifier of the immediate cause event" },
    { "name": "data",          "type": "bytes",   "doc": "Avro-serialized payload specific to the event type" }
  ]
}
```

- **id** – UUID v7 (time-sorted) — globally unique, enables idempotent consumption.
- **type** – PascalCase matching the Avro record name (e.g. `OrderPlaced`).
- **version** – Monotonic integer; bumped on backward-incompatible changes.
- **occurredAt** – Producer wall-clock time; **not** the broker timestamp.
- **correlationId** – Set once at the start of a flow (e.g. order placement); stays constant across all downstream events.
- **causationId** – The `id` of the event that directly triggered this one; for chaining.
- **data** – The domain-specific payload bytes, serialized with the per-event Avro schema.

## Topics & event schemas

### `orders.events`

| Event | Key | Partitioning | Retention | Payload (Avro) |
|---|---|---|---|---|
| `OrderPlaced` | orderId (string) | hash(key) → partition | 7 days | [OrderPlaced](#orderplaced) |
| `OrderCancelled` | orderId (string) | hash(key) → partition | 7 days | [OrderCancelled](#ordercancelled) |

**Key** = order id (UUID string) — all events for the same order land in the same partition, preserving order.

**Retention** = 7 days (cleanup.policy=delete). Compaction is not needed because old order states are irrelevant once terminal.

#### OrderPlaced

```avro
{
  "type": "record",
  "name": "OrderPlaced",
  "namespace": "com.granitesecurity.event.order",
  "fields": [
    { "name": "orderId",    "type": "string", "doc": "Order UUID" },
    { "name": "customerId", "type": "string", "doc": "Username / customer identifier" },
    { "name": "items", "type": {
      "type": "array",
      "items": {
        "type": "record",
        "name": "OrderItem",
        "fields": [
          { "name": "productId", "type": "string" },
          { "name": "quantity",  "type": "int" },
          { "name": "unitPrice", "type": "float" }
        ]
      }
    }},
    { "name": "total",       "type": "float",  "doc": "Order grand total" },
    { "name": "orderedAt",   "type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

#### OrderCancelled

```avro
{
  "type": "record",
  "name": "OrderCancelled",
  "namespace": "com.granitesecurity.event.order",
  "fields": [
    { "name": "orderId",  "type": "string", "doc": "Order UUID" },
    { "name": "reason",   "type": "string", "doc": "Cancellation reason" },
    { "name": "cancelledAt", "type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

---

### `payments.events`

| Event | Key | Partitioning | Retention | Payload (Avro) |
|---|---|---|---|---|
| `PaymentReceived` | orderId (string) | hash(key) → partition | 7 days | [PaymentReceived](#paymentreceived) |
| `PaymentFailed` | orderId (string) | hash(key) → partition | 7 days | [PaymentFailed](#paymentfailed) |

#### PaymentReceived

```avro
{
  "type": "record",
  "name": "PaymentReceived",
  "namespace": "com.granitesecurity.event.payment",
  "fields": [
    { "name": "orderId",      "type": "string", "doc": "Order UUID" },
    { "name": "paymentId",    "type": "string", "doc": "Payment transaction UUID" },
    { "name": "amount",       "type": "float",  "doc": "Amount paid" },
    { "name": "paidAt",       "type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

#### PaymentFailed

```avro
{
  "type": "record",
  "name": "PaymentFailed",
  "namespace": "com.granitesecurity.event.payment",
  "fields": [
    { "name": "orderId",  "type": "string", "doc": "Order UUID" },
    { "name": "reason",   "type": "string", "doc": "Failure reason / error code" },
    { "name": "failedAt", "type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

---

### `shipments.events`

| Event | Key | Partitioning | Retention | Payload (Avro) |
|---|---|---|---|---|
| `ShipmentDispatched` | orderId (string) | hash(key) → partition | 7 days | [ShipmentDispatched](#shipmentdispatched) |
| `ShipmentDelivered` | orderId (string) | hash(key) → partition | 7 days | [ShipmentDelivered](#shipmentdelivered) |

#### ShipmentDispatched

```avro
{
  "type": "record",
  "name": "ShipmentDispatched",
  "namespace": "com.granitesecurity.event.shipment",
  "fields": [
    { "name": "orderId",      "type": "string", "doc": "Order UUID" },
    { "name": "shipmentId",   "type": "string", "doc": "Shipment tracking UUID" },
    { "name": "carrier",      "type": "string", "doc": "Carrier name (e.g. UPS, FedEx)" },
    { "name": "dispatchedAt", "type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

#### ShipmentDelivered

```avro
{
  "type": "record",
  "name": "ShipmentDelivered",
  "namespace": "com.granitesecurity.event.shipment",
  "fields": [
    { "name": "orderId",     "type": "string", "doc": "Order UUID" },
    { "name": "shipmentId",  "type": "string", "doc": "Shipment tracking UUID" },
    { "name": "deliveredAt", "type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

---

### `delivery.events`

| Event | Key | Partitioning | Retention | Payload (Avro) |
|---|---|---|---|---|
| `DeliveryDispatched` | orderId (string) | hash(key) → partition | 7 days | [DeliveryDispatched](#deliverydispatched) |
| `DeliveryDelivered` | orderId (string) | hash(key) → partition | 7 days | [DeliveryDelivered](#deliverydelivered) |

#### DeliveryDispatched

```avro
{
  "type": "record",
  "name": "DeliveryDispatched",
  "namespace": "com.granitesecurity.event.delivery",
  "fields": [
    { "name": "orderId",      "type": "string", "doc": "Order UUID" },
    { "name": "deliveryId",   "type": "string", "doc": "Delivery tracking UUID" },
    { "name": "status",       "type": "string", "doc": "DISPATCHED" },
    { "name": "carrier",      "type": "string", "doc": "Carrier name (e.g. UPS, FedEx)" },
    { "name": "dispatchedAt", "type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

#### DeliveryDelivered

```avro
{
  "type": "record",
  "name": "DeliveryDelivered",
  "namespace": "com.granitesecurity.event.delivery",
  "fields": [
    { "name": "orderId",    "type": "string", "doc": "Order UUID" },
    { "name": "deliveryId", "type": "string", "doc": "Delivery tracking UUID" },
    { "name": "status",     "type": "string", "doc": "DELIVERED" },
    { "name": "deliveredAt","type": "string", "doc": "ISO-8601 timestamp" }
  ]
}
```

---

## Schema Registry subjects

Subjects follow the TopicNameStrategy by default:

| Subject | Schema |
|---|---|
| `orders.events-value` | EventEnvelope |
| `payments.events-value` | EventEnvelope |
| `delivery.events-value` | EventEnvelope |

Nested `data` payloads are **not** registered independently — they are embedded bytes inside the envelope. Services documenting their wire-format in this file is sufficient until Phase 3.3 (shared events module) decides on registry-vs-codegen.

## Producer / consumer conventions

- **Producers** use `acks=all`, `enable.idempotence=true`.
- **Consumers** use `isolation.level=read_committed` to avoid reading aborted transactions.
- **Consumer groups** are named `<service>.<topic>.consumer`, e.g. `payment.orders.events.consumer`.
- **At-least-once** delivery; idempotent consumers deduplicate by event `id`.
- **Auto-created topics** use:
  - `partitions=3`
  - `replication.factor=1` (single-broker dev; adjust for production)
  - `retention.ms=604800000` (7 days)
