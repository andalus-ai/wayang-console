# Runtime management contract fixtures

These are synthetic protobuf TextFormat characterization inputs aligned to the current schema at contracts/proto/wayang/management/v1/management.proto.

They are not serialized protobuf wire bytes and do not, by themselves, prove compatibility with a separately deployed runtime. Their role is to make field-level examples reviewable and feed the executable parser/serialization checks in ManagementContractCompatibilityTest.

## Coverage

| RPC | Request | Response |
|---|---|---|
| Register | register-request.textproto | register-response.textproto |
| Heartbeat | heartbeat-request.textproto | heartbeat-response.textproto |
| ReportInventory | inventory-report-request.textproto | inventory-report-response.textproto |
| PollCommands | poll-commands-request.textproto | poll-commands-response.textproto |
| AcknowledgeCommand | acknowledge-command-request.textproto | acknowledge-command-response.textproto |

## Fixture rules

- Values are deterministic examples, not credentials or production identifiers.
- Timestamps use explicit UTC seconds/nanos.
- The PollCommands response's typed_payload is a protobuf bytes field represented by a TextFormat string literal.
- Do not renumber fields, change field wire types, or reuse removed field numbers in the current schema.
- Additive fields must be tested for old-reader behavior using generated descriptors.
- Keep fixtures independent of Console database entities and REST response DTOs.

## Executable checks in this slice

The backend pins protoc and the Java protobuf runtime to the same version, compiles the schemas from contracts/proto, and runs a focused compatibility test that:
1. Parses all ten fixtures into generated message builders.
2. Serializes each parsed message to protobuf binary and verifies a DynamicMessage parse/serialize round-trip.
3. Asserts the five RuntimeManagement RPC method names.
4. Asserts ManagementCommand field numbers and protobuf types.
5. Appends an unknown field to a message and verifies an older descriptor preserves it through parse/serialize.

The round-trip checks are executable serialization checks, not fixed historical binary goldens. A future slice should check in immutable binary goldens and test cross-version descriptors plus the actual Wayang runtime and Console adapters.
