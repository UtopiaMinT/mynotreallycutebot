package xyz.utopiamint.mynotreallycutebot;

import okhttp3.*;
import okio.BufferedSink;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Utils {
    private static OkHttpClient client = new OkHttpClient();
    private static Logger logger = Logger.getLogger(Main.class.getName());
    // player stats cache
    private static Map<String, JSONObject> playerCache = new HashMap<>();
    private static Map<String, Long> playerCacheTime = new HashMap<>();
    // guild stats cache
    private static Map<String, JSONObject> guildCache = new HashMap<>();
    private static Map<String, Long> guildCacheTime = new HashMap<>();

    public static Map<String, List<String>> getOnlinePlayers() {
        JSONObject resp = new JSONObject(httpGet(Constants.API_ONLINE_PLAYERS));
        Map<String, List<String>> players = new HashMap<>();
        for (String key : resp.keySet()) {
            if (resp.get(key) instanceof JSONArray) {
                JSONArray list = resp.getJSONArray(key);
                players.put(key, list.toList().stream().map(Object::toString).collect(Collectors.toList()));
            }
        }
        List<String> tsHack = new ArrayList<>();
        tsHack.add(String.valueOf(resp.getJSONObject("request").getInt("timestamp")));
        players.put("timestamp", tsHack);
        return players;
    }

    public static JSONObject getPlayerStats(String player) {
        long now = System.currentTimeMillis();
        if (playerCacheTime.containsKey(player) && playerCacheTime.get(player) > now - 60000) {
            return playerCache.get(player);
        }
        JSONObject resp = new JSONObject(httpGet(Constants.API_PLAYER_STATS.replace("%s", player)));
        if (resp.getInt("code") == 200) {
            long timestamp = resp.getLong("timestamp");
            JSONObject data = resp.getJSONArray("data").getJSONObject(0);
            resp.put("data", data);
            String uuid = resp.getJSONObject("data").getString("uuid");
            resp.put("uuid", uuid.replaceAll("-", ""));
            playerCache.put(player, resp);
            playerCache.put(uuid, resp);
            playerCacheTime.put(player, timestamp);
            playerCacheTime.put(uuid, timestamp);
        }
        return resp;
    }

    public static JSONObject getGuildStats(String guild) {
        long now = System.currentTimeMillis();
        if (guildCacheTime.containsKey(guild) && guildCacheTime.get(guild) > now - 60000) {
            return guildCache.get(guild);
        }
        JSONObject resp = new JSONObject(httpGet(Constants.API_GUILD_STATS.replace("%s", guild)));
        if (resp.getInt("code") == 200) {
            long timestamp = resp.getLong("timestamp");
            guildCache.put(guild, resp);
            guildCacheTime.put(guild, timestamp);
        }
        return resp;
    }

    public static JSONObject getTerritoryList() {
        return new JSONObject(httpGet(Constants.API_TERRITORIES));
    }

    public static String questionMarks(int count) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < count - 1; i++) {
            sb.append("?, ");
        }
        sb.append("?)");
        return sb.toString();
    }

    public static String questionMarkMatrix(int count, int columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count - 1; i++) {
            sb.append(questionMarks(columns)).append(", ");
        }
        sb.append(questionMarks(columns));
        return sb.toString();
    }

    private static String httpGet(String url) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        return http(request);

    }

    private static String httpPost(String url, String content) {
        Request request = new Request.Builder()
                .url(url)
                .post(new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.get("application/json");
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        sink.write(content.getBytes());
                    }
                })
                .build();
        return http(request);
    }

    private static String http(Request request) {
        try {
            Response response = client.newCall(request).execute();
            if (response.body() != null) {
                return response.body().string();
            } else {
                return "";
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, String.format("Failed to get %s", request.url().toString()), e);
            e.printStackTrace();
            return "{}";
        }
    }
}
