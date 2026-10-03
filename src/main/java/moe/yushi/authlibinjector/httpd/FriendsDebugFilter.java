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
package moe.yushi.authlibinjector.httpd;

import static moe.yushi.authlibinjector.util.IOUtils.CONTENT_TYPE_JSON;
import static moe.yushi.authlibinjector.util.IOUtils.asBytes;
import static moe.yushi.authlibinjector.util.IOUtils.asString;
import static moe.yushi.authlibinjector.util.Logging.Level.DEBUG;
import static moe.yushi.authlibinjector.util.Logging.log;
import java.io.IOException;
import java.util.Optional;
import moe.yushi.authlibinjector.Config;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.IHTTPSession;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Response;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Status;
import moe.yushi.authlibinjector.internal.org.json.simple.JSONArray;
import moe.yushi.authlibinjector.internal.org.json.simple.JSONObject;

/**
 * Serves the friends endpoints from in-memory fixtures, without ever reaching
 * the backend. Enabled only by -Dmcyas.friends.debug, for observing what the
 * client actually sends.
 */
public class FriendsDebugFilter implements URLFilter {

	private static final String ETAG_FRIENDS = "\"debug-friends-1\"";
	private static final String ETAG_PRESENCE = "\"debug-presence-1\"";

	private static final String FRIEND_ID = "00000000-0000-0000-0000-000000000001";
	private static final String INCOMING_ID = "00000000-0000-0000-0000-000000000002";
	private static final String LAST_UPDATED = "2026-01-01T00:00:00Z";

	@Override
	public boolean canHandle(String domain) {
		return Config.friendsDebug && domain.equals("api.minecraftservices.com");
	}

	@Override
	public Optional<Response> handle(URLProcessor urlProcessor, String domain, String path, IHTTPSession session) throws IOException {
		if (!Config.friendsDebug || !domain.equals("api.minecraftservices.com")) {
			return Optional.empty();
		}

		String method = session.getMethod();
		if (path.equals("/friends") && (method.equals("GET") || method.equals("PUT"))) {
			if (method.equals("PUT")) {
				log(DEBUG, "Friends debug stub received PUT /friends: " + asString(asBytes(session.getInputStream())));
			}
			return Optional.of(ifNoneMatch(session, ETAG_FRIENDS, friendsResponse(), ETAG_FRIENDS));
		} else if (path.equals("/presence") && method.equals("POST")) {
			log(DEBUG, "Friends debug stub received POST /presence: " + asString(asBytes(session.getInputStream())));
			return Optional.of(ifNoneMatch(session, ETAG_PRESENCE, presenceResponse(), ETAG_PRESENCE));
		}
		return Optional.empty();
	}

	private static Response ifNoneMatch(IHTTPSession session, String etag, JSONObject body, String responseEtag) {
		String ifNoneMatch = session.getHeaders().get("if-none-match");
		if (etag.equals(ifNoneMatch)) {
			return Response.newFixedLength(Status.NOT_MODIFIED, null, "");
		}
		Response response = Response.newFixedLength(Status.OK, CONTENT_TYPE_JSON, body.toJSONString());
		response.addHeader("ETag", responseEtag);
		return response;
	}

	private static JSONObject friendDto(String profileId, String name) {
		JSONObject dto = new JSONObject();
		dto.put("profileId", profileId);
		dto.put("name", name);
		return dto;
	}

	private static JSONObject friendsResponse() {
		JSONArray friends = new JSONArray();
		friends.add(friendDto(FRIEND_ID, "DebugFriend"));

		JSONArray incoming = new JSONArray();
		incoming.add(friendDto(INCOMING_ID, "DebugIncoming"));

		JSONObject response = new JSONObject();
		response.put("friends", friends);
		response.put("incomingRequests", incoming);
		response.put("outgoingRequests", new JSONArray());
		return response;
	}

	private static JSONObject presenceResponse() {
		JSONObject entry = new JSONObject();
		entry.put("profileId", FRIEND_ID);
		entry.put("pmid", FRIEND_ID);
		entry.put("status", "ONLINE");
		entry.put("lastUpdated", LAST_UPDATED);

		JSONArray presence = new JSONArray();
		presence.add(entry);

		JSONObject response = new JSONObject();
		response.put("presence", presence);
		return response;
	}
}
