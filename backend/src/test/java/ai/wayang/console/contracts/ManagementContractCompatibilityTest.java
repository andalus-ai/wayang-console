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
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ManagementContractCompatibilityTest {
    private record Fixture(String resource, String goldenResource, Supplier<? extends Message.Builder> builderFactory) {}

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("management/v1/register-request.textproto", "management/v1/golden/register-request.bin.hex", RegisterRuntimeRequest::newBuilder),
            new Fixture("management/v1/register-response.textproto", "management/v1/golden/register-response.bin.hex", RegisterRuntimeResponse::newBuilder),
            new Fixture("management/v1/heartbeat-request.textproto", "management/v1/golden/heartbeat-request.bin.hex", HeartbeatRequest::newBuilder),
            new Fixture("management/v1/heartbeat-response.textproto", "management/v1/golden/heartbeat-response.bin.hex", HeartbeatResponse::newBuilder),
            new Fixture("management/v1/inventory-report-request.textproto", "management/v1/golden/inventory-report-request.bin.hex", InventoryReportRequest::newBuilder),
            new Fixture("management/v1/inventory-report-response.textproto", "management/v1/golden/inventory-report-response.bin.hex", InventoryReportResponse::newBuilder),
            new Fixture("management/v1/poll-commands-request.textproto", "management/v1/golden/poll-commands-request.bin.hex", PollCommandsRequest::newBuilder),
            new Fixture("management/v1/poll-commands-response.textproto", "management/v1/golden/poll-commands-response.bin.hex", PollCommandsResponse::newBuilder),
            new Fixture("management/v1/acknowledge-command-request.textproto", "management/v1/golden/acknowledge-command-request.bin.hex", AcknowledgeCommandRequest::newBuilder),
            new Fixture("management/v1/acknowledge-command-response.textproto", "management/v1/golden/acknowledge-command-response.bin.hex", AcknowledgeCommandResponse::newBuilder)
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

            var goldenStream = getClass().getClassLoader().getResourceAsStream(fixture.goldenResource());
            assertNotNull(goldenStream, () -> "Missing immutable wire golden: " + fixture.goldenResource());
            String goldenHex;
            try (goldenStream) {
                goldenHex = new String(goldenStream.readAllBytes(), StandardCharsets.US_ASCII).trim();
            }
            byte[] golden = HexFormat.of().parseHex(goldenHex);
            assertArrayEquals(golden, encoded,
                    () -> "Serialized bytes differ from immutable wire golden: " + fixture.resource());
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
        var descriptor = ai.wayang.console.contracts.management.v1.ManagementCommand.getDescriptor();

        assertField(descriptor, "command_id", 1, Descriptors.FieldDescriptor.Type.STRING);
        assertField(descriptor, "idempotency_key", 2, Descriptors.FieldDescriptor.Type.STRING);
        assertField(descriptor, "command_type", 3, Descriptors.FieldDescriptor.Type.STRING);
        assertField(descriptor, "typed_payload", 4, Descriptors.FieldDescriptor.Type.BYTES);
        assertField(descriptor, "expires_at", 5, Descriptors.FieldDescriptor.Type.MESSAGE);
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

    @Test
    void previousSchemaCanForwardNewSchemaFieldsWithoutLosingThem() throws Exception {
        Descriptors.Descriptor previous = compatibilityDescriptor(false);
        Descriptors.Descriptor current = compatibilityDescriptor(true);

        DynamicMessage currentMessage = DynamicMessage.newBuilder(current)
                .setField(current.findFieldByName("name"), "runtime-01")
                .setField(current.findFieldByName("future_value"), "new-runtime-feature")
                .build();

        DynamicMessage previousReader = DynamicMessage.parseFrom(previous, currentMessage.toByteArray());
        assertEquals("runtime-01", previousReader.getField(previous.findFieldByName("name")));
        assertTrue(previousReader.getUnknownFields().hasField(99));

        DynamicMessage currentAfterForwarding =
                DynamicMessage.parseFrom(current, previousReader.toByteArray());
        assertEquals("new-runtime-feature",
                currentAfterForwarding.getField(current.findFieldByName("future_value")));
    }

    private static Descriptors.Descriptor compatibilityDescriptor(boolean includeAdditiveField) throws Exception {
        var nameField = DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName("name")
                .setNumber(1)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                .build();
        var message = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("CompatibilityEnvelope")
                .addField(nameField);
        if (includeAdditiveField) {
            message.addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("future_value")
                    .setNumber(99)
                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
        }
        var file = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("compatibility-envelope.proto")
                .setPackage("wayang.management.compatibility.test")
                .setSyntax("proto3")
                .addMessageType(message)
                .build();
        return Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0])
                .findMessageTypeByName("CompatibilityEnvelope");
    }

    private static void assertField(Descriptors.Descriptor descriptor,
                                    String name,
                                    int number,
                                    Descriptors.FieldDescriptor.Type type) {
        var field = descriptor.findFieldByName(name);
        assertNotNull(field, () -> "Missing field " + name);
        assertEquals(number, field.getNumber(), "Field number changed for " + name);
        assertEquals(type, field.getType(), "Wire type changed for " + name);
    }
}
