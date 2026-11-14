# Runtime management contract fixtures

These are synthetic protobuf TextFormat characterization inputs aligned to the current schema at contracts/proto/wayang/management/v1/management.proto.

They are not serialized protobuf wire bytes and do not, by themselves, prove binary compatibility. Their purpose in this first M7 slice is to make intended field-level examples reviewable and stable while the Core contract module and actual runtime consumer are established.

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
- Do not renumber fields, change field wire types, or reuse removed field numbers in the current schema.
- Additive fields must be tested for old-reader behavior once generated descriptors and a runnable compatibility test harness are available.
- Keep fixtures independent of Console database entities and REST response DTOs.

## Promotion to executable golden tests

The next test-harness slice must:
1. Compile the schema with the exact protobuf toolchain used by both consumers.
2. Parse each TextFormat fixture into its generated message.
3. Serialize to binary and compare against checked-in binary goldens (or a stable SHA-256 manifest).
4. Parse old goldens with the new descriptor and verify unknown additive fields are preserved by the protobuf runtime where applicable.
5. Exercise actual runtime and Console adapters, not only generated message classes.

Do not describe this directory as wire-compatibility proof until those tests run in CI.
