/*
 * Copyright (C) 2026  MCYAS Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package club.leisurec.mcyas.authlibinjector;

import static java.nio.charset.StandardCharsets.UTF_8;
import static moe.yushi.authlibinjector.util.IOUtils.asBytes;
import static moe.yushi.authlibinjector.util.IOUtils.asString;
import static moe.yushi.authlibinjector.util.Logging.Level.ERROR;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.Level.WARNING;
import static moe.yushi.authlibinjector.util.Logging.log;
import static moe.yushi.authlibinjector.util.JsonUtils.asJsonObject;
import static moe.yushi.authlibinjector.util.JsonUtils.asJsonString;
import static moe.yushi.authlibinjector.util.JsonUtils.parseJson;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import moe.yushi.authlibinjector.APIMetadata;
import moe.yushi.authlibinjector.Config;

public final class MCYASAuthentication {

	private MCYASAuthentication() {}

	public static void init(APIMetadata apiMetadata) {
		String verifyKey = Config.mcyasVerifyKeyValue;
		if (verifyKey == null || verifyKey.isEmpty()) {
			return;
		}

		String apiRoot = apiMetadata.getApiRoot();
		if (!apiRoot.endsWith("/")) {
			apiRoot += "/";
		}

		String token = doVerify(apiRoot + "server/verify", verifyKey);
		Config.mcyasSessionToken = token;
		log(INFO, "MCYAS server authentication successful, session token obtained");

		registerShutdownHook(apiRoot + "server/logout");
	}

	private static String doVerify(String url, String verifyKey) {
		log(INFO, "Authenticating with MCYAS server: " + url);
		try {
			HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty(MCYASHttpHeader.GAME_SERVER_VERIFY, verifyKey);
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);

			int responseCode = conn.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				log(ERROR, "MCYAS server authentication failed: HTTP " + responseCode + " " + conn.getResponseMessage());
				try {
					byte[] errorBody = asBytes(conn.getErrorStream());
					if (errorBody.length > 0) {
						log(ERROR, "Response: " + new String(errorBody, UTF_8));
					}
				} catch (Exception ignored) {
				}
				System.exit(1);
				throw new AssertionError("unreachable");
			}

			String token;
			try {
				String responseBody = asString(asBytes(conn.getInputStream())).trim();
				token = asJsonString(asJsonObject(parseJson(responseBody)).get("sessionId"));
			} catch (IOException e) {
				log(ERROR, "Failed to read MCYAS authentication response body", e);
				System.exit(1);
				throw new AssertionError("unreachable");
			} catch (RuntimeException e) {
				log(ERROR, "Failed to parse MCYAS authentication response as JSON", e);
				System.exit(1);
				throw new AssertionError("unreachable");
			}

			if (token.isEmpty()) {
				log(ERROR, "MCYAS server returned empty session token");
				System.exit(1);
				throw new AssertionError("unreachable");
			}

			return token;
		} catch (IOException e) {
			log(ERROR, "Failed to connect to MCYAS server for authentication: " + e.getMessage(), e);
			System.exit(1);
			throw new AssertionError("unreachable");
		}
	}

	private static void registerShutdownHook(String logoutUrl) {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			String verifyKey = Config.mcyasVerifyKeyValue;
			if (verifyKey == null || verifyKey.isEmpty()) {
				return;
			}
			log(INFO, "Logging out from MCYAS server...");
			try {
				HttpURLConnection conn = (HttpURLConnection) new URL(logoutUrl).openConnection();
				conn.setRequestMethod("POST");
				conn.setRequestProperty(MCYASHttpHeader.GAME_SERVER_VERIFY, verifyKey);
				conn.setConnectTimeout(5000);
				conn.setReadTimeout(5000);

				int responseCode = conn.getResponseCode();
				if (responseCode >= 200 && responseCode < 300) {
					log(INFO, "MCYAS server logout successful");
				} else {
					log(WARNING, "MCYAS server logout failed: HTTP " + responseCode);
				}
			} catch (Exception e) {
				log(WARNING, "Failed to logout from MCYAS server: " + e.getMessage());
			}
		}, "MCYAS-Logout-Hook"));
	}
}
