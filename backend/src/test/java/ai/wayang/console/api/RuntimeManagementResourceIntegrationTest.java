package ai.wayang.console.api;

import ai.wayang.console.application.RuntimeRegistryService;
import ai.wayang.console.testsupport.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class RuntimeManagementResourceIntegrationTest {
    @Inject RuntimeRegistryService registry;

    @Test
    void enrollmentIssuesBearerCredentialAndRejectsCredentialReuse() {
        var enrollment = registry.issueEnrollmentToken(
                UUID.randomUUID(), UUID.randomUUID(), Duration.ofHours(1));

        var response = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "enrollmentToken", enrollment.token(),
                        "instanceId", "rest-contract-" + UUID.randomUUID(),
                        "runtimeVersion", "1.2.3",
                        "deploymentId", "deployment-rest",
                        "hostname", "runtime-host",
                        "platform", "linux-amd64"))
                .when().post("/api/v1/runtime/enroll")
                .then().statusCode(200)
                .body("registrationId", not(isEmptyOrNullString()))
                .body("registrationToken", not(isEmptyOrNullString()))
                .body("registrationExpiresAt", notNullValue())
                .body("leaseExpiresAt", notNullValue())
                .body("heartbeatIntervalSeconds", equalTo(30))
                .extract().response();

        String registrationToken = response.jsonPath().getString("registrationToken");
        assertNotNull(registrationToken);
        assertFalse(registrationToken.isBlank());

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "enrollmentToken", enrollment.token(),
                        "instanceId", "rest-contract-replay",
                        "runtimeVersion", "1.2.3"))
                .when().post("/api/v1/runtime/enroll")
                .then().statusCode(401)
                .body("code", equalTo("invalid_enrollment"));
    }

    @Test
    void heartbeatRequiresBearerCredentialAndRenewsLease() {
        String token = enrolledToken();

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("health", "HEALTHY"))
                .when().post("/api/v1/runtime/heartbeat")
                .then().statusCode(401)
                .body("code", equalTo("unauthorized"));

        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(Map.of("health", "HEALTHY"))
                .when().post("/api/v1/runtime/heartbeat")
                .then().statusCode(200)
                .body("leaseExpiresAt", notNullValue())
                .body("heartbeatIntervalSeconds", equalTo(30));
    }

    @Test
    void inventoryRequiresBearerCredentialAndReturnsReplacementRevision() {
        String token = enrolledToken();
        Map<String, Object> inventory = Map.of(
                "capabilities", List.of(Map.of(
                        "componentId", "wayang.runtime",
                        "version", "1.2.3",
                        "capability", "workflow.execute")),
                "workflows", List.of());

        given()
                .contentType(ContentType.JSON)
                .body(inventory)
                .when().post("/api/v1/runtime/inventory")
                .then().statusCode(401)
                .body("code", equalTo("unauthorized"));

        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(inventory)
                .when().post("/api/v1/runtime/inventory")
                .then().statusCode(200)
                .body("revision", not(isEmptyOrNullString()))
                .body("capabilityCount", equalTo(1))
                .body("workflowCount", equalTo(0))
                .body("reportedAt", notNullValue());
    }

    @Test
    void commandPollingAndAcknowledgementRequireRuntimeCredential() {
        String token = enrolledToken();

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("limit", 5))
                .when().post("/api/v1/runtime/commands/poll")
                .then().statusCode(401)
                .body("code", equalTo("unauthorized"));

        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(Map.of("limit", 5))
                .when().post("/api/v1/runtime/commands/poll")
                .then().statusCode(200)
                .body("commands", hasSize(0));

        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(Map.of("status", "SUCCEEDED", "message", "not found"))
                .when().post("/api/v1/runtime/commands/" + UUID.randomUUID() + "/ack")
                .then().statusCode(404)
                .body("code", equalTo("not_found"));
    }

    private String enrolledToken() {
        var enrollment = registry.issueEnrollmentToken(
                UUID.randomUUID(), UUID.randomUUID(), Duration.ofHours(1));
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "enrollmentToken", enrollment.token(),
                        "instanceId", "rest-contract-" + UUID.randomUUID(),
                        "runtimeVersion", "1.2.3",
                        "deploymentId", "deployment-rest",
                        "hostname", "runtime-host",
                        "platform", "linux-amd64"))
                .when().post("/api/v1/runtime/enroll")
                .then().statusCode(200)
                .extract().jsonPath().getString("registrationToken");
    }
}
