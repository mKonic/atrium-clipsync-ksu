package dev.atrium.link;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// The link's primitives, the same as atrium's crypto.cpp; both test suites
// pin the same known answers. X25519 is done here (RFC 7748's ladder over
// BigInteger) rather than through a provider: which XDH spellings Conscrypt
// takes differs by release, and only one-off pairing keys go through it.
final class Crypto {
    static final int TAG = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // RFC 5869, SHA-256.
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] prk = hmac(salt.length == 0 ? new byte[32] : salt, ikm);
        byte[] out = new byte[length], t = new byte[0];
        int at = 0;
        for (int i = 1; at < length; i++) {
            byte[] in = cat(t, info, new byte[] {(byte) i});
            t = hmac(prk, in);
            int n = Math.min(t.length, length - at);
            System.arraycopy(t, 0, out, at, n);
            at += n;
        }
        return out;
    }

    // --- X25519 ----------------------------------------------------------------

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    private static BigInteger decodeLe(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++)
            be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    private static byte[] encodeLe(BigInteger v) {
        byte[] be = v.toByteArray(), out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++)
            out[i] = be[be.length - 1 - i];
        return out;
    }

    private static byte[] ladder(byte[] scalar, byte[] u) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        byte[] uc = u.clone();
        uc[31] &= 127;
        BigInteger x1 = decodeLe(uc).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int bit = (k[t >> 3] >> (t & 7)) & 1;
            swap ^= bit;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = bit;
            BigInteger a = x2.add(z2).mod(P), aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P), bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P), d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P), cb = c.multiply(b).mod(P);
            x3 = da.add(cb).pow(2).mod(P);
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P);
        }
        if (swap == 1) {
            x2 = x3;
            z2 = z3;
        }
        return encodeLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P));
    }

    static byte[] x25519Public(byte[] priv) {
        byte[] nine = new byte[32];
        nine[0] = 9;
        return ladder(priv, nine);
    }

    // Null for an all-zero result (a low-order point, RFC 7748 6.1).
    static byte[] x25519(byte[] priv, byte[] peerPublic) {
        if (peerPublic.length != 32)
            return null;
        byte[] s = ladder(priv, peerPublic);
        int any = 0;
        for (byte b : s)
            any |= b;
        return any == 0 ? null : s;
    }

    // --- AES-256-GCM -----------------------------------------------------------

    static byte[] nonce(int label, long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 4; i++)
            n[i] = (byte) (label >>> (24 - 8 * i));
        for (int i = 0; i < 8; i++)
            n[4 + i] = (byte) (counter >>> (56 - 8 * i));
        return n;
    }

    // A reusable cipher: one per thread that seals or opens.
    static Cipher cipher() {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] seal(Cipher c, byte[] key, byte[] nonce, byte[] aad, byte[] plain, int off, int len) {
        try {
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
            if (aad.length > 0)
                c.updateAAD(aad);
            return c.doFinal(plain, off, len);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plain) {
        return seal(cipher(), key, nonce, aad, plain, 0, plain.length);
    }

    // Null when it fails authentication.
    static byte[] open(Cipher c, byte[] key, byte[] nonce, byte[] aad, byte[] sealed, int off, int len) {
        if (len < TAG)
            return null;
        try {
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
            if (aad.length > 0)
                c.updateAAD(aad);
            return c.doFinal(sealed, off, len);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) {
        return open(cipher(), key, nonce, aad, sealed, 0, sealed.length);
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b)
            sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts)
            n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
