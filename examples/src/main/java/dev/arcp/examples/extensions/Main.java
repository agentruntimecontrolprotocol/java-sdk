package dev.arcp.examples.extensions;

import dev.arcp.client.ARCPClient;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SDR domain via custom {@code arcpx.sdr.*.v1} extension messages.
 *
 * <p>
 * Tune to 145.500 MHz (2 m FM calling), capture 5 s of IQ at 2.048 MS/s,
 * NBFM-demodulate to 48 kHz PCM. Exercises §21 naming, capability
 * advertisement, and unknown-message handling.
 */
public final class Main {

	static final String EXT_TUNE = "arcpx.sdr.tune.v1";
	static final String EXT_GAIN = "arcpx.sdr.gain.v1";
	static final String EXT_CAPTURE = "arcpx.sdr.capture.v1";
	static final String EXT_DEMODULATE = "arcpx.sdr.demodulate.v1";
	static final List<String> ALL_EXTENSIONS = List.of(EXT_TUNE, EXT_GAIN, EXT_CAPTURE, EXT_DEMODULATE);

	private Main() {
	}

	public static void main(String[] args) {
		// capabilities.extensions=ALL_EXTENSIONS on the open call.
		ARCPClient client = null;
		// SessionAccepted accepted = client.open();
		Set<String> advertised = Set.of(); // accepted.capabilities().extensions()
		if (!advertised.containsAll(ALL_EXTENSIONS)) {
			throw new ARCPException(ErrorCode.UNIMPLEMENTED, "runtime missing SDR extensions: " + advertised);
		}

		String handle = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

		// client.request(client.envelope(EXT_TUNE, payload=Map.of(
		// "center_freq_hz", 145_500_000.0,
		// "sample_rate_hz", 2_048_000.0,
		// "ppm_correction", 1)), 10s);
		// client.request(client.envelope(EXT_GAIN, payload=Map.of(
		// "stages", List.of(Map.of("name", "TUNER", "value_db", 28.0)))), 10s);

		// Capture returns an artifact.ref pointing at the IQ buffer. The
		// buffer never travels inline — demodulate references it.
		String iqArtifact = "art_iq_" + handle;
		// Envelope cap = client.request(client.envelope(EXT_CAPTURE, payload=Map.of(
		// "seconds", 5.0, "capture_handle", handle, "decimate", 1)), 15s);
		// String iqArtifact = (String) cap.payload().get("artifact_id");
		System.out.println("captured IQ → " + iqArtifact);

		// Envelope audio = client.request(client.envelope(EXT_DEMODULATE,
		// payload=Map.of(
		// "iq_artifact_id", iqArtifact, "mode", "NBFM", "audio_rate_hz", 48_000)),
		// 15s);
		// System.out.println("demod PCM → " + audio.payload().get("artifact_id"));

		// §21.3 demonstration: unadvertised extension marked optional.
		// Runtime SHOULD ack (silent drop) rather than nack.
		// Envelope optional = client.request(client.envelope(
		// "arcpx.sdr.experimental_doppler.v1",
		// extensions=Map.of("optional", Boolean.TRUE),
		// payload=Map.of("velocity_mps", 7.4)), 5s);
		// System.out.println("optional unknown → " + optional.type());
		if (client == null) {
			// unreachable: silences unused-var lint when SDK wiring elided
			throw new UnsupportedOperationException("client elided");
		}
		// client.close();
	}
}
