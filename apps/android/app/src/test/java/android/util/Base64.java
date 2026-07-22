// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package android.util;

/**
 * Host-JVM stand-in for {@code android.util.Base64}, present ONLY on the unit-test
 * classpath (src/test). The AGP "mockable" android.jar stubs every platform method
 * to throw "not mocked"; this real implementation lets host tests drive production
 * code that touches base64 — e.g. the PR-D2a SignalProtocolManager counter test,
 * whose assertions never depend on the encoded value, only on the id/timestamp
 * counters. Test classes load BEFORE the mockable jar on the unit-test runtime
 * classpath, so this shadows the stub without affecting the shipped app (which
 * always uses the device's real android.util.Base64).
 *
 * Implements the flag semantics the app uses: {@link #NO_WRAP} (standard alphabet,
 * padded, single line) maps to {@link java.util.Base64}'s Basic encoder/decoder —
 * byte-for-byte identical output for the app's callers.
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;

    private Base64() {}

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] encode(byte[] input, int flags) {
        java.util.Base64.Encoder encoder =
                ((flags & URL_SAFE) != 0)
                        ? java.util.Base64.getUrlEncoder()
                        : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) {
            encoder = encoder.withoutPadding();
        }
        return encoder.encode(input);
    }

    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(java.nio.charset.StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        java.util.Base64.Decoder decoder =
                ((flags & URL_SAFE) != 0)
                        ? java.util.Base64.getUrlDecoder()
                        : java.util.Base64.getDecoder();
        return decoder.decode(input);
    }
}
