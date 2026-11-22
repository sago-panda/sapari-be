package com.sapari.common.securityjwt.jwt;

import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.UnsupportedJwtException;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link JwtProperties} 의 RS256 키를 파싱해 들고, JWS 서명(kid 헤더)과 검증(kid → 공개키)을 붙여 준다.
 *
 * <p>토큰 종류(claims)를 보지 않는다 — 키 로딩과 서명·검증만 맡고, 토큰을 구분하는 claim 검사는 호출자 몫이다.
 *
 * <p>검증은 RS256 만 받는다. 헤더의 alg 를 그대로 믿으면 HMAC 시절 토큰이나 다른 알고리즘 토큰이
 * 들어올 여지가 생기므로 키를 고르기 전에 alg 부터 고정한다.
 */
public final class JwtKeys {

    private static final String ALGORITHM = Jwts.SIG.RS256.getId();
    private static final int MIN_RSA_BITS = 2048;

    private final String signingKid;
    private final PrivateKey signingKey;
    private final Map<String, PublicKey> verificationKeys;

    private JwtKeys(String signingKid, PrivateKey signingKey, Map<String, PublicKey> verificationKeys) {
        this.signingKid = signingKid;
        this.signingKey = signingKey;
        this.verificationKeys = verificationKeys;
    }

    /**
     * 키 형식·길이·서명키와 공개키의 짝이 틀리면 여기서 실패한다 — 기동 시 1회 호출되어 잘못된 키로 뜨지 않게 한다.
     * 예외 메시지에 키 원문을 싣지 않는다.
     */
    public static JwtKeys from(JwtProperties properties) {
        Map<String, PublicKey> publicKeys = new LinkedHashMap<>();
        for (JwtProperties.VerificationKey key : properties.verificationKeys()) {
            publicKeys.put(key.kid(), parsePublicKey(key));
        }

        JwtProperties.SigningKey signing = properties.signingKey();
        if (signing == null) {
            return new JwtKeys(null, null, Map.copyOf(publicKeys));
        }

        PrivateKey privateKey = parsePrivateKey(signing);
        // 짝이 안 맞으면 자기가 발급한 토큰을 자기가 거부한다 — 기동은 되는데 모든 로그인이 실패하는 상태라 여기서 막는다.
        if (!modulus(privateKey).equals(modulus(publicKeys.get(signing.kid())))) {
            throw new IllegalStateException(
                    "jwt.signing-key 가 같은 kid 의 jwt.verification-keys 공개키와 짝이 아닙니다: " + signing.kid());
        }
        return new JwtKeys(signing.kid(), privateKey, Map.copyOf(publicKeys));
    }

    /**
     * 헤더에 kid 를 넣고 RS256 으로 서명하도록 builder 를 설정한다. 서명키가 없는(검증 전용) 앱에서는 실패한다.
     */
    public JwtBuilder signWith(JwtBuilder builder) {
        if (signingKey == null) {
            throw new IllegalStateException("jwt.signing-key 가 없어 토큰을 서명할 수 없습니다(검증 전용 설정).");
        }
        return builder
                .header().keyId(signingKid).and()
                .signWith(signingKey, Jwts.SIG.RS256);
    }

    /**
     * 헤더의 kid 로 공개키를 골라 서명을 검증하도록 parser 를 설정한다. alg 가 RS256 이 아니거나 kid 를 모르면 거부한다.
     */
    public JwtParserBuilder verifyWith(JwtParserBuilder builder) {
        return builder.keyLocator(new LocatorAdapter<Key>() {
            @Override
            protected Key locate(JwsHeader header) {
                if (!ALGORITHM.equals(header.getAlgorithm())) {
                    throw new UnsupportedJwtException("JWT alg must be " + ALGORITHM + ".");
                }
                PublicKey key = header.getKeyId() == null ? null : verificationKeys.get(header.getKeyId());
                if (key == null) {
                    throw new UnsupportedJwtException("JWT kid is unknown.");
                }
                return key;
            }
        });
    }

    /**
     * {@code privateKey} 가 검증 공개키 중 하나와 짝(같은 modulus)이면 true. 다른 용도의 서명키가 사용자 토큰
     * 키쌍을 재사용하는지 기동 시 검사하는 데 쓴다 — 재사용하면 그 키를 가진 쪽이 사용자 토큰을 위조할 수 있다.
     */
    public boolean hasVerificationKeyFor(PrivateKey privateKey) {
        BigInteger target = modulus(privateKey);
        return verificationKeys.values().stream().anyMatch(key -> modulus(key).equals(target));
    }

    private static PublicKey parsePublicKey(JwtProperties.VerificationKey key) {
        PublicKey publicKey;
        try {
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(key.publicKey())));
        } catch (RuntimeException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "jwt.verification-keys 공개키(Base64 X.509) 파싱 실패: kid=" + key.kid(), e);
        }
        requireStrength(publicKey, "jwt.verification-keys kid=" + key.kid());
        return publicKey;
    }

    private static PrivateKey parsePrivateKey(JwtProperties.SigningKey key) {
        PrivateKey privateKey;
        try {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(key.privateKey())));
        } catch (RuntimeException | GeneralSecurityException e) {
            // 개인키 원문은 자격증명이므로 메시지에 싣지 않는다.
            throw new IllegalStateException("jwt.signing-key 개인키(Base64 PKCS#8) 파싱 실패: kid=" + key.kid(), e);
        }
        requireStrength(privateKey, "jwt.signing-key kid=" + key.kid());
        return privateKey;
    }

    /**
     * MIME 디코더라 PEM 본문처럼 줄바꿈된 Base64 도 받는다(streaming-app {@code RoomTokenConfig} 와 같은 이유).
     */
    private static byte[] decode(String base64) {
        return Base64.getMimeDecoder().decode(base64.trim());
    }

    /**
     * jjwt 는 2048비트 미만 RSA 키를 서명·검증 시점에 거부한다. 그러면 기동은 되고 모든 토큰이 실패하므로 기동 때 막는다.
     */
    private static void requireStrength(Key key, String name) {
        if (modulus(key).bitLength() < MIN_RSA_BITS) {
            throw new IllegalStateException(name + " 는 " + MIN_RSA_BITS + "비트 이상 RSA 키여야 합니다.");
        }
    }

    private static BigInteger modulus(Key key) {
        return ((RSAKey) key).getModulus();
    }
}
