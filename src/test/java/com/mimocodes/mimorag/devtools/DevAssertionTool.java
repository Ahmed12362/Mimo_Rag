package com.mimocodes.mimorag.devtools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Arrays;

import com.mimocodes.mimorag.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * Development-only helper that plays the role of a connected system's token issuer, for manual curl testing with the
 * {@code local} profile (inline JWKS). Not packaged into the application jar.
 *
 * <pre>
 * keygen &lt;dir&gt;                                   writes private-jwk.json (keep local!) and public-jwks.json
 * mint   &lt;dir&gt; &lt;issuer&gt; &lt;audience&gt; &lt;subject&gt; [roles...]   prints a 2-minute RS256 user assertion
 * </pre>
 */
public final class DevAssertionTool {

	private DevAssertionTool() {
	}

	public static void main(String[] args) throws IOException, ParseException {
		if (args.length >= 2 && "keygen".equals(args[0])) {
			Path dir = Path.of(args[1]);
			Files.createDirectories(dir);
			RSAKey key = TestTokens.newRsaKey();
			Files.writeString(dir.resolve("private-jwk.json"), key.toJSONString());
			Files.writeString(dir.resolve("public-jwks.json"), TestTokens.publicJwks(key));
			System.out.println(TestTokens.publicJwks(key));
		}
		else if (args.length >= 5 && "mint".equals(args[0])) {
			RSAKey key = RSAKey.parse(Files.readString(Path.of(args[1]).resolve("private-jwk.json")));
			String[] roles = Arrays.copyOfRange(args, 5, args.length);
			System.out.println(TestTokens.claims(args[2], args[3], args[4]).roles(roles).signRs256(key));
		}
		else {
			System.err.println("usage: keygen <dir> | mint <dir> <issuer> <audience> <subject> [roles...]");
			System.exit(2);
		}
	}

}
