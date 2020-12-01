package xyz.utopiamint.mynotreallycutebot;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Utils {
    private static OkHttpClient client = new OkHttpClient();
    private static Logger logger = Logger.getLogger(Utils.class.getName());
    // player stats cache
    private static Map<String, JSONObject> playerCache = new HashMap<>();
    private static Map<String, Long> playerCacheTime = new HashMap<>();
    // guild stats cache
    private static Map<String, JSONObject> guildCache = new HashMap<>();
    private static Map<String, Long> guildCacheTime = new HashMap<>();

    /**
     * Gets online players
     * @return A map of server name => list of players online in that server
     */
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

    /**
     * Gets player stats
     * @param player the player's ign or uuid without dashes
     * @return Wynn API response as a JSONObject with data[0] mapped to data and a convenient uuid field
     */
    public static JSONObject getPlayerStats(String player) {
        long now = System.currentTimeMillis();
        sweepCache();
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

    /**
     * Gets guild stats
     * @param guild Guild name
     * @return Wynn API response as a JSONObject
     */
    public static JSONObject getGuildStats(String guild) {
        long now = System.currentTimeMillis();
        sweepCache();
        if (guildCacheTime.containsKey(guild) && guildCacheTime.get(guild) > now - 60000) {
            return guildCache.get(guild);
        }
        JSONObject resp = new JSONObject(httpGet(Constants.API_GUILD_STATS.replace("%s", guild)));
        if (resp.getInt("code") == 200) {
            long timestamp = resp.getJSONObject("request").getLong("timestamp") * 1000L;
            guildCache.put(guild, resp);
            guildCacheTime.put(guild, timestamp);
        }
        return resp;
    }

    /**
     * Gets territory list
     * @return Wynn API response as a JSONObject
     */
    public static JSONObject getTerritoryList() {
        return new JSONObject(httpGet(Constants.API_TERRITORIES));
    }

    /**
     * Requests the uuid of the supplied list of ign, may take longer to return if the list is long
     * @param conn The database connection for us to retrieve and store the ign caches
     * @param names Player ign
     * @return A map of name => uuid
     */
    public static Map<String, String> ignToUuidBulk(Connection conn, Collection<String> names) throws SQLException{
        Map<String, String> result = new HashMap<>();
        Set<String> remainingNames = new HashSet<>(names);
        // first we go for the database
        PreparedStatement stmt = conn.prepareStatement("select uuid, ign from ign_cache where time>? and ign_lower in " + questionMarks(remainingNames.size()));
        stmt.setInt(1, (int) ((System.currentTimeMillis() - 172800000) / 1000));
        int i = 2;
        for (String name : remainingNames) {
            stmt.setString(i++, name.toLowerCase());
        }
        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            String uuid = rs.getString(1);
            String ign = rs.getString(2);
            result.put(ign, uuid);
            remainingNames.remove(ign);
        }
        stmt.close();
        // for the remaining, we go for the mojang api, 10 names at a time
        List<String> nameList = new ArrayList<>(remainingNames);
        Map<String, String> newNames = new HashMap<>();
        for (i = 0; i < (nameList.size() + 9) / 10; ++i) {
            JSONArray array = new JSONArray();
            for (int j = i * 10; j < i * 10 + 10 && j < nameList.size(); j++) {
                array.put(nameList.get(j));
            }
            String resp = httpPost(Constants.MOJANG_UUID_API, array.toString());
            JSONArray batch = new JSONArray(resp);
            for (int j = 0; j < batch.length(); j++) {
                JSONObject mcProfile = batch.getJSONObject(j);
                String uuid = mcProfile.getString("id");
                String ign = mcProfile.getString("name");
                newNames.put(uuid, ign);
                result.put(ign, uuid);
            }
        }
        // finally, we upload everything new to the database (if any)
        if (!newNames.isEmpty()) {
            stmt = conn.prepareStatement("replace into ign_cache (uuid, ign, ign_lower, time) VALUES " + questionMarkMatrix(newNames.size(), 4));
            i = 1;
            int time = (int) (System.currentTimeMillis() / 1000);
            for (Map.Entry<String, String> entry : newNames.entrySet()) {
                stmt.setString(i++, entry.getKey());
                stmt.setString(i++, entry.getValue());
                stmt.setString(i++, entry.getValue().toLowerCase());
                stmt.setInt(i++, time);
            }
            logger.info(String.format("Updated %d rows for ign_cache", stmt.executeUpdate()));
        }
        return result;
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

    private static void sweepCache() {
        long now = System.currentTimeMillis();
        playerCache.entrySet().removeIf(entry -> playerCacheTime.get(entry.getKey()) < now - 60000);
        guildCache.entrySet().removeIf(entry -> guildCacheTime.get(entry.getKey()) < now - 60000);
    }

    private static String httpGet(String url) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        return http(request);

    }

    private static String httpPost(String url, String content) {
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), content);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        return http(request);
    }

    private static String http(Request request) {
        try {
            logger.info(String.format("%s %s", request.method(), request.url()));
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
