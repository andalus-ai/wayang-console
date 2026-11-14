package ai.wayang.console.contracts;

import ai.wayang.console.contracts.management.v1.AcknowledgeCommandRequest;
import ai.wayang.console.contracts.management.v1.AcknowledgeCommandResponse;
import ai.wayang.console.contracts.management.v1.HeartbeatRequest;
import ai.wayang.console.contracts.management.v1.HeartbeatResponse;
import ai.wayang.console.contracts.management.v1.InventoryReportRequest;
import ai.wayang.console.contracts.management.v1.InventoryReportResponse;
import ai.wayang.console.contracts.management.v1.PollCommandsRequest;
import ai.wayang.console.contracts.management.v1.PollCommandsResponse;
import ai.wayang.console.contracts.management.v1.RegisterRuntimeRequest;
import ai.wayang.console.contracts.management.v1.RegisterRuntimeResponse;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ManagementContractCompatibilityTest {
    private record Fixture(String resource, Supplier<? extends Message.Builder> builderFactory) {}

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("management/v1/register-request.textproto", RegisterRuntimeRequest::newBuilder),
            new Fixture("management/v1/register-response.textproto", RegisterRuntimeResponse::newBuilder),
            new Fixture("management/v1/heartbeat-request.textproto", HeartbeatRequest::newBuilder),
            new Fixture("management/v1/heartbeat-response.textproto", HeartbeatResponse::newBuilder),
            new Fixture("management/v1/inventory-report-request.textproto", InventoryReportRequest::newBuilder),
            new Fixture("management/v1/inventory-report-response.textproto", InventoryReportResponse::newBuilder),
            new Fixture("management/v1/poll-commands-request.textproto", PollCommandsRequest::newBuilder),
            new Fixture("management/v1/poll-commands-response.textproto", PollCommandsResponse::newBuilder),
            new Fixture("management/v1/acknowledge-command-request.textproto", AcknowledgeCommandRequest::newBuilder),
            new Fixture("management/v1/acknowledge-command-response.textproto", AcknowledgeCommandResponse::newBuilder)
    );

    @Test
    void allRpcRequestAndResponseFixturesParseAndRoundTripAsProtobufBinary() throws IOException {
        assertEquals(10, FIXTURES.size());
        for (Fixture fixture : FIXTURES) {
            var stream = getClass().getClassLoader().getResourceAsStream(fixture.resource());
            assertNotNull(stream, () -> "Missing contract fixture: " + fixture.resource());

            Message.Builder builder = fixture.builderFactory().get();
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                TextFormat.getParser().merge(reader, builder);
            }

            Message parsed = builder.build();
            byte[] encoded = parsed.toByteArray();
            Message decoded = DynamicMessage.parseFrom(parsed.getDescriptorForType(), encoded);
            assertArrayEquals(encoded, decoded.toByteArray(),
                    () -> "Protobuf binary round-trip changed fixture: " + fixture.resource());
        }
    }

    @Test
    void managementServiceRetainsTheFiveExpectedRpcMethods() {
        var file = RegisterRuntimeRequest.getDescriptor().getFile();
        var service = file.findServiceByName("RuntimeManagement");
        assertNotNull(service);
        assertEquals(List.of("Register", "Heartbeat", "ReportInventory", "PollCommands", "AcknowledgeCommand"),
                service.getMethods().stream().map(method -> method.getName()).toList());
    }

    @Test
    void managementCommandFieldNumbersAndWireTypesRemainStable() {
        var descriptor = PollCommandsResponse.getDescriptor()
                .findNestedTypeByName("ManagementCommand");
        // ManagementCommand is a top-level message in this schema.
        descriptor = ai.wayang.console.contracts.management.v1.ManagementCommand.getDescriptor();

        assertField(descriptor, "command_id", 1, com.google.protobuf.Descriptors.FieldDescriptor.Type.STRING);
        assertField(descriptor, "idempotency_key", 2, com.google.protobuf.Descriptors.FieldDescriptor.Type.STRING);
        assertField(descriptor, "command_type", 3, com.google.protobuf.Descriptors.FieldDescriptor.Type.STRING);
        assertField(descriptor, "typed_payload", 4, com.google.protobuf.Descriptors.FieldDescriptor.Type.BYTES);
        assertField(descriptor, "expires_at", 5, com.google.protobuf.Descriptors.FieldDescriptor.Type.MESSAGE);
    }

    @Test
    void olderReaderPreservesUnknownAdditiveFields() throws IOException {
        var descriptor = RegisterRuntimeRequest.getDescriptor();
        byte[] baseline = RegisterRuntimeRequest.newBuilder()
                .setHostname("fixture-host")
                .setPlatform("linux-amd64")
                .build().toByteArray();

        byte[] unknownField = new byte[16];
        CodedOutputStream output = CodedOutputStream.newInstance(unknownField);
        output.writeUInt64(99, 7L);
        output.flush();
        int used = output.getTotalBytesWritten();
        byte[] withAdditiveField = new byte[baseline.length + used];
        System.arraycopy(baseline, 0, withAdditiveField, 0, baseline.length);
        System.arraycopy(unknownField, 0, withAdditiveField, baseline.length, used);

        DynamicMessage olderReader = DynamicMessage.parseFrom(descriptor, withAdditiveField);
        assertTrue(olderReader.getUnknownFields().hasField(99));

        byte[] forwarded = olderReader.toByteArray();
        DynamicMessage forwardedAgain = DynamicMessage.parseFrom(descriptor, forwarded);
        assertTrue(forwardedAgain.getUnknownFields().hasField(99));
        assertArrayEquals(forwarded, forwardedAgain.toByteArray());
    }

    private static void assertField(com.google.protobuf.Descriptors.Descriptor descriptor,
                                    String name,
                                    int number,
                                    com.google.protobuf.Descriptors.FieldDescriptor.Type type) {
        var field = descriptor.findFieldByName(name);
        assertNotNull(field, () -> "Missing field " + name);
        assertEquals(number, field.getNumber(), "Field number changed for " + name);
        assertEquals(type, field.getType(), "Wire type changed for " + name);
    }
}
