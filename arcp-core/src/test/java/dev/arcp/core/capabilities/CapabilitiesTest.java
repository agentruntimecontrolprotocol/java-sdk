package dev.arcp.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilitiesTest {

  @Test
  void emptyFeatureSet_doesNotThrow() {
    Capabilities c = Capabilities.of(Set.of());
    assertTrue(c.features().isEmpty());
    assertTrue(c.featuresWire().isEmpty());
  }

  @Test
  void compactConstructorCopiesCollections() {
    EnumSet<Feature> mutable = EnumSet.of(Feature.HEARTBEAT);
    Capabilities c = Capabilities.of(mutable);
    mutable.add(Feature.ACK);
    assertEquals(1, c.features().size());
  }
}
