package dev.arcp.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExtensionNamespaceTest {

	@Test
	void arcpxFormAccepted() {
		assertThat(ExtensionNamespace.isValid("arcpx.example.demo.v1")).isTrue();
		assertThat(ExtensionNamespace.isValid("arcpx.acme.toolkit.v12")).isTrue();
	}

	@Test
	void reverseDnsAccepted() {
		assertThat(ExtensionNamespace.isValid("com.example.foo.v1")).isTrue();
		assertThat(ExtensionNamespace.isValid("io.example.bar-baz.v3")).isTrue();
	}

	@Test
	void invalidFormsRejected() {
		assertThat(ExtensionNamespace.isValid("example.v1")).isFalse(); // only one label
		assertThat(ExtensionNamespace.isValid("arcpx.example.demo")).isFalse(); // missing version
		assertThat(ExtensionNamespace.isValid("arcpx.example.demo.v")).isFalse();
		assertThat(ExtensionNamespace.isValid("Arcpx.example.demo.v1")).isFalse();
		assertThat(ExtensionNamespace.isValid("")).isFalse();
		assertThat(ExtensionNamespace.isValid(null)).isFalse();
	}

	@Test
	void requireThrowsOnInvalid() {
		assertThatThrownBy(() -> ExtensionNamespace.require("bad")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bad");
	}

	@Test
	void registryTracksAdvertisedNamespaces() {
		ExtensionRegistry reg = new ExtensionRegistry();
		reg.register("arcpx.example.demo.v1");
		assertThat(reg.isAdvertised("arcpx.example.demo.v1")).isTrue();
		assertThat(reg.isAdvertised("arcpx.example.demo.v2")).isFalse();
		assertThat(reg.snapshot()).containsExactly("arcpx.example.demo.v1");

		reg.unregister("arcpx.example.demo.v1");
		assertThat(reg.isAdvertised("arcpx.example.demo.v1")).isFalse();
	}

	@Test
	void registryRejectsMalformedNamespace() {
		ExtensionRegistry reg = new ExtensionRegistry();
		assertThatThrownBy(() -> reg.register("bad")).isInstanceOf(IllegalArgumentException.class);
	}
}
