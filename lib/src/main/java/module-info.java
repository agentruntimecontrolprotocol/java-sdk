/**
 * ARCP — Java reference implementation of the Agent Runtime Control Protocol
 * (RFC 0001 v2). Public surface mirrors the package layout described in
 * {@code PLAN.md} §6.
 */
module arcp {
	requires transitive com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.annotation;
	requires com.fasterxml.jackson.datatype.jsr310;
	requires transitive org.slf4j;
	requires transitive org.jspecify;
	requires java.sql;
	requires com.github.f4b6a3.ulid;

	exports dev.arcp;
	exports dev.arcp.envelope;
	exports dev.arcp.ids;
	exports dev.arcp.error;
	exports dev.arcp.extensions;
	exports dev.arcp.messages.control;

	// Jackson reflective deserialization of envelope and message records.
	opens dev.arcp.envelope to com.fasterxml.jackson.databind;
	opens dev.arcp.ids to com.fasterxml.jackson.databind;
	opens dev.arcp.error to com.fasterxml.jackson.databind;
	opens dev.arcp.messages.control to com.fasterxml.jackson.databind;
}
