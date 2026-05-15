package dev.arcp.client;

import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.ids.ResultId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * §8.4 result-chunk reassembly property: for any partition of a byte array
 * into chunks emitted in any arrival order, the reassembled output equals
 * the original concatenation.
 */
class ResultStreamPropertyTest {

    @Property
    boolean reassemblesAnyChunkPermutation(
            @ForAll("payloads") byte[] payload,
            @ForAll("chunkCounts") int chunkCount,
            @ForAll long shuffleSeed) throws Exception {
        if (payload.length == 0) {
            return true; // ResultStream requires at least one chunk
        }
        final int n = Math.max(1, Math.min(chunkCount, payload.length));
        ResultId id = ResultId.of("res_prop");

        // Partition payload into `n` non-empty contiguous chunks of as-even
        // a size as possible; the last chunk takes the remainder.
        int base = payload.length / n;
        int leftover = payload.length % n;
        List<ResultChunkEvent> chunks = new ArrayList<>(n);
        int cursor = 0;
        for (int seq = 0; seq < n; seq++) {
            int size = base + (seq < leftover ? 1 : 0);
            byte[] slice = java.util.Arrays.copyOfRange(payload, cursor, cursor + size);
            cursor += size;
            chunks.add(new ResultChunkEvent(
                    id, seq, Base64.getEncoder().encodeToString(slice),
                    "base64", seq < n - 1));
        }

        // Shuffle arrival order deterministically.
        List<ResultChunkEvent> arrival = new ArrayList<>(chunks);
        Collections.shuffle(arrival, new java.util.Random(shuffleSeed));

        ResultStream stream = ResultStream.toMemory(id);
        for (ResultChunkEvent c : arrival) {
            stream.accept(c);
        }
        if (!stream.isComplete()) {
            return false;
        }
        return java.util.Arrays.equals(stream.bytes(), payload);
    }

    @Provide
    Arbitrary<byte[]> payloads() {
        return Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(2048)
                .map(s -> s.getBytes(StandardCharsets.UTF_8));
    }

    @Provide
    Arbitrary<Integer> chunkCounts() {
        return Arbitraries.integers().between(1, 16);
    }
}
