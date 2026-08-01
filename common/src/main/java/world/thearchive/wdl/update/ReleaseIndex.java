// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.update.Release;
import world.thearchive.wdl.core.update.SemVer;

/**
 * The release-index request and its fail-soft parse: one anonymous versions query, filtered server-side by loader and
 * MC version, read back into {@link Release} rows. Any non-2xx reply, transport failure, or unparseable body yields an
 * empty list, and a malformed row is dropped without touching its siblings. No retry and no backoff: the
 * once-per-launch cadence is the rate limit.
 */
final class ReleaseIndex {
    private ReleaseIndex() {}

    public static List<Release> fetch(RuntimeInfo info, Transport transport) {
        Transport.Result result = transport.fetch(requestUri(info), Map.of("User-Agent", userAgent(info)));
        if (result.status() / 100 != 2) {
            return List.of();
        }
        return parseRows(result.body());
    }

    private static URI requestUri(RuntimeInfo info) {
        return URI.create("https://api.modrinth.com/v2/project/" + encode(info.modrinthId())
                + "/version?loaders=" + encode("[\"" + info.loaderId() + "\"]")
                + "&game_versions=" + encode("[\"" + info.mcVersion() + "\"]")
                + "&include_changelog=false");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String userAgent(RuntimeInfo info) {
        return "archive-wdl/" + info.runningRaw() + " (" + UpdateCheck.MODRINTH_PAGE_URL + ")";
    }

    /**
     * Tree-model parse reading each snake_case key explicitly: default POJO binding would match camelCase field names
     * against these keys and silently null every one of them, including the load-bearing version_number, so the check
     * would never fire.
     */
    private static List<Release> parseRows(String body) {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!root.isJsonArray()) {
            return List.of();
        }
        List<Release> rows = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            Release release = parseRow(element);
            if (release != null) {
                rows.add(release);
            }
        }
        return rows;
    }

    private static @Nullable Release parseRow(JsonElement element) {
        // A missing key, a wrong-typed value, or an unparseable date each throw a RuntimeException
        // subtype here; any of them makes the row unusable, so the row is dropped whole.
        try {
            JsonObject row = element.getAsJsonObject();
            String versionNumber = row.get("version_number").getAsString();
            SemVer version = SemVer.parse(versionNumber).orElse(null);
            if (version == null) {
                return null;
            }
            return new Release(versionNumber, version,
                    strings(row.get("loaders").getAsJsonArray()),
                    strings(row.get("game_versions").getAsJsonArray()),
                    Instant.parse(row.get("date_published").getAsString()),
                    row.get("status").getAsString(),
                    row.get("version_type").getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }
}
