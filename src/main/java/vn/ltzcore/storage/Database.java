package vn.ltzcore.storage;

import java.io.File;
import java.sql.*;
import java.util.*;

/** Lightweight SQLite storage. Queries are synchronized and use prepared statements. */
public final class Database implements AutoCloseable {
    private final Connection connection;

    public Database(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create plugin data folder");
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS users(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, salt TEXT NOT NULL, pass_hash TEXT NOT NULL, created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS economy(uuid TEXT PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS ranks(uuid TEXT PRIMARY KEY, rank TEXT NOT NULL DEFAULT 'Member', expires_at INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS rank_definitions(name TEXT PRIMARY KEY COLLATE NOCASE, prefix TEXT NOT NULL, home_limit INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 0, staff_level INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS homes(uuid TEXT NOT NULL, name TEXT NOT NULL, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, PRIMARY KEY(uuid,name))");
            st.execute("CREATE TABLE IF NOT EXISTS warps(name TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS lifesteal(uuid TEXT PRIMARY KEY, hearts INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS lifesteal_event_players(event_started_at INTEGER NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY(event_started_at,uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS crate_keys(uuid TEXT NOT NULL, crate TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,crate))");
            st.execute("CREATE TABLE IF NOT EXISTS codes(code TEXT PRIMARY KEY, reward_money INTEGER NOT NULL, max_uses INTEGER NOT NULL, uses INTEGER NOT NULL DEFAULT 0, expires_at INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE TABLE IF NOT EXISTS redemptions(code TEXT NOT NULL, uuid TEXT NOT NULL, redeemed_at INTEGER NOT NULL, PRIMARY KEY(code,uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, from_uuid TEXT, to_uuid TEXT, amount INTEGER NOT NULL, type TEXT NOT NULL, created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS punishments(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, type TEXT NOT NULL, reason TEXT NOT NULL, expires_at INTEGER NOT NULL, staff TEXT NOT NULL, created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS playtime(uuid TEXT PRIMARY KEY, seconds INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS shards(uuid TEXT PRIMARY KEY, amount INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS shard_transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, amount INTEGER NOT NULL, type TEXT NOT NULL, created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS rank_purchase_requests(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, player_name TEXT NOT NULL, rank_name TEXT NOT NULL, price_vnd INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', created_at INTEGER NOT NULL, confirmed_at INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rank_requests_status ON rank_purchase_requests(status,created_at)");
            st.execute("CREATE TABLE IF NOT EXISTS bounties(id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT NOT NULL, placer_uuid TEXT NOT NULL, amount INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, claimed_by TEXT, claimed_at INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bounties_target ON bounties(target_uuid,active)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_created ON transactions(created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_codes_active ON codes(active,expires_at)");
            st.execute("CREATE TABLE IF NOT EXISTS haven_spawners(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,mob TEXT NOT NULL,stack INTEGER NOT NULL DEFAULT 1,owner_uuid TEXT NOT NULL,stored_xp INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(world,x,y,z))");
            st.execute("CREATE TABLE IF NOT EXISTS haven_spawner_loot(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,material TEXT NOT NULL,amount INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(world,x,y,z,material))");
            st.execute("CREATE TABLE IF NOT EXISTS haven_ah(id INTEGER PRIMARY KEY AUTOINCREMENT,seller_uuid TEXT NOT NULL,seller_name TEXT NOT NULL,item_data TEXT NOT NULL,material TEXT NOT NULL,amount INTEGER NOT NULL,price INTEGER NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'ACTIVE')");
            st.execute("CREATE INDEX IF NOT EXISTS idx_haven_ah_active ON haven_ah(status,expires_at,id DESC)");
            st.execute("CREATE TABLE IF NOT EXISTS haven_orders(id INTEGER PRIMARY KEY AUTOINCREMENT,buyer_uuid TEXT NOT NULL,buyer_name TEXT NOT NULL,material TEXT NOT NULL,remaining INTEGER NOT NULL,unit_price INTEGER NOT NULL,escrow INTEGER NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'ACTIVE')");
            st.execute("CREATE INDEX IF NOT EXISTS idx_haven_orders_active ON haven_orders(status,expires_at,id DESC)");
            st.execute("CREATE TABLE IF NOT EXISTS haven_order_deliveries(id INTEGER PRIMARY KEY AUTOINCREMENT,buyer_uuid TEXT NOT NULL,material TEXT NOT NULL,amount INTEGER NOT NULL,created_at INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_haven_order_deliveries_buyer ON haven_order_deliveries(buyer_uuid,id)");
            st.execute("CREATE TABLE IF NOT EXISTS haven_enderchest(uuid TEXT PRIMARY KEY, contents TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS haven_player_settings(uuid TEXT NOT NULL, setting TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(uuid,setting))");
            st.execute("CREATE TABLE IF NOT EXISTS haven_stats(uuid TEXT PRIMARY KEY, kills INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0, mobs_killed INTEGER NOT NULL DEFAULT 0, blocks_broken INTEGER NOT NULL DEFAULT 0, blocks_placed INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS haven_teams(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, tag TEXT NOT NULL, owner_uuid TEXT NOT NULL, home_world TEXT, home_x REAL NOT NULL DEFAULT 0, home_y REAL NOT NULL DEFAULT 0, home_z REAL NOT NULL DEFAULT 0, home_yaw REAL NOT NULL DEFAULT 0, home_pitch REAL NOT NULL DEFAULT 0, pvp_enabled INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS haven_team_members(uuid TEXT PRIMARY KEY, team_id INTEGER NOT NULL, role TEXT NOT NULL DEFAULT 'MEMBER', joined_at INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_haven_team_members_team ON haven_team_members(team_id)");
        }
        ensureColumn("codes", "reward_type", "TEXT NOT NULL DEFAULT 'money'");
        ensureColumn("codes", "reward_value", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("codes", "reward_amount", "INTEGER NOT NULL DEFAULT 1");
        seedRank("Member", "§7[Member]", 1, 0, 0);
        seedRank("+", "§a[+]", 2, 10, 0);
        seedRank("++", "§b[++]", 3, 20, 0);
        seedRank("+++", "§d[+++]", 5, 30, 0);
        if (!"1".equals(getSetting("rank.staff_tier_v2", "0"))) {
            try (Statement st = connection.createStatement()) {
                st.execute("UPDATE rank_definitions SET staff_level=3 WHERE name='Owner' COLLATE NOCASE AND staff_level=2");
                st.execute("UPDATE rank_definitions SET staff_level=2 WHERE name='Admin' COLLATE NOCASE AND staff_level=1");
            }
            setting("rank.staff_tier_v2", "1");
        }
        seedRank("Staff", "§3[Staff]", 10, 95, 1);
        seedRank("Admin", "§c[Admin]", 20, 90, 2);
        seedRank("Owner", "§6[Owner]", 50, 100, 3);
        if (!"1".equals(getSetting("rank.staff_order_v3", "0"))) {
            try (Statement st = connection.createStatement()) {
                st.execute("UPDATE rank_definitions SET priority=95 WHERE name='Staff' COLLATE NOCASE AND priority=80");
            }
            setting("rank.staff_order_v3", "1");
        }
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        boolean found = false;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) { found = true; break; }
        }
        if (!found) try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public synchronized boolean registered(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM users WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public synchronized boolean createUser(UUID uuid, String name, String salt, String hash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO users(uuid,name,salt,pass_hash,created_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setString(3, salt); ps.setString(4, hash); ps.setLong(5, System.currentTimeMillis());
            return ps.executeUpdate() == 1;
        }
    }

    public synchronized void upsertUserPassword(UUID uuid, String name, String salt, String hash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users(uuid,name,salt,pass_hash,created_at) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,salt=excluded.salt,pass_hash=excluded.pass_hash")) {
            ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setString(3, salt);
            ps.setString(4, hash); ps.setLong(5, System.currentTimeMillis()); ps.executeUpdate();
        }
    }

    public synchronized String[] credentials(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT salt,pass_hash FROM users WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new String[]{rs.getString(1),rs.getString(2)} : null; }
        }
    }

    public synchronized void changePassword(UUID uuid, String salt, String hash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE users SET salt=?,pass_hash=? WHERE uuid=?")) {
            ps.setString(1, salt); ps.setString(2, hash); ps.setString(3, uuid.toString()); ps.executeUpdate();
        }
    }

    public synchronized long balance(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM economy WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        }
    }

    public synchronized void setBalance(UUID uuid, long amount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO economy(uuid,balance) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET balance=excluded.balance")) {
            ps.setString(1, uuid.toString()); ps.setLong(2, Math.max(0, amount)); ps.executeUpdate();
        }
    }

    public synchronized boolean transfer(UUID from, UUID to, long amount) throws SQLException {
        if (amount <= 0 || from.equals(to)) return false;
        connection.setAutoCommit(false);
        try {
            long sender = balance(from);
            if (sender < amount) { connection.rollback(); return false; }
            long receiver = balance(to);
            setBalance(from, sender - amount); setBalance(to, Math.addExact(receiver, amount));
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO transactions(from_uuid,to_uuid,amount,type,created_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, from.toString()); ps.setString(2, to.toString()); ps.setLong(3, amount); ps.setString(4, "PAY"); ps.setLong(5, System.currentTimeMillis()); ps.executeUpdate();
            }
            connection.commit(); return true;
        } catch (Exception e) { connection.rollback(); if (e instanceof SQLException se) throw se; return false; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized void logTransaction(UUID from, UUID to, long amount, String type) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO transactions(from_uuid,to_uuid,amount,type,created_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, from == null ? null : from.toString()); ps.setString(2, to == null ? null : to.toString()); ps.setLong(3, amount); ps.setString(4, type); ps.setLong(5, System.currentTimeMillis()); ps.executeUpdate();
        }
    }

    public synchronized List<String[]> baltop(int limit) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT e.uuid,e.balance,COALESCE(u.name,'Unknown') FROM economy e LEFT JOIN users u ON u.uuid=e.uuid ORDER BY e.balance DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(20, limit))); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows.add(new String[]{rs.getString(1),String.valueOf(rs.getLong(2)),rs.getString(3)}); }
        }
        return rows;
    }

    public synchronized String rank(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT rank,expires_at FROM ranks WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "Member";
                long expiry = rs.getLong(2); if (expiry > 0 && expiry < System.currentTimeMillis()) return "Member";
                return rs.getString(1);
            }
        }
    }

    public synchronized void setRank(UUID uuid, String rank, long expiresAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ranks(uuid,rank,expires_at) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET rank=excluded.rank,expires_at=excluded.expires_at")) {
            ps.setString(1,uuid.toString()); ps.setString(2,rank); ps.setLong(3,expiresAt); ps.executeUpdate();
        }
    }

    private synchronized void seedRank(String name, String prefix, int homeLimit, int priority, int staffLevel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO rank_definitions(name,prefix,home_limit,priority,staff_level) VALUES(?,?,?,?,?)")) {
            ps.setString(1, name); ps.setString(2, prefix); ps.setInt(3, homeLimit);
            ps.setInt(4, priority); ps.setInt(5, staffLevel); ps.executeUpdate();
        }
    }

    public synchronized void createRank(String name, String prefix, int homeLimit, int priority, int staffLevel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO rank_definitions(name,prefix,home_limit,priority,staff_level) VALUES(?,?,?,?,?)")) {
            ps.setString(1, name); ps.setString(2, prefix); ps.setInt(3, Math.max(1, Math.min(100, homeLimit)));
            ps.setInt(4, priority); ps.setInt(5, Math.max(0, Math.min(3, staffLevel))); ps.executeUpdate();
        }
    }

    public synchronized void updateRankPrefix(String name, String prefix) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE rank_definitions SET prefix=? WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, prefix); ps.setString(2, name); if (ps.executeUpdate() == 0) throw new SQLException("Rank not found: " + name);
        }
    }

    public synchronized boolean deleteRank(String name) throws SQLException {
        if (name.equalsIgnoreCase("Member") || name.equalsIgnoreCase("Staff") || name.equalsIgnoreCase("Admin") || name.equalsIgnoreCase("Owner")) return false;
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement("UPDATE ranks SET rank='Member',expires_at=0 WHERE rank=? COLLATE NOCASE");
             PreparedStatement delete = connection.prepareStatement("DELETE FROM rank_definitions WHERE name=?")) {
            update.setString(1, name); update.executeUpdate();
            delete.setString(1, name); boolean removed = delete.executeUpdate() > 0;
            connection.commit(); return removed;
        } catch (SQLException e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized boolean rankExists(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM rank_definitions WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, name); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public synchronized int staffLevel(String rank) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT staff_level FROM rank_definitions WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, rank); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public synchronized int homeLimit(String rank) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT home_limit FROM rank_definitions WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, rank); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 1; }
        }
    }

    public synchronized int rankPriority(String rank) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT priority FROM rank_definitions WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, rank); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public synchronized String rankPrefix(String rank) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT prefix FROM rank_definitions WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, rank); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : "§7[Member]"; }
        }
    }

    public synchronized List<String[]> rankDefinitions() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT name,prefix,home_limit,priority,staff_level FROM rank_definitions ORDER BY priority DESC,name COLLATE NOCASE")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3)), String.valueOf(rs.getInt(4)), String.valueOf(rs.getInt(5))});
            }
        }
        return rows;
    }

    public synchronized int hearts(UUID uuid, int defaultHearts) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT hearts FROM lifesteal WHERE uuid=?")) {
            ps.setString(1,uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : defaultHearts; }
        }
    }

    public synchronized void setHearts(UUID uuid, int hearts) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO lifesteal(uuid,hearts) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET hearts=excluded.hearts")) {
            ps.setString(1,uuid.toString()); ps.setInt(2,hearts); ps.executeUpdate();
        }
    }

    public synchronized void addLifeStealParticipant(long eventStartedAt, UUID uuid) throws SQLException {
        if(eventStartedAt<=0)return;
        try(PreparedStatement ps=connection.prepareStatement("INSERT OR IGNORE INTO lifesteal_event_players(event_started_at,uuid) VALUES(?,?)")) { ps.setLong(1,eventStartedAt); ps.setString(2,uuid.toString()); ps.executeUpdate(); }
    }

    public synchronized boolean isLifeStealParticipant(long eventStartedAt, UUID uuid) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT 1 FROM lifesteal_event_players WHERE event_started_at=? AND uuid=?")) {
            ps.setLong(1,eventStartedAt); ps.setString(2,uuid.toString());
            try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }
    }

    public synchronized List<String[]> eventHeartTop(long eventStartedAt,int limit) throws SQLException {
        List<String[]> rows=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT p.uuid,l.hearts,COALESCE(u.name,'Unknown') FROM lifesteal_event_players p JOIN lifesteal l ON l.uuid=p.uuid LEFT JOIN users u ON u.uuid=p.uuid WHERE p.event_started_at=? ORDER BY l.hearts DESC,p.uuid LIMIT ?")) {
            ps.setLong(1,eventStartedAt);ps.setInt(2,Math.max(1,Math.min(100,limit)));
            try(ResultSet rs=ps.executeQuery()){while(rs.next())rows.add(new String[]{rs.getString(1),String.valueOf(rs.getInt(2)),rs.getString(3)});}
        }
        return rows;
    }

    public synchronized List<String[]> heartTop(int limit) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT l.uuid,l.hearts,COALESCE(u.name,'Unknown') FROM lifesteal l LEFT JOIN users u ON u.uuid=l.uuid ORDER BY l.hearts DESC LIMIT ?")) {
            ps.setInt(1,Math.max(1,Math.min(20,limit))); try (ResultSet rs=ps.executeQuery()) { while(rs.next()) rows.add(new String[]{rs.getString(1),String.valueOf(rs.getInt(2)),rs.getString(3)}); }
        }
        return rows;
    }

    public synchronized String getSetting(String key, String fallback) throws SQLException {
        try (PreparedStatement ps=connection.prepareStatement("SELECT value FROM settings WHERE key=?")) { ps.setString(1,key); try(ResultSet rs=ps.executeQuery()){ return rs.next()?rs.getString(1):fallback; } }
    }
    public synchronized void setting(String key, String value) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){ps.setString(1,key);ps.setString(2,value);ps.executeUpdate();}
    }

    public synchronized void addKey(UUID uuid, String crate, int amount) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO crate_keys(uuid,crate,amount) VALUES(?,?,?) ON CONFLICT(uuid,crate) DO UPDATE SET amount=amount+excluded.amount")){ps.setString(1,uuid.toString());ps.setString(2,crate.toLowerCase(Locale.ROOT));ps.setInt(3,Math.max(0,amount));ps.executeUpdate();}
    }
    public synchronized int keys(UUID uuid, String crate) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT amount FROM crate_keys WHERE uuid=? AND crate=?")){ps.setString(1,uuid.toString());ps.setString(2,crate.toLowerCase(Locale.ROOT));try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getInt(1):0;}}
    }
    public synchronized boolean takeKey(UUID uuid, String crate) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("UPDATE crate_keys SET amount=amount-1 WHERE uuid=? AND crate=? AND amount>0")){ps.setString(1,uuid.toString());ps.setString(2,crate.toLowerCase(Locale.ROOT));return ps.executeUpdate()==1;}
    }

    public synchronized void createCode(String code, long reward, int maxUses, long expiresAt) throws SQLException {
        createCodeReward(code, "money", String.valueOf(reward), 1, maxUses, expiresAt);
    }

    public synchronized void createCodeReward(String code, String type, String value, int amount, int maxUses, long expiresAt) throws SQLException {
        String canonical = code.toUpperCase(Locale.ROOT);
        if (!canonical.matches("[A-Z0-9_-]{3,32}")) throw new SQLException("Invalid code format");
        String rewardType = type.toLowerCase(Locale.ROOT);
        if (!Set.of("money", "item", "key", "rank", "shard").contains(rewardType)) throw new SQLException("Invalid reward type");
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO codes(code,reward_money,max_uses,expires_at,reward_type,reward_value,reward_amount) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, canonical);
            ps.setLong(2, rewardType.equals("money") ? Math.max(0, Long.parseLong(value)) : 0);
            ps.setInt(3, Math.max(0, maxUses));
            ps.setLong(4, Math.max(0, expiresAt));
            ps.setString(5, rewardType);
            ps.setString(6, value == null ? "" : value);
            ps.setInt(7, Math.max(1, amount));
            ps.executeUpdate();
        }
    }

    /** Returns {rewardType, rewardValue, rewardAmount}; null means invalid/expired/used. */
    public synchronized String[] redeemCodeReward(String code, UUID uuid) throws SQLException {
        connection.setAutoCommit(false);
        try {
            String canonical = code.toUpperCase(Locale.ROOT);
            String type, value;
            int uses, max, amount, active;
            long expires;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT reward_type,reward_value,reward_amount,uses,max_uses,expires_at,active,reward_money FROM codes WHERE code=?")) {
                ps.setString(1, canonical);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { connection.rollback(); return null; }
                    type = rs.getString(1);
                    value = rs.getString(2);
                    amount = rs.getInt(3);
                    uses = rs.getInt(4);
                    max = rs.getInt(5);
                    expires = rs.getLong(6);
                    active = rs.getInt(7);
                    if (type == null || type.isBlank()) type = "money";
                    if (value == null || value.isBlank()) value = String.valueOf(rs.getLong(8));
                }
            }
            if (active != 1 || (max > 0 && uses >= max) || (expires > 0 && System.currentTimeMillis() > expires)) {
                connection.rollback(); return null;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO redemptions(code,uuid,redeemed_at) VALUES(?,?,?)")) {
                ps.setString(1, canonical); ps.setString(2, uuid.toString()); ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE codes SET uses=uses+1 WHERE code=? AND active=1 AND (max_uses=0 OR uses<max_uses)")) {
                ps.setString(1, canonical);
                if (ps.executeUpdate() != 1) { connection.rollback(); return null; }
            }
            if ("money".equalsIgnoreCase(type)) {
                long reward = Math.max(0, Long.parseLong(value));
                setBalance(uuid, Math.addExact(balance(uuid), reward));
                logTransaction(null, uuid, reward, "REDEEM:" + canonical);
            }
            connection.commit();
            return new String[]{type, value, String.valueOf(Math.max(1, amount))};
        } catch (Exception e) {
            connection.rollback();
            if (e instanceof SQLException se && se.getErrorCode() != 19) throw se;
            return null;
        } finally { connection.setAutoCommit(true); }
    }

    public synchronized boolean redeemCode(String code, UUID uuid) throws SQLException {
        String[] reward = redeemCodeReward(code, uuid);
        return reward != null && "money".equalsIgnoreCase(reward[0]);
    }

    public synchronized void deleteCode(String code) throws SQLException { try(PreparedStatement ps=connection.prepareStatement("DELETE FROM codes WHERE code=?")){ps.setString(1,code.toUpperCase(Locale.ROOT));ps.executeUpdate();} }
    public synchronized List<String[]> codeList(int limit) throws SQLException {
        List<String[]> out=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT code,reward_type,reward_value,reward_amount,uses,max_uses,expires_at,active FROM codes ORDER BY rowid DESC LIMIT ?")) {
            ps.setInt(1,Math.max(1,Math.min(100,limit)));
            try(ResultSet rs=ps.executeQuery()) { while(rs.next()) out.add(new String[]{rs.getString(1),rs.getString(2),rs.getString(3),String.valueOf(rs.getInt(4)),String.valueOf(rs.getInt(5)),String.valueOf(rs.getInt(6)),String.valueOf(rs.getLong(7)),String.valueOf(rs.getInt(8))}); }
        }
        return out;
    }

    public synchronized String codeInfo(String code) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT reward_type,reward_value,reward_amount,uses,max_uses,expires_at,active FROM codes WHERE code=?")) {
            ps.setString(1,code.toUpperCase(Locale.ROOT));
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()) return "§cKhông tìm thấy code.";
                long expires=rs.getLong(6);
                return "§eCode §f"+code.toUpperCase(Locale.ROOT)+" §7| reward="+rs.getString(1)+":"+rs.getString(2)+" x"+rs.getInt(3)+" | uses="+rs.getInt(4)+"/"+(rs.getInt(5)==0?"∞":rs.getInt(5))+" | expires="+(expires==0?"never":new java.util.Date(expires))+" | active="+(rs.getInt(7)==1);
            }
        }
    }

    public synchronized long shardBalance(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT amount FROM shards WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        }
    }

    public synchronized void addShards(UUID uuid, long amount, String type) throws SQLException {
        if (amount <= 0) return;
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO shards(uuid,amount) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET amount=amount+excluded.amount")) {
                ps.setString(1, uuid.toString()); ps.setLong(2, amount); ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO shard_transactions(uuid,amount,type,created_at) VALUES(?,?,?,?)")) {
                ps.setString(1, uuid.toString()); ps.setLong(2, amount); ps.setString(3, type); ps.setLong(4, System.currentTimeMillis()); ps.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) { connection.rollback(); if (e instanceof SQLException se) throw se; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized boolean spendShards(UUID uuid, long amount, String type) throws SQLException {
        if (amount <= 0) return false;
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE shards SET amount=amount-? WHERE uuid=? AND amount>=?")) {
                ps.setLong(1, amount); ps.setString(2, uuid.toString()); ps.setLong(3, amount);
                if (ps.executeUpdate() != 1) { connection.rollback(); return false; }
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO shard_transactions(uuid,amount,type,created_at) VALUES(?,?,?,?)")) {
                ps.setString(1, uuid.toString()); ps.setLong(2, -amount); ps.setString(3, type); ps.setLong(4, System.currentTimeMillis()); ps.executeUpdate();
            }
            connection.commit(); return true;
        } catch (Exception e) { connection.rollback(); if (e instanceof SQLException se) throw se; return false; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized List<String[]> shardTop(int limit) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT s.uuid,s.amount,COALESCE(u.name,'Unknown') FROM shards s LEFT JOIN users u ON u.uuid=s.uuid ORDER BY s.amount DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(20, limit))); try (ResultSet rs=ps.executeQuery()) { while (rs.next()) out.add(new String[]{rs.getString(1), String.valueOf(rs.getLong(2)), rs.getString(3)}); }
        }
        return out;
    }

    public synchronized long createRankPurchase(UUID uuid, String name, String rank, int priceVnd) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO rank_purchase_requests(uuid,player_name,rank_name,price_vnd,status,created_at) VALUES(?,?,?,?,'PENDING',?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setString(3, rank); ps.setInt(4, priceVnd); ps.setLong(5, System.currentTimeMillis()); ps.executeUpdate();
            try (ResultSet rs=ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        }
        throw new SQLException("Could not create rank purchase request");
    }

    public synchronized String[] rankPurchase(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT uuid,player_name,rank_name,price_vnd,status FROM rank_purchase_requests WHERE id=?")) {
            ps.setLong(1,id); try (ResultSet rs=ps.executeQuery()) { return rs.next() ? new String[]{rs.getString(1),rs.getString(2),rs.getString(3),String.valueOf(rs.getInt(4)),rs.getString(5)} : null; }
        }
    }

    public synchronized List<String[]> pendingRankPurchases(int limit) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,player_name,rank_name,price_vnd,created_at FROM rank_purchase_requests WHERE status='PENDING' ORDER BY id LIMIT ?")) {
            ps.setInt(1,Math.max(1,Math.min(50,limit))); try(ResultSet rs=ps.executeQuery()) { while(rs.next()) out.add(new String[]{String.valueOf(rs.getLong(1)),rs.getString(2),rs.getString(3),String.valueOf(rs.getInt(4)),String.valueOf(rs.getLong(5))}); }
        }
        return out;
    }

    public synchronized String[] confirmRankPurchase(long id) throws SQLException {
        connection.setAutoCommit(false);
        try {
            String uuid, name, rank;
            try (PreparedStatement ps=connection.prepareStatement("SELECT uuid,player_name,rank_name FROM rank_purchase_requests WHERE id=? AND status='PENDING'")) {
                ps.setLong(1,id); try(ResultSet rs=ps.executeQuery()) { if(!rs.next()) { connection.rollback(); return null; } uuid=rs.getString(1); name=rs.getString(2); rank=rs.getString(3); }
            }
            try (PreparedStatement ps=connection.prepareStatement("INSERT INTO ranks(uuid,rank,expires_at) VALUES(?,?,0) ON CONFLICT(uuid) DO UPDATE SET rank=excluded.rank,expires_at=0")) {
                ps.setString(1,uuid); ps.setString(2,rank); ps.executeUpdate();
            }
            try (PreparedStatement ps=connection.prepareStatement("UPDATE rank_purchase_requests SET status='PAID',confirmed_at=? WHERE id=? AND status='PENDING'")) {
                ps.setLong(1,System.currentTimeMillis()); ps.setLong(2,id); if(ps.executeUpdate()!=1) { connection.rollback(); return null; }
            }
            connection.commit(); return new String[]{uuid,name,rank};
        } catch(Exception e) { connection.rollback(); if(e instanceof SQLException se) throw se; return null; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized boolean createBounty(UUID target, UUID placer, long amount) throws SQLException {
        if (target.equals(placer) || amount <= 0) return false;
        connection.setAutoCommit(false);
        try {
            long bal=balance(placer); if (bal < amount) { connection.rollback(); return false; }
            setBalance(placer,bal-amount);
            try (PreparedStatement ps=connection.prepareStatement("INSERT INTO bounties(target_uuid,placer_uuid,amount,active,created_at) VALUES(?,?,?,1,?)")) {
                ps.setString(1,target.toString()); ps.setString(2,placer.toString()); ps.setLong(3,amount); ps.setLong(4,System.currentTimeMillis()); ps.executeUpdate();
            }
            logTransaction(placer,null,amount,"BOUNTY_CREATE"); connection.commit(); return true;
        } catch (Exception e) { connection.rollback(); if(e instanceof SQLException se) throw se; return false; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized long bountyTotal(UUID target) throws SQLException {
        try (PreparedStatement ps=connection.prepareStatement("SELECT COALESCE(SUM(amount),0) FROM bounties WHERE target_uuid=? AND active=1")) { ps.setString(1,target.toString()); try(ResultSet rs=ps.executeQuery()) { return rs.next()?rs.getLong(1):0L; } }
    }

    public synchronized List<String[]> bountyTop(int limit) throws SQLException {
        List<String[]> out=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT b.target_uuid,COALESCE(SUM(b.amount),0),COALESCE(u.name,'Unknown') FROM bounties b LEFT JOIN users u ON u.uuid=b.target_uuid WHERE b.active=1 GROUP BY b.target_uuid ORDER BY SUM(b.amount) DESC LIMIT ?")) {
            ps.setInt(1,Math.max(1,Math.min(20,limit))); try(ResultSet rs=ps.executeQuery()){while(rs.next()) out.add(new String[]{rs.getString(1),String.valueOf(rs.getLong(2)),rs.getString(3)});}
        }
        return out;
    }

    /** Atomically pays a LifeSteal event once. Map order is irrelevant; event marker prevents double payout after restart. */
    public synchronized boolean payLifeStealEvent(long eventStartedAt, Map<UUID,Long> payouts) throws SQLException {
        if(eventStartedAt<=0) return false;
        connection.setAutoCommit(false);
        try {
            long previous=Long.parseLong(getSetting("lifesteal.rewardedEventStartedAt","0"));
            if(previous==eventStartedAt){connection.rollback();return false;}
            for(Map.Entry<UUID,Long> entry:payouts.entrySet()) {
                long amount=entry.getValue()==null?0:entry.getValue();
                if(amount<=0) continue;
                UUID id=entry.getKey(); setBalance(id,Math.addExact(balance(id),amount)); logTransaction(null,id,amount,"LIFESTEAL_EVENT_REWARD");
            }
            setting("lifesteal.rewardedEventStartedAt",String.valueOf(eventStartedAt));
            connection.commit(); return true;
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException("LifeSteal payout failed",e);}
        finally{connection.setAutoCommit(true);}
    }

    public synchronized long claimBounty(UUID target, UUID killer) throws SQLException {
        if(target.equals(killer)) return 0L;
        connection.setAutoCommit(false);
        try {
            long total=0L;
            try(PreparedStatement ps=connection.prepareStatement("SELECT COALESCE(SUM(amount),0) FROM bounties WHERE target_uuid=? AND active=1")) { ps.setString(1,target.toString()); try(ResultSet rs=ps.executeQuery()) { if(rs.next()) total=rs.getLong(1); } }
            if(total<=0) { connection.rollback(); return 0L; }
            try(PreparedStatement ps=connection.prepareStatement("UPDATE bounties SET active=0,claimed_by=?,claimed_at=? WHERE target_uuid=? AND active=1")) { ps.setString(1,killer.toString()); ps.setLong(2,System.currentTimeMillis()); ps.setString(3,target.toString()); ps.executeUpdate(); }
            setBalance(killer,Math.addExact(balance(killer),total)); logTransaction(null,killer,total,"BOUNTY_CLAIM"); connection.commit(); return total;
        } catch(Exception e) { connection.rollback(); if(e instanceof SQLException se) throw se; return 0L; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized void saveHome(UUID uuid,String name,String world,double x,double y,double z,float yaw,float pitch) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO homes(uuid,name,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(uuid,name) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch")){ps.setString(1,uuid.toString());ps.setString(2,name.toLowerCase(Locale.ROOT));ps.setString(3,world);ps.setDouble(4,x);ps.setDouble(5,y);ps.setDouble(6,z);ps.setFloat(7,yaw);ps.setFloat(8,pitch);ps.executeUpdate();}
    }
    public synchronized double[] home(UUID uuid,String name) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT world,x,y,z,yaw,pitch FROM homes WHERE uuid=? AND name=?")){ps.setString(1,uuid.toString());ps.setString(2,name.toLowerCase(Locale.ROOT));try(ResultSet rs=ps.executeQuery()){if(!rs.next())return null;return new double[]{rs.getDouble(2),rs.getDouble(3),rs.getDouble(4),rs.getFloat(5),rs.getFloat(6)};}}
    }
    public synchronized String homeWorld(UUID uuid,String name) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT world FROM homes WHERE uuid=? AND name=?")){ps.setString(1,uuid.toString());ps.setString(2,name.toLowerCase(Locale.ROOT));try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}}
    }
    public synchronized boolean deleteHome(UUID uuid,String name) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("DELETE FROM homes WHERE uuid=? AND name=?")){ps.setString(1,uuid.toString());ps.setString(2,name.toLowerCase(Locale.ROOT));return ps.executeUpdate()>0;}}
    public synchronized int homeCount(UUID uuid) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("SELECT COUNT(*) FROM homes WHERE uuid=?")){ps.setString(1,uuid.toString());try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getInt(1):0;}}}
    public synchronized List<String> homeNames(UUID uuid) throws SQLException {List<String> out=new ArrayList<>();try(PreparedStatement ps=connection.prepareStatement("SELECT name FROM homes WHERE uuid=? ORDER BY name")){ps.setString(1,uuid.toString());try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(rs.getString(1));}}return out;}

    public synchronized void saveWarp(String name,String world,double x,double y,double z,float yaw,float pitch) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("INSERT INTO warps(name,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch")){ps.setString(1,name.toLowerCase(Locale.ROOT));ps.setString(2,world);ps.setDouble(3,x);ps.setDouble(4,y);ps.setDouble(5,z);ps.setFloat(6,yaw);ps.setFloat(7,pitch);ps.executeUpdate();}}
    public synchronized double[] warp(String name) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("SELECT x,y,z,yaw,pitch FROM warps WHERE name=?")){ps.setString(1,name.toLowerCase(Locale.ROOT));try(ResultSet rs=ps.executeQuery()){return rs.next()?new double[]{rs.getDouble(1),rs.getDouble(2),rs.getDouble(3),rs.getFloat(4),rs.getFloat(5)}:null;}}}
    public synchronized String warpWorld(String name) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("SELECT world FROM warps WHERE name=?")){ps.setString(1,name.toLowerCase(Locale.ROOT));try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}}}
    public synchronized boolean deleteWarp(String name) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("DELETE FROM warps WHERE name=?")){ps.setString(1,name.toLowerCase(Locale.ROOT));return ps.executeUpdate()>0;}}

    public synchronized void addPunishment(UUID uuid, String type, String reason, long expiresAt, String staff) throws SQLException {
        try (PreparedStatement ps=connection.prepareStatement("INSERT INTO punishments(uuid,type,reason,expires_at,staff,created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1,uuid.toString()); ps.setString(2,type.toUpperCase(Locale.ROOT)); ps.setString(3,reason); ps.setLong(4,expiresAt); ps.setString(5,staff); ps.setLong(6,System.currentTimeMillis()); ps.executeUpdate();
        }
    }

    public synchronized String[] activePunishment(UUID uuid, String type) throws SQLException {
        try (PreparedStatement ps=connection.prepareStatement("SELECT id,reason,expires_at,staff FROM punishments WHERE uuid=? AND type=? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1,uuid.toString()); ps.setString(2,type.toUpperCase(Locale.ROOT));
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()) return null;
                long expires=rs.getLong(3);
                if(expires>0 && expires<System.currentTimeMillis()) { removePunishment(uuid,type); return null; }
                return new String[]{String.valueOf(rs.getLong(1)),rs.getString(2),String.valueOf(expires),rs.getString(4)};
            }
        }
    }

    public synchronized boolean removePunishment(UUID uuid, String type) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("DELETE FROM punishments WHERE uuid=? AND type=?")) { ps.setString(1,uuid.toString()); ps.setString(2,type.toUpperCase(Locale.ROOT)); return ps.executeUpdate()>0; }
    }

    public synchronized void addPlaytime(UUID uuid,long seconds) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("INSERT INTO playtime(uuid,seconds) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET seconds=seconds+excluded.seconds")){ps.setString(1,uuid.toString());ps.setLong(2,seconds);ps.executeUpdate();}}
    public synchronized long playtime(UUID uuid) throws SQLException {try(PreparedStatement ps=connection.prepareStatement("SELECT seconds FROM playtime WHERE uuid=?")){ps.setString(1,uuid.toString());try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getLong(1):0;}}}

    /** Atomically claims one daily reward and maintains a consecutive-day streak. Returns -1 when already claimed today. */
    public synchronized long claimDailyReward(UUID uuid, long epochDay, long baseReward, long streakBonus) throws SQLException {
        String lastKey="daily.last."+uuid, streakKey="daily.streak."+uuid;
        connection.setAutoCommit(false);
        try {
            long last=Long.parseLong(getSetting(lastKey,"-1"));
            if(last>=epochDay){connection.rollback();return -1L;}
            long streak=last==epochDay-1?Long.parseLong(getSetting(streakKey,"0"))+1:1;
            long reward=Math.addExact(Math.max(0,baseReward),Math.multiplyExact(Math.min(7,Math.max(0,streak-1)),Math.max(0,streakBonus)));
            setting(lastKey,String.valueOf(epochDay)); setting(streakKey,String.valueOf(streak));
            setBalance(uuid,Math.addExact(balance(uuid),reward)); logTransaction(null,uuid,reward,"DAILY_STREAK");
            connection.commit(); return reward;
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException("Daily reward transaction failed",e);}
        finally{connection.setAutoCommit(true);}
    }

    public synchronized long dailyStreak(UUID uuid) throws SQLException {
        return Long.parseLong(getSetting("daily.streak."+uuid,"0"));
    }

    /** Claims all unclaimed playtime milestones in one transaction. */
    public synchronized long claimPlaytimeReward(UUID uuid, long totalSeconds, long intervalSeconds, long rewardPerMilestone) throws SQLException {
        if(intervalSeconds<=0||rewardPerMilestone<=0)return 0L;
        String key="playreward.claimed."+uuid;
        connection.setAutoCommit(false);
        try {
            long due=Math.max(0,totalSeconds/intervalSeconds);
            long claimed=Long.parseLong(getSetting(key,"0"));
            if(due<=claimed){connection.rollback();return 0L;}
            long milestones=due-claimed;
            long reward=Math.multiplyExact(milestones,rewardPerMilestone);
            setting(key,String.valueOf(due)); setBalance(uuid,Math.addExact(balance(uuid),reward));
            logTransaction(null,uuid,reward,"PLAYTIME_REWARD"); connection.commit(); return reward;
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException("Playtime reward transaction failed",e);}
        finally{connection.setAutoCommit(true);}
    }


    public synchronized List<String[]> recentTransactions(int limit) throws SQLException {List<String[]> out=new ArrayList<>();try(PreparedStatement ps=connection.prepareStatement("SELECT COALESCE(u.name,'System'),t.amount,t.type,t.created_at FROM transactions t LEFT JOIN users u ON u.uuid=t.to_uuid ORDER BY t.id DESC LIMIT ?")){ps.setInt(1,Math.max(1,Math.min(50,limit)));try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(new String[]{rs.getString(1),String.valueOf(rs.getLong(2)),rs.getString(3),String.valueOf(rs.getLong(4))});}}return out;}



    // ---- Haven stacked spawner storage ----
    public synchronized void saveSpawner(String world,int x,int y,int z,String mob,int stack,String owner) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO haven_spawners(world,x,y,z,mob,stack,owner_uuid) VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET mob=excluded.mob,stack=excluded.stack,owner_uuid=excluded.owner_uuid")){
            ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);ps.setString(5,mob);ps.setInt(6,Math.max(1,stack));ps.setString(7,owner);ps.executeUpdate();
        }
    }
    public synchronized String[] spawner(String world,int x,int y,int z) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("SELECT mob,stack,owner_uuid,stored_xp FROM haven_spawners WHERE world=? AND x=? AND y=? AND z=?")){
            ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);try(ResultSet rs=ps.executeQuery()){return rs.next()?new String[]{rs.getString(1),String.valueOf(rs.getInt(2)),rs.getString(3),String.valueOf(rs.getLong(4))}:null;}
        }
    }
    public synchronized List<String[]> allSpawners(int limit) throws SQLException {
        List<String[]> out=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT world,x,y,z,mob,stack,owner_uuid,stored_xp FROM haven_spawners ORDER BY world,x,y,z LIMIT ?")){
            ps.setInt(1,Math.max(1,Math.min(1000,limit)));
            try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(new String[]{rs.getString(1),String.valueOf(rs.getInt(2)),String.valueOf(rs.getInt(3)),String.valueOf(rs.getInt(4)),rs.getString(5),String.valueOf(rs.getInt(6)),rs.getString(7),String.valueOf(rs.getLong(8))});}
        }
        return out;
    }
    public synchronized boolean changeSpawnerStack(String world,int x,int y,int z,int delta) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("UPDATE haven_spawners SET stack=stack+? WHERE world=? AND x=? AND y=? AND z=? AND stack+?>=1")){
            ps.setInt(1,delta);ps.setString(2,world);ps.setInt(3,x);ps.setInt(4,y);ps.setInt(5,z);ps.setInt(6,delta);return ps.executeUpdate()==1;
        }
    }
    public synchronized boolean deleteSpawner(String world,int x,int y,int z) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("DELETE FROM haven_spawners WHERE world=? AND x=? AND y=? AND z=?")){ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);return ps.executeUpdate()>0;}
    }
    public synchronized void addSpawnerXp(String world,int x,int y,int z,int amount) throws SQLException {
        addSpawnerXp(world,x,y,z,amount,1_000_000);
    }
    public synchronized void addSpawnerXp(String world,int x,int y,int z,int amount,int maxAmount) throws SQLException {
        if(amount<=0)return;int cap=Math.max(1,maxAmount);
        try(PreparedStatement ps=connection.prepareStatement("UPDATE haven_spawners SET stored_xp=MIN(?,stored_xp+?) WHERE world=? AND x=? AND y=? AND z=?")){
            ps.setInt(1,cap);ps.setInt(2,Math.min(amount,cap));ps.setString(3,world);ps.setInt(4,x);ps.setInt(5,y);ps.setInt(6,z);ps.executeUpdate();
        }
    }
    public synchronized int takeSpawnerXp(String world,int x,int y,int z) throws SQLException {
        connection.setAutoCommit(false);
        try {
            int stored=0;
            try(PreparedStatement q=connection.prepareStatement("SELECT stored_xp FROM haven_spawners WHERE world=? AND x=? AND y=? AND z=?")) {
                q.setString(1,world);q.setInt(2,x);q.setInt(3,y);q.setInt(4,z);
                try(ResultSet rs=q.executeQuery()){if(rs.next())stored=rs.getInt(1);}
            }
            if(stored>0)try(PreparedStatement u=connection.prepareStatement("UPDATE haven_spawners SET stored_xp=0 WHERE world=? AND x=? AND y=? AND z=?")) {
                u.setString(1,world);u.setInt(2,x);u.setInt(3,y);u.setInt(4,z);u.executeUpdate();
            }
            connection.commit();return stored;
        } catch(Exception e) { connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e); }
        finally { connection.setAutoCommit(true); }
    }
    public synchronized void addSpawnerLoot(String world,int x,int y,int z,String material,int amount) throws SQLException {
        addSpawnerLoot(world,x,y,z,material,amount,100_000);
    }
    public synchronized void addSpawnerLoot(String world,int x,int y,int z,String material,int amount,int maxAmount) throws SQLException {
        if(amount<=0)return;int cap=Math.max(1,maxAmount);
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO haven_spawner_loot(world,x,y,z,material,amount) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z,material) DO UPDATE SET amount=MIN(?,amount+excluded.amount)")){
            ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);ps.setString(5,material);ps.setInt(6,Math.min(amount,cap));ps.setInt(7,cap);ps.executeUpdate();
        }
    }
    public synchronized List<String[]> spawnerLoot(String world,int x,int y,int z) throws SQLException {
        List<String[]> out=new ArrayList<>();try(PreparedStatement ps=connection.prepareStatement("SELECT material,amount FROM haven_spawner_loot WHERE world=? AND x=? AND y=? AND z=? AND amount>0 ORDER BY material")){ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(new String[]{rs.getString(1),String.valueOf(rs.getInt(2))});}}return out;
    }
    public synchronized int takeSpawnerLoot(String world,int x,int y,int z,String material) throws SQLException {
        connection.setAutoCommit(false);try{int amount=0;try(PreparedStatement q=connection.prepareStatement("SELECT amount FROM haven_spawner_loot WHERE world=? AND x=? AND y=? AND z=? AND material=?")){q.setString(1,world);q.setInt(2,x);q.setInt(3,y);q.setInt(4,z);q.setString(5,material);try(ResultSet rs=q.executeQuery()){if(rs.next())amount=rs.getInt(1);}}try(PreparedStatement d=connection.prepareStatement("DELETE FROM haven_spawner_loot WHERE world=? AND x=? AND y=? AND z=? AND material=?")){d.setString(1,world);d.setInt(2,x);d.setInt(3,y);d.setInt(4,z);d.setString(5,material);d.executeUpdate();}connection.commit();return amount;}catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}finally{connection.setAutoCommit(true);}
    }

    // ---- Auction House ----
    public synchronized long createAuction(String sellerUuid,String sellerName,String itemData,String material,int amount,long price,long expiresAt) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO haven_ah(seller_uuid,seller_name,item_data,material,amount,price,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?, 'ACTIVE')",Statement.RETURN_GENERATED_KEYS)){
            ps.setString(1,sellerUuid);ps.setString(2,sellerName);ps.setString(3,itemData);ps.setString(4,material);ps.setInt(5,amount);ps.setLong(6,price);ps.setLong(7,System.currentTimeMillis());ps.setLong(8,expiresAt);ps.executeUpdate();try(ResultSet rs=ps.getGeneratedKeys()){return rs.next()?rs.getLong(1):-1;}
        }
    }
    public synchronized List<String[]> auctionListings(int limit,int offset) throws SQLException {
        List<String[]> out=new ArrayList<>();try(PreparedStatement ps=connection.prepareStatement("SELECT id,seller_uuid,seller_name,item_data,material,amount,price,created_at,expires_at FROM haven_ah WHERE status='ACTIVE' AND expires_at>? ORDER BY id DESC LIMIT ? OFFSET ?")){ps.setLong(1,System.currentTimeMillis());ps.setInt(2,Math.max(1,Math.min(45,limit)));ps.setInt(3,Math.max(0,offset));try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(new String[]{String.valueOf(rs.getLong(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),String.valueOf(rs.getInt(6)),String.valueOf(rs.getLong(7)),String.valueOf(rs.getLong(8)),String.valueOf(rs.getLong(9))});}}return out;
    }
    /** Active listings owned by a player. Expired-but-not-yet-withdrawn listings remain visible here. */
    public synchronized List<String[]> myAuctionListings(String sellerUuid,int limit,int offset) throws SQLException {
        List<String[]> out=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT id,seller_uuid,seller_name,item_data,material,amount,price,created_at,expires_at FROM haven_ah WHERE seller_uuid=? AND status='ACTIVE' ORDER BY id DESC LIMIT ? OFFSET ?")) {
            ps.setString(1,sellerUuid);ps.setInt(2,Math.max(1,Math.min(45,limit)));ps.setInt(3,Math.max(0,offset));
            try(ResultSet rs=ps.executeQuery()) { while(rs.next()) out.add(new String[]{String.valueOf(rs.getLong(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),String.valueOf(rs.getInt(6)),String.valueOf(rs.getLong(7)),String.valueOf(rs.getLong(8)),String.valueOf(rs.getLong(9))}); }
        }
        return out;
    }
    public synchronized String[] buyAuction(long id,String buyerUuid) throws SQLException {
        connection.setAutoCommit(false);
        try {
            String[] row=null;
            try(PreparedStatement q=connection.prepareStatement("SELECT seller_uuid,seller_name,item_data,material,amount,price FROM haven_ah WHERE id=? AND status='ACTIVE' AND expires_at>?")) {
                q.setLong(1,id);q.setLong(2,System.currentTimeMillis());
                try(ResultSet rs=q.executeQuery()){if(rs.next())row=new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),String.valueOf(rs.getInt(5)),String.valueOf(rs.getLong(6))};}
            }
            if(row==null || row[0].equalsIgnoreCase(buyerUuid)){connection.rollback();return null;}
            long price=Long.parseLong(row[5]);UUID buyer=UUID.fromString(buyerUuid),seller=UUID.fromString(row[0]);
            long buyerBalance=balance(buyer);if(buyerBalance<price){connection.rollback();return null;}
            try(PreparedStatement u=connection.prepareStatement("UPDATE haven_ah SET status='SOLD' WHERE id=? AND status='ACTIVE' AND expires_at>?")) {
                u.setLong(1,id);u.setLong(2,System.currentTimeMillis());if(u.executeUpdate()!=1){connection.rollback();return null;}
            }
            setBalance(buyer,Math.subtractExact(buyerBalance,price));
            setBalance(seller,Math.addExact(balance(seller),price));
            logTransaction(buyer,seller,price,"AH_BUY:"+id);
            connection.commit();return row;
        } catch(Exception e) { connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e); }
        finally { connection.setAutoCommit(true); }
    }
    public synchronized boolean reverseAuctionPurchase(long id,String buyerUuid,String sellerUuid,long price) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try(PreparedStatement q=connection.prepareStatement("SELECT status FROM haven_ah WHERE id=?")){q.setLong(1,id);try(ResultSet rs=q.executeQuery()){if(!rs.next()||!"SOLD".equals(rs.getString(1))){connection.rollback();return false;}}}
            UUID buyer=UUID.fromString(buyerUuid),seller=UUID.fromString(sellerUuid);
            long sellerBalance=balance(seller);if(sellerBalance<price){connection.rollback();return false;}
            setBalance(seller,sellerBalance-price);setBalance(buyer,Math.addExact(balance(buyer),price));
            try(PreparedStatement u=connection.prepareStatement("UPDATE haven_ah SET status='ACTIVE' WHERE id=? AND status='SOLD'")){u.setLong(1,id);if(u.executeUpdate()!=1){connection.rollback();return false;}}
            logTransaction(seller,buyer,price,"AH_REFUND:"+id);connection.commit();return true;
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}
        finally{connection.setAutoCommit(true);}
    }
    public synchronized String[] claimAuction(long id,String buyerUuid) throws SQLException { return buyAuction(id,buyerUuid); }
    public synchronized boolean reopenAuction(long id) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("UPDATE haven_ah SET status='ACTIVE' WHERE id=? AND status='SOLD'")) { ps.setLong(1,id);return ps.executeUpdate()==1; }
    }
    public synchronized String[] cancelAuction(long id,String ownerUuid) throws SQLException {
        connection.setAutoCommit(false);try{String[] row=null;try(PreparedStatement q=connection.prepareStatement("SELECT item_data,material,amount FROM haven_ah WHERE id=? AND seller_uuid=? AND status='ACTIVE'")){q.setLong(1,id);q.setString(2,ownerUuid);try(ResultSet rs=q.executeQuery()){if(rs.next())row=new String[]{rs.getString(1),rs.getString(2),String.valueOf(rs.getInt(3))};}}if(row==null){connection.rollback();return null;}try(PreparedStatement u=connection.prepareStatement("UPDATE haven_ah SET status='CANCELLED' WHERE id=? AND seller_uuid=? AND status='ACTIVE'")){u.setLong(1,id);u.setString(2,ownerUuid);if(u.executeUpdate()!=1){connection.rollback();return null;}}connection.commit();return row;}catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}finally{connection.setAutoCommit(true);}
    }

    // ---- Player buy orders with escrow ----
    public synchronized long createOrder(String buyerUuid,String buyerName,String material,int amount,long unitPrice,long expiresAt) throws SQLException {
        long escrow=Math.multiplyExact((long)amount,unitPrice);connection.setAutoCommit(false);
        try {
            UUID buyer=UUID.fromString(buyerUuid);long current=balance(buyer);if(current<escrow){connection.rollback();return -1;}
            setBalance(buyer,current-escrow);
            long id;
            try(PreparedStatement ps=connection.prepareStatement("INSERT INTO haven_orders(buyer_uuid,buyer_name,material,remaining,unit_price,escrow,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?, 'ACTIVE')",Statement.RETURN_GENERATED_KEYS)){
                ps.setString(1,buyerUuid);ps.setString(2,buyerName);ps.setString(3,material);ps.setInt(4,amount);ps.setLong(5,unitPrice);ps.setLong(6,escrow);ps.setLong(7,System.currentTimeMillis());ps.setLong(8,expiresAt);ps.executeUpdate();try(ResultSet rs=ps.getGeneratedKeys()){id=rs.next()?rs.getLong(1):-1;}
            }
            logTransaction(buyer,null,escrow,"ORDER_ESCROW");connection.commit();return id;
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}
        finally{connection.setAutoCommit(true);}
    }
    public synchronized List<String[]> activeOrders(int limit,int offset) throws SQLException {
        List<String[]> out=new ArrayList<>();try(PreparedStatement ps=connection.prepareStatement("SELECT id,buyer_uuid,buyer_name,material,remaining,unit_price,escrow,created_at,expires_at FROM haven_orders WHERE status='ACTIVE' AND expires_at>? ORDER BY id DESC LIMIT ? OFFSET ?")){ps.setLong(1,System.currentTimeMillis());ps.setInt(2,Math.max(1,Math.min(45,limit)));ps.setInt(3,Math.max(0,offset));try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(new String[]{String.valueOf(rs.getLong(1)),rs.getString(2),rs.getString(3),rs.getString(4),String.valueOf(rs.getInt(5)),String.valueOf(rs.getLong(6)),String.valueOf(rs.getLong(7)),String.valueOf(rs.getLong(8)),String.valueOf(rs.getLong(9))});}}return out;
    }
    public synchronized String[] fillOrder(long id,int amount) throws SQLException {
        if(amount<=0)return null;connection.setAutoCommit(false);try{String[] row=null;try(PreparedStatement q=connection.prepareStatement("SELECT buyer_uuid,material,remaining,unit_price,escrow FROM haven_orders WHERE id=? AND status='ACTIVE' AND expires_at>?")){q.setLong(1,id);q.setLong(2,System.currentTimeMillis());try(ResultSet rs=q.executeQuery()){if(rs.next())row=new String[]{rs.getString(1),rs.getString(2),String.valueOf(rs.getInt(3)),String.valueOf(rs.getLong(4)),String.valueOf(rs.getLong(5))};}}if(row==null||amount>Integer.parseInt(row[2])){connection.rollback();return null;}int remaining=Integer.parseInt(row[2])-amount;long payout=Math.multiplyExact(amount,Long.parseLong(row[3]));long escrow=Long.parseLong(row[4])-payout;try(PreparedStatement u=connection.prepareStatement("UPDATE haven_orders SET remaining=?,escrow=?,status=? WHERE id=? AND status='ACTIVE'")){u.setInt(1,remaining);u.setLong(2,escrow);u.setString(3,remaining==0?"FILLED":"ACTIVE");u.setLong(4,id);if(u.executeUpdate()!=1){connection.rollback();return null;}}connection.commit();return new String[]{row[0],row[1],String.valueOf(payout)};}catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}finally{connection.setAutoCommit(true);}
    }
    public synchronized String[] fillOrder(long id,int amount,String sellerUuid) throws SQLException {
        if(amount<=0)return null;connection.setAutoCommit(false);
        try {
            String[] row=null;
            try(PreparedStatement q=connection.prepareStatement("SELECT buyer_uuid,material,remaining,unit_price,escrow FROM haven_orders WHERE id=? AND status='ACTIVE' AND expires_at>?")){
                q.setLong(1,id);q.setLong(2,System.currentTimeMillis());try(ResultSet rs=q.executeQuery()){if(rs.next())row=new String[]{rs.getString(1),rs.getString(2),String.valueOf(rs.getInt(3)),String.valueOf(rs.getLong(4)),String.valueOf(rs.getLong(5))};}
            }
            if(row==null||amount>Integer.parseInt(row[2])||row[0].equalsIgnoreCase(sellerUuid)){connection.rollback();return null;}
            long payout=Math.multiplyExact(amount,Long.parseLong(row[3]));long escrow=Long.parseLong(row[4])-payout;int remaining=Integer.parseInt(row[2])-amount;
            try(PreparedStatement u=connection.prepareStatement("UPDATE haven_orders SET remaining=?,escrow=?,status=? WHERE id=? AND status='ACTIVE'")){
                u.setInt(1,remaining);u.setLong(2,escrow);u.setString(3,remaining==0?"FILLED":"ACTIVE");u.setLong(4,id);if(u.executeUpdate()!=1){connection.rollback();return null;}
            }
            try(PreparedStatement delivery=connection.prepareStatement("INSERT INTO haven_order_deliveries(buyer_uuid,material,amount,created_at) VALUES(?,?,?,?)")) {
                delivery.setString(1,row[0]);delivery.setString(2,row[1]);delivery.setInt(3,amount);delivery.setLong(4,System.currentTimeMillis());delivery.executeUpdate();
            }
            UUID seller=UUID.fromString(sellerUuid);setBalance(seller,Math.addExact(balance(seller),payout));logTransaction(null,seller,payout,"ORDER_FULFILLED:"+id);
            connection.commit();return new String[]{row[0],row[1],String.valueOf(payout)};
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}
        finally{connection.setAutoCommit(true);}
    }
    public synchronized List<String[]> orderDeliveries(UUID buyer) throws SQLException {
        List<String[]> rows=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT id,material,amount FROM haven_order_deliveries WHERE buyer_uuid=? ORDER BY id")) {
            ps.setString(1,buyer.toString());
            try(ResultSet rs=ps.executeQuery()){while(rs.next())rows.add(new String[]{String.valueOf(rs.getLong(1)),rs.getString(2),String.valueOf(rs.getInt(3))});}
        }
        return rows;
    }

    public synchronized boolean deleteOrderDelivery(long id,UUID buyer) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement("DELETE FROM haven_order_deliveries WHERE id=? AND buyer_uuid=?")) {
            ps.setLong(1,id);ps.setString(2,buyer.toString());return ps.executeUpdate()==1;
        }
    }

    public synchronized boolean updateOrderDeliveryAmount(long id,UUID buyer,int amount) throws SQLException {
        if(amount<=0)return deleteOrderDelivery(id,buyer);
        try(PreparedStatement ps=connection.prepareStatement("UPDATE haven_order_deliveries SET amount=? WHERE id=? AND buyer_uuid=?")) {
            ps.setInt(1,amount);ps.setLong(2,id);ps.setString(3,buyer.toString());return ps.executeUpdate()==1;
        }
    }

    public synchronized String[] cancelOrder(long id,String ownerUuid) throws SQLException {
        connection.setAutoCommit(false);try{String[] row=null;try(PreparedStatement q=connection.prepareStatement("SELECT escrow,remaining FROM haven_orders WHERE id=? AND buyer_uuid=? AND status='ACTIVE'")){q.setLong(1,id);q.setString(2,ownerUuid);try(ResultSet rs=q.executeQuery()){if(rs.next())row=new String[]{String.valueOf(rs.getLong(1)),String.valueOf(rs.getInt(2))};}}if(row==null){connection.rollback();return null;}try(PreparedStatement u=connection.prepareStatement("UPDATE haven_orders SET status='CANCELLED',escrow=0 WHERE id=? AND buyer_uuid=? AND status='ACTIVE'")){u.setLong(1,id);u.setString(2,ownerUuid);if(u.executeUpdate()!=1){connection.rollback();return null;}}connection.commit();return row;}catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}finally{connection.setAutoCommit(true);}
    }

    public synchronized long cancelOrderAndRefund(long id,String ownerUuid) throws SQLException {
        connection.setAutoCommit(false);
        try {
            long escrow;
            try(PreparedStatement q=connection.prepareStatement("SELECT escrow FROM haven_orders WHERE id=? AND buyer_uuid=? AND status='ACTIVE'")){
                q.setLong(1,id);q.setString(2,ownerUuid);try(ResultSet rs=q.executeQuery()){if(!rs.next()){connection.rollback();return -1L;}escrow=rs.getLong(1);}
            }
            try(PreparedStatement u=connection.prepareStatement("UPDATE haven_orders SET status='CANCELLED',escrow=0 WHERE id=? AND buyer_uuid=? AND status='ACTIVE'")){
                u.setLong(1,id);u.setString(2,ownerUuid);if(u.executeUpdate()!=1){connection.rollback();return -1L;}
            }
            UUID owner=UUID.fromString(ownerUuid);setBalance(owner,Math.addExact(balance(owner),escrow));if(escrow>0)logTransaction(null,owner,escrow,"ORDER_CANCEL_REFUND");
            connection.commit();return escrow;
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}
        finally{connection.setAutoCommit(true);}
    }

    public synchronized int expireOrders() throws SQLException {
        connection.setAutoCommit(false);
        try {
            List<String[]> rows=new ArrayList<>();
            try(PreparedStatement q=connection.prepareStatement("SELECT id,buyer_uuid,escrow FROM haven_orders WHERE status='ACTIVE' AND expires_at<=?")){
                q.setLong(1,System.currentTimeMillis());try(ResultSet rs=q.executeQuery()){while(rs.next())rows.add(new String[]{String.valueOf(rs.getLong(1)),rs.getString(2),String.valueOf(rs.getLong(3))});}
            }
            int expired=0;
            for(String[] row:rows){long id=Long.parseLong(row[0]),escrow=Long.parseLong(row[2]);String owner=row[1];
                try(PreparedStatement u=connection.prepareStatement("UPDATE haven_orders SET status='EXPIRED',escrow=0 WHERE id=? AND status='ACTIVE'")){u.setLong(1,id);if(u.executeUpdate()!=1)continue;}
                UUID buyer=UUID.fromString(owner);if(escrow>0){setBalance(buyer,Math.addExact(balance(buyer),escrow));logTransaction(null,buyer,escrow,"ORDER_EXPIRED_REFUND");}expired++;
            }
            connection.commit();return expired;
        } catch(Exception e){connection.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}
        finally{connection.setAutoCommit(true);}
    }

    // ---- Expanded Ender Chest (DonutSMP 54-slot style) ----
    public synchronized String enderChestData(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT contents FROM haven_enderchest WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void saveEnderChestData(UUID uuid, String contents) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO haven_enderchest(uuid,contents,updated_at) VALUES(?,?,?) " +
                "ON CONFLICT(uuid) DO UPDATE SET contents=excluded.contents,updated_at=excluded.updated_at")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, contents == null ? "" : contents);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // ---- Player /setting preferences (KingSMP & DonutSMP style) ----
    public synchronized Map<String, Boolean> playerSettings(UUID uuid) throws SQLException {
        Map<String, Boolean> out = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT setting,enabled FROM haven_player_settings WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getInt(2) != 0);
            }
        }
        return out;
    }

    public synchronized void setPlayerSetting(UUID uuid, String setting, boolean enabled) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO haven_player_settings(uuid,setting,enabled) VALUES(?,?,?) " +
                "ON CONFLICT(uuid,setting) DO UPDATE SET enabled=excluded.enabled")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, setting.toLowerCase(Locale.ROOT));
            ps.setInt(3, enabled ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public synchronized void resetPlayerSettings(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM haven_player_settings WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    // ---- Player /stats tracking & lookup ----
    public synchronized String[] findUserByName(String name) throws SQLException {
        if (name == null || name.isBlank()) return null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT uuid,name FROM users WHERE name=? COLLATE NOCASE LIMIT 1")) {
            ps.setString(1, name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null;
            }
        }
    }

    public synchronized String userName(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM users WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Returns {kills, deaths, mobsKilled, blocksBroken, blocksPlaced}. */
    public synchronized long[] playerStats(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT kills,deaths,mobs_killed,blocks_broken,blocks_placed FROM haven_stats WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new long[]{0L, 0L, 0L, 0L, 0L};
                return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)};
            }
        }
    }

    public synchronized void addPlayerStats(UUID uuid, long kills, long deaths, long mobsKilled, long blocksBroken, long blocksPlaced) throws SQLException {
        if (kills <= 0 && deaths <= 0 && mobsKilled <= 0 && blocksBroken <= 0 && blocksPlaced <= 0) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO haven_stats(uuid,kills,deaths,mobs_killed,blocks_broken,blocks_placed) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(uuid) DO UPDATE SET " +
                "kills=kills+excluded.kills, deaths=deaths+excluded.deaths, " +
                "mobs_killed=mobs_killed+excluded.mobs_killed, " +
                "blocks_broken=blocks_broken+excluded.blocks_broken, " +
                "blocks_placed=blocks_placed+excluded.blocks_placed")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, Math.max(0L, kills));
            ps.setLong(3, Math.max(0L, deaths));
            ps.setLong(4, Math.max(0L, mobsKilled));
            ps.setLong(5, Math.max(0L, blocksBroken));
            ps.setLong(6, Math.max(0L, blocksPlaced));
            ps.executeUpdate();
        }
    }

    // ---- Haven Teams (/team) ----
    /** Returns {id, name, tag, ownerUuid, homeWorld, homeX, homeY, homeZ, homeYaw, homePitch, pvpEnabled}. */
    public synchronized String[] teamById(long teamId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,name,tag,owner_uuid,COALESCE(home_world,''),home_x,home_y,home_z,home_yaw,home_pitch,pvp_enabled FROM haven_teams WHERE id=?")) {
            ps.setLong(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{
                        String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), String.valueOf(rs.getDouble(6)), String.valueOf(rs.getDouble(7)),
                        String.valueOf(rs.getDouble(8)), String.valueOf(rs.getFloat(9)),
                        String.valueOf(rs.getFloat(10)), String.valueOf(rs.getInt(11))
                };
            }
        }
    }

    public synchronized String[] teamByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM haven_teams WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? teamById(rs.getLong(1)) : null;
            }
        }
    }

    public synchronized long playerTeamId(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT team_id FROM haven_team_members WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    public synchronized String[] teamOfPlayer(UUID uuid) throws SQLException {
        long tid = playerTeamId(uuid);
        return tid > 0 ? teamById(tid) : null;
    }

    public synchronized String teamMemberRole(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT role FROM haven_team_members WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized int teamMemberCount(long teamId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM haven_team_members WHERE team_id=?")) {
            ps.setLong(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Returns list of {uuid, name, role}. */
    public synchronized List<String[]> teamMembers(long teamId) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT m.uuid, COALESCE(u.name,'Unknown'), m.role FROM haven_team_members m " +
                "LEFT JOIN users u ON u.uuid=m.uuid WHERE m.team_id=? " +
                "ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, m.joined_at")) {
            ps.setLong(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
        }
        return out;
    }

    /** Returns list of {id, name, tag, ownerName, memberCount}. */
    public synchronized List<String[]> listTeams(int limit) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT t.id, t.name, t.tag, COALESCE(u.name,'Unknown'), " +
                "(SELECT COUNT(*) FROM haven_team_members m WHERE m.team_id=t.id) AS cnt " +
                "FROM haven_teams t LEFT JOIN users u ON u.uuid=t.owner_uuid " +
                "ORDER BY cnt DESC, t.name COLLATE NOCASE LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(45, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{
                        String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), String.valueOf(rs.getInt(5))
                });
            }
        }
        return out;
    }

    public synchronized long createTeam(String name, String tag, UUID ownerUuid) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (playerTeamId(ownerUuid) > 0) { connection.rollback(); return -1L; }
            if (teamByName(name) != null) { connection.rollback(); return -2L; }
            long now = System.currentTimeMillis();
            long teamId = -1L;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO haven_teams(name,tag,owner_uuid,pvp_enabled,created_at) VALUES(?,?,?,0,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, tag);
                ps.setString(3, ownerUuid.toString());
                ps.setLong(4, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) teamId = rs.getLong(1);
                }
            }
            if (teamId <= 0) { connection.rollback(); return -1L; }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO haven_team_members(uuid,team_id,role,joined_at) VALUES(?,?, 'OWNER',?)")) {
                ps.setString(1, ownerUuid.toString());
                ps.setLong(2, teamId);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            connection.commit();
            return teamId;
        } catch (Exception e) {
            connection.rollback();
            if (e instanceof SQLException se) throw se;
            return -1L;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized boolean addTeamMember(long teamId, UUID playerUuid) throws SQLException {
        if (playerTeamId(playerUuid) > 0) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO haven_team_members(uuid,team_id,role,joined_at) VALUES(?,?, 'MEMBER',?)")) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, teamId);
            ps.setLong(3, System.currentTimeMillis());
            return ps.executeUpdate() == 1;
        }
    }

    public synchronized boolean removeTeamMember(long teamId, UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM haven_team_members WHERE team_id=? AND uuid=?")) {
            ps.setLong(1, teamId);
            ps.setString(2, playerUuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized boolean setTeamMemberRole(long teamId, UUID playerUuid, String role) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE haven_team_members SET role=? WHERE team_id=? AND uuid=?")) {
            ps.setString(1, role.toUpperCase(Locale.ROOT));
            ps.setLong(2, teamId);
            ps.setString(3, playerUuid.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public synchronized void setTeamHome(long teamId, String world, double x, double y, double z, float yaw, float pitch) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE haven_teams SET home_world=?,home_x=?,home_y=?,home_z=?,home_yaw=?,home_pitch=? WHERE id=?")) {
            ps.setString(1, world); ps.setDouble(2, x); ps.setDouble(3, y); ps.setDouble(4, z);
            ps.setFloat(5, yaw); ps.setFloat(6, pitch); ps.setLong(7, teamId);
            ps.executeUpdate();
        }
    }

    public synchronized void setTeamPvp(long teamId, boolean enabled) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE haven_teams SET pvp_enabled=? WHERE id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, teamId);
            ps.executeUpdate();
        }
    }

    public synchronized boolean disbandTeam(long teamId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM haven_team_members WHERE team_id=?")) {
                ps.setLong(1, teamId);
                ps.executeUpdate();
            }
            boolean removed;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM haven_teams WHERE id=?")) {
                ps.setLong(1, teamId);
                removed = ps.executeUpdate() > 0;
            }
            connection.commit();
            return removed;
        } catch (Exception e) {
            connection.rollback();
            if (e instanceof SQLException se) throw se;
            return false;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void backupTo(File target) throws SQLException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new SQLException("Cannot create backup directory");
        String path = target.getAbsolutePath().replace("'", "''");
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(FULL)");
            st.execute("VACUUM INTO '" + path + "'");
        }
    }

    @Override public synchronized void close() throws SQLException { if(connection!=null && !connection.isClosed()) connection.close(); }
}
