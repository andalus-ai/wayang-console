package ai.wayang.console.application;
import ai.wayang.console.adapter.persistence.*;
import jakarta.enterprise.context.ApplicationScoped; import jakarta.inject.Inject; import jakarta.persistence.*; import jakarta.transaction.Transactional;
import jakarta.ws.rs.*; import java.time.Instant; import java.util.UUID;
@ApplicationScoped
public class PluginApprovalService {
 @Inject EntityManager em; @Inject AuditIntegrityService audit; @Inject ArtifactRegistryService artifacts;
 public record Decision(String action,String reason) {}
 public record ApprovalView(UUID catalogEntryId,String status,String requestedBy,Instant requestedAt,String decidedBy,Instant decidedAt,String reason,long revision) {}
 @Transactional public ApprovalView request(UUID org,UUID env,String plugin,String version,String actor) {
  PluginCatalogEntryEntity entry=find(org,env,plugin,version); if(entry==null)throw new NotFoundException("Catalog entry not found");
  PluginCatalogGovernanceEntity governance=PluginCatalogGovernanceEntity.find("catalogEntryId",entry.id).firstResult();
  if(governance!=null && "REVOKED".equals(governance.lifecycleStatus))throw new WebApplicationException("Revoked catalog entry cannot be submitted for approval", 409);
  PluginApprovalEntity current=PluginApprovalEntity.find("catalogEntryId",entry.id).firstResult();
  if(current!=null){if("PENDING".equals(current.approvalStatus))return view(current);throw new WebApplicationException("Approval already exists; use a new catalog version for resubmission", 409);}
  Instant now=Instant.now(); PluginApprovalEntity state=new PluginApprovalEntity(); state.id=UUID.randomUUID();state.catalogEntryId=entry.id;state.organizationId=org;state.environmentId=env;state.approvalStatus="PENDING";state.requestedBy=actor;state.requestedAt=now;state.persist();
  event(entry,org,env,null,"PENDING",actor,"Submitted for distribution approval",now);
  audit.append(org,env,actor,"PLUGIN_APPROVAL_REQUESTED","PLUGIN_CATALOG_ENTRY",entry.id.toString(),now,"SUCCESS");return view(state);
 }
 @Transactional public ApprovalView decide(UUID org,UUID env,String plugin,String version,String actor,Decision decision) {
  if(decision==null||decision.action()==null)throw new BadRequestException("Decision action required");
  String reason=decision.reason()==null?"":decision.reason().trim();if(reason.isEmpty()||reason.length()>1000)throw new BadRequestException("Decision reason is required (max 1000)");
  PluginCatalogEntryEntity entry=find(org,env,plugin,version);if(entry==null)throw new NotFoundException("Catalog entry not found");
  entry=em.find(PluginCatalogEntryEntity.class,entry.id,LockModeType.PESSIMISTIC_WRITE);
  PluginApprovalEntity state=PluginApprovalEntity.find("catalogEntryId",entry.id).firstResult();if(state==null)throw new WebApplicationException("Approval has not been requested", 409);
  state=em.find(PluginApprovalEntity.class,state.id,LockModeType.PESSIMISTIC_WRITE);
  if(!"PENDING".equals(state.approvalStatus))throw new WebApplicationException("Approval decision is final for this catalog version", 409);
  if(actor.equals(state.requestedBy))throw new ForbiddenException("Publisher cannot approve or reject its own submission");
  String action=decision.action().toUpperCase(java.util.Locale.ROOT);if(!action.equals("APPROVE")&&!action.equals("REJECT"))throw new BadRequestException("action must be APPROVE or REJECT");
  if(action.equals("APPROVE")){
   PluginCatalogGovernanceEntity gov=PluginCatalogGovernanceEntity.find("catalogEntryId",entry.id).firstResult();
   if(gov!=null&&!"ACTIVE".equals(gov.lifecycleStatus))throw new WebApplicationException("Only ACTIVE catalog entries may be approved", 409);
   artifacts.assertRegistered(org,env,entry.manifestDigest,entry.manifestDigest);
  }
  Instant now=Instant.now();String status=action.equals("APPROVE")?"APPROVED":"REJECTED";
  state.approvalStatus=status;state.decidedBy=actor;state.decidedAt=now;state.reason=reason;
  event(entry,org,env,"PENDING",status,actor,reason,now);
  audit.append(org,env,actor,"PLUGIN_"+status,"PLUGIN_CATALOG_ENTRY",entry.id.toString(),now,"SUCCESS");return view(state);
 }
 @Transactional public ApprovalView get(UUID org,UUID env,String plugin,String version){PluginCatalogEntryEntity e=find(org,env,plugin,version);if(e==null)throw new NotFoundException("Catalog entry not found");PluginApprovalEntity a=PluginApprovalEntity.find("catalogEntryId",e.id).firstResult();if(a==null)throw new NotFoundException("Approval not requested");return view(a);}
 private PluginCatalogEntryEntity find(UUID org,UUID env,String plugin,String version){return PluginCatalogEntryEntity.find("organizationId = ?1 and environmentId = ?2 and pluginId = ?3 and pluginVersion = ?4",org,env,plugin,version).firstResult();}
 private void event(PluginCatalogEntryEntity e,UUID org,UUID env,String from,String to,String actor,String reason,Instant at){PluginApprovalEventEntity x=new PluginApprovalEventEntity();x.id=UUID.randomUUID();x.catalogEntryId=e.id;x.organizationId=org;x.environmentId=env;x.fromStatus=from;x.toStatus=to;x.actorSubject=actor;x.reason=reason;x.occurredAt=at;x.persist();}
 private ApprovalView view(PluginApprovalEntity a){return new ApprovalView(a.catalogEntryId,a.approvalStatus,a.requestedBy,a.requestedAt,a.decidedBy,a.decidedAt,a.reason,a.rowVersion);}
}