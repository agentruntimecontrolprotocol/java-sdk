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
	requires com.nimbusds.jose.jwt;

	exports dev.arcp;
	exports dev.arcp.envelope;
	exports dev.arcp.ids;
	exports dev.arcp.error;
	exports dev.arcp.extensions;
	exports dev.arcp.capability;
	exports dev.arcp.auth;
	exports dev.arcp.transport;
	exports dev.arcp.runtime;
	exports dev.arcp.client;
	exports dev.arcp.messages.control;
	exports dev.arcp.messages.session;

	// Jackson reflective deserialization of envelope and message records.
	opens dev.arcp.envelope to com.fasterxml.jackson.databind;
	opens dev.arcp.ids to com.fasterxml.jackson.databind;
	opens dev.arcp.error to com.fasterxml.jackson.databind;
	opens dev.arcp.auth to com.fasterxml.jackson.databind;
	opens dev.arcp.capability to com.fasterxml.jackson.databind;
	opens dev.arcp.messages.control to com.fasterxml.jackson.databind;
	opens dev.arcp.messages.session to com.fasterxml.jackson.databind;
}
