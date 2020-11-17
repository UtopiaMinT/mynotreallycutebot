package xyz.utopiamint.mynotreallycutebot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Main implements Runnable {
    private static Logger LOGGER;

    public static void main(String[] args) throws InterruptedException {
        while (true) {
            InputStream stream = Main.class.getClassLoader().
                    getResourceAsStream("logging.properties");
            try {
                LogManager.getLogManager().readConfiguration(stream);
                LOGGER = Logger.getLogger(Main.class.getName());

            } catch (IOException e) {
                e.printStackTrace();
            }
            task();
            System.out.println("completed");
            Thread.sleep(15000);
        }
    }

    @Override
    public void run() {
        task();
    }

    public static void task() {
        LOGGER.info("Task started");
        // config
        Properties props = new Properties();
        try {
            props.load(new FileInputStream(new File(("bot.cfg"))));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Cannot read config file", e);
            return;
        }
        String connStr = String.format("jdbc:mysql://%s/%s?user=%s&password=%s&useSSL=false&autoReconnect=true", props.getProperty("db.host"), props.getProperty("db.name"), props.getProperty("db.user"), props.getProperty("db.pass"));
        try (Connection conn = DriverManager.getConnection(connStr)) {
            // gather all war servers from database
            PreparedStatement stmt = conn.prepareStatement("select id, server from war_log where verdict='started' or verdict is null");
            ResultSet rs = stmt.executeQuery();
            Map<Integer, String> startedWars = new HashMap<>();
            Map<String, Integer> warsStarted = new HashMap<>();
            while (rs.next()) {
                startedWars.put(rs.getInt(1), rs.getString(2));
                warsStarted.put(rs.getString(2), rs.getInt(1));
            }
            stmt.close();
            Set<String> startedWarServers = new HashSet<>(startedWars.values());

            // gather online war servers and players from the api
            Map<String, List<String>> onlinePlayers = Utils.getOnlinePlayers();
            int onlinePlayerTimestamp = Integer.parseInt(onlinePlayers.get("timestamp").get(0));
            Set<String> onlineWarServers = onlinePlayers.keySet().stream().filter(x -> x.startsWith("WAR")).collect(Collectors.toSet());

            Set<String> newlyStartedWars = new HashSet<>(onlineWarServers);
            newlyStartedWars.removeAll(startedWarServers);
            Set<String> endedWars = new HashSet<>(startedWarServers);
            endedWars.removeAll(onlineWarServers);

            // mark players in war servers as surviving
            Map<String, Integer> serverWarId = new HashMap<>();
            if (!onlineWarServers.isEmpty()) {
                stmt = conn.prepareStatement("select id, server from war_log where server in " + Utils.questionMarks(onlineWarServers.size()));
                int i = 1;
                for (String server : onlineWarServers) {
                    stmt.setString(i++, server);
                }
                rs = stmt.executeQuery();
                while (rs.next()) {
                    serverWarId.put(rs.getString(2), rs.getInt(1));
                }
                stmt.close();
                for (String server : onlineWarServers) {
                    List<String> players = onlinePlayers.get(server);
                    if (!players.isEmpty() && serverWarId.containsKey(server)) {
                        stmt = conn.prepareStatement("update player_war_log set survived_until=? where war_id=? and ign in " + Utils.questionMarks(players.size()));
                        i = 3;
                        stmt.setInt(1, onlinePlayerTimestamp);
                        stmt.setInt(2, serverWarId.get(server));
                        for (String player : players) {
                            stmt.setString(i++, player);
                        }
                        stmt.executeUpdate();
                        stmt.close();
                    }
                }
            }

            // for the ended wars, we mark them as ended
            int updated = 0;
            if (!endedWars.isEmpty()) {
                stmt = conn.prepareStatement("update war_log set verdict='ended', end_time=? where id in " + Utils.questionMarks(endedWars.size()));
                stmt.setInt(1, onlinePlayerTimestamp);
                int i = 2;
                for (String server : endedWars) {
                    stmt.setInt(i++, warsStarted.get(server));
                }
                updated = stmt.executeUpdate();
                if (updated != endedWars.size()) {
                    LOGGER.warning(String.format("Updated row count doesn't match ended war count (%d != %d)", updated, endedWars.size()));
                }
                stmt.close();
            }
            LOGGER.info(String.format("Ended wars: updated %d rows", updated));

            // for the newly started wars, insert them
            updated = 0;
            if (!newlyStartedWars.isEmpty()) {
                stmt = conn.prepareStatement("insert into war_log (server) values " + Utils.questionMarkMatrix(newlyStartedWars.size(), 1));
                int i = 1;
                for (String server : newlyStartedWars) {
                    stmt.setString(i++, server);
                }
                updated = stmt.executeUpdate();
                if (updated != newlyStartedWars.size()) {
                    LOGGER.warning(String.format("Updated row count doesn't match new war count (%d != %d)", updated, newlyStartedWars.size()));
                }
                stmt.close();
            }
            LOGGER.info(String.format("Started wars: updated %d rows", updated));

            // for online war servers without a guild, we try to figure out which
            // and insert player war log entries
            startedWars.clear();
            warsStarted.clear();
            stmt = conn.prepareStatement("select id, server from war_log where (verdict='started' or verdict is null) and attacker is null");
            rs = stmt.executeQuery();
            while (rs.next()) {
                startedWars.put(rs.getInt(1), rs.getString(2));
                warsStarted.put(rs.getString(2), rs.getInt(1));
            }
            stmt.close();
            for (String server : warsStarted.keySet()) {
                List<String> players = onlinePlayers.get(server);
                if (!players.isEmpty()) {
                    // server populated
                    String guild = null;
                    // we first try to derive the guild from player stats
                    for (String player : players) {
                        JSONObject playerStats = Utils.getPlayerStats(player);
                        guild = playerStats.getJSONObject("data").getJSONObject("guild").optString("name");
                        if (guild != null) {
                            break;
                        }
                    }
                    // if that didn't work, try our own guild hint
                    if (guild == null) {
                        stmt = conn.prepareStatement("select guild from guild_hint where uuid in " + Utils.questionMarks(players.size()) + "group by guild order by count(*) limit 1");
                        int i = 1;
                        for (String player : players) {
                            stmt.setString(i++, Utils.getPlayerStats(player).getString("uuid"));
                        }
                        rs = stmt.executeQuery();
                        if (rs.next()) {
                            guild = rs.getString(1);
                        }
                        stmt.close();
                        // we verify our hint by comparing it against the api
                        JSONObject guildStats = Utils.getGuildStats(guild);
                        Set<String> members = guildStats.getJSONArray("members").toList().stream().map(x -> ((JSONObject) x).getString("uuid").replaceAll("-", "")).collect(Collectors.toSet());
                        boolean verified = false;
                        for (String player : players) {
                            verified = members.contains(Utils.getPlayerStats(player).getString("uuid"));
                            if (verified) break;
                        }
                        if (!verified) guild = null;
                    }
                    // finally we update the entry, as well as populate our guild hints and player war log
                    if (guild != null) {
                        // attacker
                        stmt = conn.prepareStatement("update war_log set attacker=?, start_time=?, verdict='started' where id=?");
                        stmt.setString(1, guild);
                        stmt.setInt(2, onlinePlayerTimestamp);
                        stmt.setInt(3, warsStarted.get(server));
                        stmt.executeUpdate();
                        stmt.close();
                        LOGGER.info(String.format("Updated war #%d attacker=%s", warsStarted.get(server), guild));

                        // guild hint
                        stmt = conn.prepareStatement("replace into guild_hint (uuid, guild) VALUES " + Utils.questionMarkMatrix(players.size(), 2));
                        int i = 1;
                        for (String player : players) {
                            stmt.setString(i++, Utils.getPlayerStats(player).getString("uuid"));
                            stmt.setString(i++, guild);
                        }
                        LOGGER.info(String.format("Updated %d guild hint entries", stmt.executeUpdate()));
                        stmt.close();
                        stmt = conn.prepareStatement("insert into player_war_log (war_id, ign, uuid, guild) VALUES " + Utils.questionMarkMatrix(players.size(), 4));

                        // player war log
                        i = 1;
                        for (String player : players) {
                            stmt.setInt(i++, warsStarted.get(server));
                            stmt.setString(i++, player);
                            stmt.setString(i++, Utils.getPlayerStats(player).getString("uuid"));
                            stmt.setString(i++, guild);
                        }
                        LOGGER.info(String.format("Inserted %d player war log entries", stmt.executeUpdate()));
                        stmt.close();
                    }
                }
            }

            // for the recently ended wars, we try figure out which territory it is
            startedWars.clear();
            warsStarted.clear();
            stmt = conn.prepareStatement("select id, server from war_log where verdict='ended' and attacker is not null");
            rs = stmt.executeQuery();
            while (rs.next()) {
                startedWars.put(rs.getInt(1), rs.getString(2));
                warsStarted.put(rs.getString(2), rs.getInt(1));
            }
            stmt.close();
            Map<String, String> territoryOwners = new HashMap<>();
            stmt = conn.prepareStatement("select territory, guild from territories");
            rs = stmt.executeQuery();
            while (rs.next()) {
                territoryOwners.put(rs.getString(1), rs.getString(2));
            }
            stmt.close();

            JSONObject territories = Utils.getTerritoryList();
            JSONObject territoryMap = territories.getJSONObject("territories");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); // 2020-11-17 18:04:26
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

            for (String name : territoryMap.keySet()) {
                String owner = territoryMap.getJSONObject(name).getString("guild");
                if (!owner.equals(territoryOwners.get(name))) {
                    // find the most recent war by the guild
                    stmt = conn.prepareStatement("select id from war_log where attacker=? order by id desc limit 1");
                    stmt.setString(1, owner);
                    rs = stmt.executeQuery();

                    String defender = territoryOwners.get(name);
                    int attackerTerrCount = (int) (territoryOwners.values().stream().filter(x -> x.equals(owner)).count() + 1);
                    int defenderTerrCount = (int) (territoryOwners.values().stream().filter(x -> x.equals(defender)).count() - 1);
                    int acquired = (int) (sdf.parse(territoryMap.getJSONObject(name).getString("acquired")).getTime() / 1000);
                    if (rs.next()) {
                        // such war exists, proceed to update it
                        int id = rs.getInt(1);
                        stmt.close();
                        stmt = conn.prepareStatement("update war_log set defender=?, attacker_terr_count=?, defender_terr_count=?, end_time=?, verdict='won', terr_name=?, won=won+1 where id=?");
                        stmt.setString(1, defender);
                        stmt.setInt(2, attackerTerrCount);
                        stmt.setInt(3, defenderTerrCount);
                        stmt.setInt(4, acquired);
                        stmt.setString(5, name);
                        stmt.setInt(6, id);
                        stmt.executeUpdate();
                        stmt.close();
                        territoryOwners.put(name, owner);
                        // update the territory
                        stmt = conn.prepareStatement("replace into territories (guild, acquired, territory) values (?, ?, ?)");
                        stmt.setString(1, owner);
                        stmt.setInt(2, acquired);
                        stmt.setString(3, name);
                        stmt.executeUpdate();
                        stmt.close();
                    } else {
                        // it's a snipe, so add a "war" manually
                        stmt = conn.prepareStatement("insert into war_log (attacker, defender, attacker_terr_count, defender_terr_count, start_time, end_time, verdict, terr_name) VALUES " + Utils.questionMarks(8));
                        stmt.setString(1, owner);
                        stmt.setString(2, defender);
                        stmt.setInt(3, attackerTerrCount);
                        stmt.setInt(4, defenderTerrCount);
                        stmt.setInt(5, acquired);
                        stmt.setInt(6, acquired);
                        stmt.setString(7, "won");
                        stmt.setString(8, name);
                        stmt.executeUpdate();
                        stmt.close();
                    }
                    LOGGER.info(String.format("Updated territory %s", name));
                }
            }

            // for the not-so-recently ended wars, we mark them as lost
            stmt = conn.prepareStatement("update war_log set verdict='lost' where verdict='ended' and end_time<unix_timestamp()-120");
            LOGGER.info(String.format("Marked %d wars as lost", stmt.executeUpdate()));
            stmt.close();

            LOGGER.info("Task completed");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database error", e);
        } catch (ParseException e) {
            LOGGER.log(Level.SEVERE, "Date parse error", e);
        }
    }
}
