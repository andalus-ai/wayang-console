package ai.wayang.console.adapter.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="wc_artifact", indexes=@Index(name="idx_wc_artifact_scope_digest",columnList="organization_id, environment_id, artifact_digest"),
 uniqueConstraints=@UniqueConstraint(name="uk_wc_artifact_scope_digest",columnNames={"organization_id","environment_id","artifact_digest"}))
public class ArtifactEntity extends PanacheEntityBase {
 @Id public UUID id;
 @Column(name="organization_id",nullable=false) public UUID organizationId;
 @Column(name="environment_id",nullable=false) public UUID environmentId;
 @Column(name="artifact_digest",nullable=false,length=71) public String artifactDigest;
 @Column(name="manifest_digest",nullable=false,length=71) public String manifestDigest;
 @Column(name="object_key",nullable=false,length=500) public String objectKey;
 @Column(name="size_bytes",nullable=false) public long sizeBytes;
 @Column(name="signature",nullable=false,length=512) public String signature;
 @Column(name="signing_key_id",nullable=false,length=120) public String signingKeyId;
 @Column(name="uploaded_by",nullable=false,length=255) public String uploadedBy;
 @Column(name="created_at",nullable=false) public Instant createdAt;
}