package dev.arcp.runtime.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.messages.JobSubmit;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * §7.2 idempotency fingerprint: a collision-resistant SHA-256 over the semantically meaningful
 * {@link JobSubmit} fields (agent, input, lease_request, lease_constraints, max_runtime_sec).
 *
 * <p>The serialization is canonical — both {@link java.util.Map} entries and {@code ObjectNode}
 * properties are sorted — so two payloads that differ only in JSON object key order hash identically
 * (#91).
 */
public final class IdempotencyFingerprint {

  private IdempotencyFingerprint() {}

  public static String of(ObjectMapper mapper, JobSubmit submit) {
    try {
      ObjectMapper canonical = mapper.copy();
      canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
      canonical.configure(JsonNodeFeature.WRITE_PROPERTIES_SORTED, true);
      canonical.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
      ObjectNode canon = canonical.createObjectNode();
      canon.put("agent", submit.agent().wire());
      canon.set("input", canonical.valueToTree(submit.input()));
      if (submit.leaseRequest() != null) {
        canon.set("lease_request", canonical.valueToTree(submit.leaseRequest()));
      }
      if (submit.leaseConstraints() != null) {
        canon.set("lease_constraints", canonical.valueToTree(submit.leaseConstraints()));
      }
      if (submit.maxRuntimeSec() != null) {
        canon.put("max_runtime_sec", submit.maxRuntimeSec());
      }
      byte[] bytes = canonical.writerWithDefaultPrettyPrinter().writeValueAsBytes(canon);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("idempotency fingerprint failure", e);
    }
  }
}
