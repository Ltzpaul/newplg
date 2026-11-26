package vn.ltzcore;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ltzcore.auth.AuthManager;
import vn.ltzcore.core.Reflect;
import vn.ltzcore.economy.EconomyManager;
import vn.ltzcore.gui.GuiManager;
import vn.ltzcore.lifesteal.LifeStealManager;
import vn.ltzcore.storage.Database;
import vn.ltzcore.util.MoneyFormat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class LTZCorePlugin extends JavaPlugin {
    private Database db;
    private AuthManager auth;
    private EconomyManager economy;
    private LifeStealManager life;
    private GuiManager gui;
    private HavenExpansion expansion;
    private final Map<UUID,TpaRequest> tpaRequests = new ConcurrentHashMap<>();
    private final Map<UUID,Map<String,Boolean>> playerSettingsCache = new ConcurrentHashMap<>();
    private final Map<UUID,long[]> pendingStats = new ConcurrentHashMap<>();
    private final Map<UUID,TeamInvite> teamInvites = new ConcurrentHashMap<>();
    private final Set<UUID> teamCreateDrafts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> teamChatToggled = ConcurrentHashMap.newKeySet();
    private final Map<UUID,String> playerTeamNames = new ConcurrentHashMap<>();
    private final Map<UUID,BaitBaseSnapshot> lastBaitBases = new ConcurrentHashMap<>();
    private final List<BaitBaseZone> activeBaitBases = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<UUID,Long> baitAlertCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID,Long> joinedAt = new ConcurrentHashMap<>();
    private final Map<UUID,Object> scoreboards = new ConcurrentHashMap<>();
    private final Map<UUID,Object> objectives = new ConcurrentHashMap<>();
    private final Map<UUID,List<String>> scoreEntries = new ConcurrentHashMap<>();
    private final Map<UUID,Object> staffAttachments = new ConcurrentHashMap<>();
    private final Map<UUID,UUID> rtpOperations = new ConcurrentHashMap<>();
    private final Map<UUID,Object> rtpDestinations = new ConcurrentHashMap<>();
    private final Map<UUID,Object> rtpOrigins = new ConcurrentHashMap<>();
    private final Map<UUID,Long> rtpCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID,Long> combatTaggedUntil = new ConcurrentHashMap<>();
    private final Map<UUID,Long> shardOnlineSince = new ConcurrentHashMap<>();
    private final Map<UUID,Map<String,String[]>> spawnSelections = new ConcurrentHashMap<>();
    private final Map<UUID,Long> spawnProtectionWarnings = new ConcurrentHashMap<>();
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private volatile SpawnRegion spawnRegionCache;
    private volatile boolean shardActionBarWarningLogged;
    private record SpawnRegion(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
    private record TpaRequest(UUID requester, boolean here) {}
    private record TeamInvite(long teamId, String teamName, UUID inviterId, String inviterName, long expiresAt) {}
    private record SavedBlock(int x, int y, int z, String material) {}
    private record BaitBaseSnapshot(String world, int spawnerX, int spawnerY, int spawnerZ, List<SavedBlock> blocks) {}
    private record BaitBaseZone(UUID creator, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        // Merge newly introduced default paths into existing user config without overwriting custom values.
        try { Reflect.call(Reflect.call(getConfig(), "options"), "copyDefaults", true); Reflect.call(this, "saveConfig"); } catch (Exception e) { getLogger().warning("Could not merge default config keys: "+e.getMessage()); }
        try { migrateShopPriceProfile(); } catch(Exception e) { getLogger().warning("Shop price migration skipped: "+e.getMessage()); }
        try { saveResource("messages.yml",false); } catch(Exception ignored) {}
        try { saveResource("scoreboard.yml",false); } catch(Exception ignored) {}
        try {
            db = new Database(new File(getDataFolder(), "database.db"));
            loadSpawnRegionCache();
            auth = new AuthManager(db);
            economy = new EconomyManager(db);
            life = new LifeStealManager(this, db,
                    cfgInt("lifesteal.starting-hearts",10), cfgInt("lifesteal.maximum-hearts",20),
                    cfgInt("lifesteal.hearts-per-kill",1), cfgInt("lifesteal.hearts-lost",1),
                    cfgInt("lifesteal.duration-seconds",0));
            gui = new GuiManager(this,db,economy,life,this::awardLifeStealRewards);
            expansion = new HavenExpansion(this, db, economy, this::admin, this::isInSpawnRegion);
            gui.setExpansion(expansion);
            registerListeners();
            expansion.registerListeners();
            Reflect.scheduleRepeating(this,this::updateAllScoreboards,20L,Math.max(20,cfgInt("scoreboard.update-interval-ticks",20)));
            // One shared 1-second ticker handles shard accrual and the action-bar countdown.
            // It does not create per-player tasks and only persists a reward once a full minute has elapsed.
            Reflect.scheduleRepeating(this,this::updateShardStatusAndActionBars,20L,20L);
            Reflect.scheduleRepeating(this,this::flushAllPendingStats,600L,600L);
            getLogger().info("LTZCore 0.5.15-Haven enabled. Spawn cuboid protection is active. SQLite is ready.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE,"LTZCore failed to initialize. Check SQLite library download and database permissions.",e);
            Reflect.callStatic("org.bukkit.Bukkit","getPluginManager");
            Reflect.call(Reflect.callStatic("org.bukkit.Bukkit","getPluginManager"),"disablePlugin",this);
        }
    }

    @Override public void onDisable() {
        if (gui != null) {
            try { gui.flushSellInventories(); } catch (Exception ignored) { }
            try { gui.flushEnderChests(); } catch (Exception ignored) { }
        }
        try { flushAllPendingStats(); } catch (Exception ignored) { }
        try { Reflect.call(Reflect.callStatic("org.bukkit.Bukkit","getScheduler"),"cancelTasks",this); } catch(Exception ignored) {}
        if(db!=null) try { db.close(); } catch(Exception e) { getLogger().log(Level.WARNING,"Could not close SQLite cleanly",e); }
        getLogger().info("LTZCore disabled.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name=command.getName().toLowerCase(Locale.ROOT);
        boolean player=isPlayer(sender);
        UUID uuid=player?uuid(sender):null;
        if(player && !auth.isAuthenticated(uuid) && !isAuthenticationCommand(name)) {
            send(sender,"§cBạn cần đăng nhập trước. Dùng /login hoặc /register (có thể dùng /havenlogin và /havenregister)."); return true;
        }
        try {
            switch(name) {
                case "register", "reg", "havenregister", "ltzregister" -> register(sender,args);
                case "login", "l", "havenlogin", "ltzlogin" -> login(sender,args);
                case "logout" -> { if(!player){send(sender,"Chỉ người chơi mới dùng được lệnh này.");break;}auth.clear(uuid);shardOnlineSince.remove(uuid);refreshStaffPermissions(sender);try{Reflect.call(sender,"updateCommands");}catch(Exception ignored){}send(sender,"§eBạn đã đăng xuất."); }
                case "changepassword" -> changePassword(sender,args);
                case "resetpassword" -> resetPassword(sender,args);
                case "menu","dn" -> { requirePlayer(sender);gui.openMain(sender); }
                case "lifesteal" -> lifeSteal(sender,args);
                case "bal","balance" -> { requirePlayer(sender);send(sender,"§eSố dư: §6$"+MoneyFormat.compact(economy.balance(uuid))); }
                case "pay" -> pay(sender,args);
                case "baltop" -> baltop(sender);
                case "shop" -> {
                    requirePlayer(sender);
                    if (args.length > 0) gui.openShopCategory(sender, args[0]);
                    else gui.openShop(sender);
                }
                case "sell" -> sell(sender,args);
                case "rank" -> rank(sender,args);
                case "sethome" -> setHome(sender,args);
                case "home" -> home(sender,args);
                case "homes" -> listHomes(sender);
                case "delhome" -> deleteHome(sender,args);
                case "tpa" -> tpa(sender,args);
                case "tpahere" -> tpaHere(sender,args);
                case "tpaccept" -> tpAccept(sender);
                case "tpdeny" -> tpDeny(sender);
                case "spawn" -> teleportSetting(sender,"spawn");
                case "setspawn" -> setSpawn(sender);
                case "rtp" -> randomTeleport(sender);
                case "warp" -> warp(sender,args);
                case "setwarp" -> setWarp(sender,args);
                case "delwarp" -> deleteWarp(sender,args);
                case "code" -> code(sender,args);
                case "redeem" -> redeem(sender,args);
                case "crates", "crate" -> send(sender,"§cHệ thống crate đã được tắt trên Haven SMP.");
                case "daily" -> daily(sender);
                case "playtime" -> playtime(sender);
                case "playreward" -> playtimeReward(sender);
                case "streak" -> streak(sender);
                case "shard" -> shard(sender,args);
                case "giveshard" -> shard(sender,new String[]{"give", args.length>0?args[0]:"", args.length>1?args[1]:""});
                case "shardtop" -> shardTop(sender);
                case "shardshop" -> { requirePlayer(sender); gui.openShopCategory(sender,"shards"); }
                case "heartshop" -> { requirePlayer(sender); gui.openHeartShop(sender); }
                case "bounty" -> bounty(sender,args);
                case "bounties" -> bountyTop(sender,args);
                case "spawnregion", "region" -> spawnRegion(sender,args);
                case "ban" -> punish(sender,args,"BAN");
                case "unban" -> unpunish(sender,args,"BAN");
                case "mute" -> punish(sender,args,"MUTE");
                case "unmute" -> unpunish(sender,args,"MUTE");
                case "freeze" -> freeze(sender,args);
                case "vanish" -> vanish(sender,args);
                case "giveitem" -> giveItem(sender,args);
                case "playerinfo" -> playerInfo(sender,args);
                case "spawner","ah","orders","order","tool" -> expansion.command(sender,name,args);
                case "setting","settings","caidat" -> { requirePlayer(sender); gui.openSettings(sender); }
                case "stats","stat","thongke" -> statsCommand(sender,args);
                case "spec","gmsp" -> specCommand(sender);
                case "gms" -> gmsCommand(sender);
                case "gamemode","gm" -> gamemodeCommand(sender,args);
                case "tp" -> tpCommand(sender,args);
                case "kick" -> kickCommand(sender,args);
                case "invsee" -> invseeCommand(sender,args);
                case "staffchat","sc" -> staffChatCommand(sender,args);
                case "team","teams" -> teamCommand(sender,args);
                case "tc","teamchat" -> teamChatCommand(sender,args);
                case "baitbase","fakebase","trapbase","wizoxbase" -> baitBaseCommand(sender,args);
                case "addstaff","setstaff" -> addStaffCommand(sender,args);
                case "removestaff","delstaff" -> removeStaffCommand(sender,args);
                case "ec","echest","enderchest" -> enderChest(sender,args);
                case "ltzcore" -> admin(sender,args);
                default -> send(sender,"§cLệnh chưa được hỗ trợ.");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING,"Command /"+name+" failed",e);
            send(sender,"§cĐã xảy ra lỗi. Hãy báo admin và kiểm tra console.");
        }
        return true;
    }

    private void registerListeners() {
        register("org.bukkit.event.player.PlayerJoinEvent",this::onJoin);
        register("org.bukkit.event.player.PlayerQuitEvent",this::onQuit);
        register("org.bukkit.event.player.PlayerMoveEvent",this::onMove);
        register("org.bukkit.event.player.PlayerCommandPreprocessEvent",this::onCommandPreprocess);
        register("org.bukkit.event.player.PlayerCommandSendEvent",this::onCommandSend);
        register("org.bukkit.event.player.AsyncPlayerChatEvent",this::onChat);
        register("org.bukkit.event.player.PlayerInteractEvent",this::lockEvent);
        register("org.bukkit.event.player.PlayerInteractEvent",this::onEnderChestInteract);
        registerProtection("org.bukkit.event.player.PlayerInteractEvent",this::onSpawnProtection);
        register("org.bukkit.event.player.PlayerDropItemEvent",this::lockEvent);
        registerProtection("org.bukkit.event.player.PlayerDropItemEvent",this::onSpawnDrop);
        register("org.bukkit.event.block.BlockBreakEvent",this::lockEvent);
        register("org.bukkit.event.block.BlockPlaceEvent",this::lockEvent);
        registerProtection("org.bukkit.event.block.BlockBreakEvent",this::onSpawnProtection);
        registerProtection("org.bukkit.event.block.BlockPlaceEvent",this::onSpawnProtection);
        registerProtection("org.bukkit.event.block.BlockIgniteEvent",this::onSpawnIgnite);
        registerProtection("org.bukkit.event.player.PlayerBucketEmptyEvent",this::onSpawnProtection);
        registerProtection("org.bukkit.event.player.PlayerBucketFillEvent",this::onSpawnProtection);
        registerProtection("org.bukkit.event.block.BlockBurnEvent",this::onSpawnProtection);
        registerProtection("org.bukkit.event.block.BlockFromToEvent",this::onSpawnFlow);
        registerProtection("org.bukkit.event.block.BlockSpreadEvent",this::onSpawnSpread);
        registerProtection("org.bukkit.event.block.BlockPistonExtendEvent",this::onSpawnPiston);
        registerProtection("org.bukkit.event.block.BlockPistonRetractEvent",this::onSpawnPiston);
        registerProtection("org.bukkit.event.entity.EntityChangeBlockEvent",this::onSpawnEntityBlock);
        registerProtection("org.bukkit.event.entity.EntityExplodeEvent",this::onSpawnExplosion);
        registerProtection("org.bukkit.event.block.BlockExplodeEvent",this::onSpawnExplosion);
        registerProtection("org.bukkit.event.entity.EntityDamageByEntityEvent",this::onSpawnPvp);
        registerProtection("org.bukkit.event.entity.EntityDamageByEntityEvent",this::onTeamFriendlyFire);
        register("org.bukkit.event.entity.EntityDamageByEntityEvent",this::onCombatDamage);
        registerProtection("org.bukkit.event.player.PlayerInteractEntityEvent",this::onSpawnEntityInteract);
        registerProtection("org.bukkit.event.player.PlayerArmorStandManipulateEvent",this::onSpawnEntityInteract);
        registerProtection("org.bukkit.event.hanging.HangingBreakByEntityEvent",this::onSpawnHangingBreak);
        registerProtection("org.bukkit.event.entity.CreatureSpawnEvent",this::onSpawnCreature);
        register("org.bukkit.event.entity.CreatureSpawnEvent",this::onMobSpawnSetting);
        register("org.bukkit.event.inventory.InventoryClickEvent",this::onInventoryClick);
        register("org.bukkit.event.inventory.InventoryDragEvent",this::onInventoryDrag);
        register("org.bukkit.event.inventory.InventoryCloseEvent",this::onInventoryClose);
        registerProtection("org.bukkit.event.entity.EntityDamageEvent",this::onEntityDamage);
        register("org.bukkit.event.entity.EntityPickupItemEvent",this::onPickup);
        register("org.bukkit.event.entity.PlayerDeathEvent",this::onDeath);
        register("org.bukkit.event.entity.EntityDeathEvent",this::onEntityDeathStat);
        try { Reflect.registerEvent(this,"org.bukkit.event.block.BlockBreakEvent",this::onBlockBreakStat,"MONITOR",true); } catch (Exception ignored) { }
        try { Reflect.registerEvent(this,"org.bukkit.event.block.BlockBreakEvent",this::onBaitBaseTrigger,"MONITOR",false); } catch (Exception ignored) { }
        try { Reflect.registerEvent(this,"org.bukkit.event.player.PlayerInteractEvent",this::onBaitBaseInteract,"MONITOR",false); } catch (Exception ignored) { }
        try { Reflect.registerEvent(this,"org.bukkit.event.block.BlockPlaceEvent",this::onBlockPlaceStat,"MONITOR",true); } catch (Exception ignored) { }
        register("org.bukkit.event.player.PlayerRespawnEvent",this::onRespawn);
    }
    private void register(String event,java.util.function.Consumer<Object> consumer){try{Reflect.registerEvent(this,event,consumer);}catch(Exception e){getLogger().warning("Could not register listener for "+event+": "+e.getMessage());}}
    private void registerProtection(String event,java.util.function.Consumer<Object> consumer){try{Reflect.registerEvent(this,event,consumer,"HIGHEST",false);}catch(Exception e){getLogger().warning("Could not register protection listener for "+event+": "+e.getMessage());}}

    private void onCombatDamage(Object event) {
        try {
            Object victim = Reflect.call(event, "getEntity");
            Object damager = Reflect.call(event, "getDamager");
            if (!cfgBoolean("combat-log.enabled", true)) return;
            if (Boolean.TRUE.equals(Reflect.call(event, "isCancelled"))) return;
            try { if (((Number) Reflect.call(event, "getFinalDamage")).doubleValue() <= 0.0) return; } catch (Exception ignored) { }
            if (!isPlayer(damager)) { try { Object shooter = Reflect.call(damager, "getShooter"); if (isPlayer(shooter)) damager = shooter; } catch (Exception ignored) { } }
            if (!isPlayer(victim) || !isPlayer(damager)) return;
            UUID victimId = uuid(victim), damagerId = uuid(damager);
            if (!auth.isAuthenticated(victimId) || !auth.isAuthenticated(damagerId)) return;
            if (victimId.equals(damagerId)) return;
            int seconds = Math.max(1, cfgInt("combat-log.tag-seconds", 20));
            // Every valid hit refreshes BOTH players to a fresh 20-second combat window.
            // The shared one-second ticker renders the remaining time in the Action Bar only.
            long until = System.currentTimeMillis() + seconds * 1000L;
            combatTaggedUntil.put(victimId, until);
            combatTaggedUntil.put(damagerId, until);
            refreshStatusActionBar(victim);
            refreshStatusActionBar(damager);
        } catch (Exception ignored) { }
    }

    private void applyCombatLogoutPenalty(Object player, UUID id) {
        Long until = combatTaggedUntil.remove(id);
        if (!cfgBoolean("combat-log.enabled", true) || !cfgBoolean("combat-log.drop-inventory-on-quit", true) || until == null || until <= System.currentTimeMillis()) return;
        try {
            Object location = Reflect.call(player, "getLocation");
            Object world = Reflect.call(location, "getWorld");
            Object inventory = Reflect.call(player, "getInventory");
            Object contents = Reflect.call(inventory, "getStorageContents");
            if (contents instanceof Object[] items) {
                for (Object stack : items) {
                    if (stack == null) continue;
                    Object type = Reflect.call(stack, "getType");
                    if (type == null || String.valueOf(type).equals("AIR")) continue;
                    Reflect.call(world, "dropItemNaturally", location, stack);
                }
            }
            Object armor = Reflect.call(inventory, "getArmorContents");
            if (armor instanceof Object[] items) {
                for (Object stack : items) {
                    if (stack == null) continue;
                    Object type = Reflect.call(stack, "getType");
                    if (type == null || String.valueOf(type).equals("AIR")) continue;
                    Reflect.call(world, "dropItemNaturally", location, stack);
                }
            }
            Object offhand = Reflect.call(inventory, "getItemInOffHand");
            if (offhand != null && !String.valueOf(Reflect.call(offhand, "getType")).equals("AIR")) Reflect.call(world, "dropItemNaturally", location, offhand);
            Reflect.call(inventory, "clear");
            try { Reflect.call(player, "setHealth", 0.0); } catch (Exception ignored) { }
            broadcast("§c§lCOMBAT LOG §f" + name(player) + " §7đã thoát khi đang combat và bị mất toàn bộ đồ.");
        } catch (Exception e) {
            getLogger().warning("Could not apply combat-log penalty for " + name(player) + ": " + e.getMessage());
        }
    }

    private long totalPlaytimeSeconds(UUID id) {
        try {
            long saved = db.playtime(id);
            long sessionStart = joinedAt.getOrDefault(id, System.currentTimeMillis());
            return saved + Math.max(0, (System.currentTimeMillis() - sessionStart) / 1000L);
        } catch (Exception e) { return 0L; }
    }

    private String formatPlaytime(long seconds) {
        long hours = seconds / 3600L, minutes = (seconds % 3600L) / 60L;
        if (hours >= 24) return (hours / 24) + "d " + (hours % 24) + "h";
        return hours + "h " + minutes + "m";
    }

    private String scoreboardTeamName(Object player) {
        try {
            UUID id = uuid(player);
            String cached = playerTeamNames.get(id);
            if (cached == null) {
                String[] t = db.teamOfPlayer(id);
                cached = t == null ? "" : t[1];
                playerTeamNames.put(id, cached);
            }
            return cached.isBlank() ? "§7None" : "§b" + cached;
        } catch (Exception e) { return "§7None"; }
    }

    private void migrateShopPriceProfile() {
        if (cfgInt("shop.price-profile", 0) >= 6) return;
        Map<String, Long> values = new LinkedHashMap<>();
        // Classic DonutSMP per-item shop prices (End, Nether, Gear, Food)
        values.put("ender_chest", 2500L); values.put("ender_pearl", 75L);
        values.put("end_stone", 16L); values.put("dragon_breath", 1000L);
        values.put("end_rod", 100L); values.put("chorus_fruit", 108L);
        values.put("popped_chorus_fruit", 24L); values.put("shulker_shell", 350L);
        values.put("shulker_box", 800L);
        values.put("blaze_rod", 150L); values.put("nether_wart", 96L);
        values.put("glowstone_dust", 15L); values.put("magma_cream", 96L);
        values.put("ghast_tear", 350L); values.put("quartz", 30L);
        values.put("soul_sand", 50L); values.put("magma_block", 35L);
        values.put("crying_obsidian", 150L);
        values.put("obsidian", 100L); values.put("end_crystal", 250L);
        values.put("respawn_anchor", 500L); values.put("glowstone", 100L);
        values.put("totem_of_undying", 1500L); values.put("golden_apple", 250L);
        values.put("experience_bottle", 100L); values.put("arrow_of_slow_falling", 500L);
        values.put("potato", 96L); values.put("sweet_berries", 50L);
        values.put("melon_slice", 36L); values.put("carrot", 96L);
        values.put("apple", 25L); values.put("cooked_chicken", 48L);
        values.put("cooked_beef", 35L); values.put("golden_carrot", 120L);
        try {
            Object config = getConfig();
            for (Map.Entry<String, Long> entry : values.entrySet()) {
                Reflect.call(config, "set", "shop.prices." + entry.getKey(), entry.getValue());
            }
            // Move Shard display off the Action Bar onto the Scoreboard on upgrade.
            Reflect.call(config, "set", "shards.actionbar.enabled", false);
            Reflect.call(config, "set", "scoreboard.update-interval-ticks", 20);
            Reflect.call(config, "set", "spawners.max-stack", 258);
            Reflect.call(config, "set", "spawners.access-mode", "PUBLIC");
            Reflect.call(config, "set", "shop.price-profile", 6);
            saveConfig();
        } catch (Exception e) { getLogger().warning("Could not migrate shop prices: " + e.getMessage()); }
    }

    private void onJoin(Object event) {
        Object player=Reflect.call(event,"getPlayer"); UUID id=uuid(player); auth.clear(id); joinedAt.put(id,System.currentTimeMillis()); loadPlayerSettings(id); refreshPlayerTeamCache(id);
        try {
            String[] ban = db.activePunishment(id,"BAN");
            if (ban != null) { Reflect.call(player,"kickPlayer","§cBạn đã bị ban: §f"+ban[1]); return; }
        } catch(Exception ignored) {}
        for (UUID vanished : vanishedPlayers) { Object hidden = Reflect.callStatic("org.bukkit.Bukkit","getPlayer",vanished); if(hidden!=null) try{Reflect.call(player,"hidePlayer",this,hidden);}catch(Exception ignored){} }
        if (isOwnerName(name(player)) && !auth.isRegistered(id)) {
            send(player,"§6Tài khoản Owner Haven SMP chưa được thiết lập. Hãy nhờ console chạy lệnh: ltzcore ownerpassword.");
        } else {
            send(player,auth.isRegistered(id)?"§eChào mừng! Hãy đăng nhập bằng §f/login <mật khẩu>§e.":"§eChào mừng! Hãy đăng ký bằng §f/register <mật khẩu> <xác nhận>§e.");
        }
        Reflect.scheduleLater(this,()->{try{if(Boolean.TRUE.equals(Reflect.call(player,"isOnline"))&&!auth.isAuthenticated(id))Reflect.call(player,"kickPlayer","§cBạn đã hết thời gian đăng nhập.");}catch(Exception ignored){}},cfgInt("authentication.timeout-seconds",120)*20L);
    }
    private void onQuit(Object event) {
        Object player=Reflect.call(event,"getPlayer"); UUID id=uuid(player);
        applyCombatLogoutPenalty(player, id);
        auth.clear(id); scoreboards.remove(id);objectives.remove(id);scoreEntries.remove(id);
        if(gui!=null) { gui.cancelCrateOpen(id); gui.cleanupPlayer(id); }
        long joined=joinedAt.getOrDefault(id,System.currentTimeMillis()); joinedAt.remove(id);
        try { db.addPlaytime(id,Math.max(0,(System.currentTimeMillis()-joined)/1000L)); } catch(Exception ignored){}
        tpaRequests.remove(id);tpaRequests.values().removeIf(r -> id.equals(r.requester()));
        playerSettingsCache.remove(id);
        flushPendingStats(id);
        teamInvites.remove(id);
        teamCreateDrafts.remove(id);
        teamChatToggled.remove(id);
        playerTeamNames.remove(id);
        baitAlertCooldowns.remove(id);
        spawnProtectionWarnings.remove(id);
        rtpOperations.remove(id);
        rtpDestinations.remove(id);
        rtpOrigins.remove(id);
        rtpCooldowns.remove(id);
        shardOnlineSince.remove(id);
        removeStaffPermissions(player);
    }
    private void onMove(Object event) {
        Object player=Reflect.call(event,"getPlayer");
        if(frozenPlayers.contains(uuid(player))) { Reflect.call(event,"setCancelled",true); return; }
        if(auth.isAuthenticated(uuid(player)))return;
        Object from=Reflect.call(event,"getFrom"),to=Reflect.call(event,"getTo"); if(to==null){Reflect.call(event,"setCancelled",true);return;}
        boolean moved=number(Reflect.call(from,"getX"))!=number(Reflect.call(to,"getX"))||number(Reflect.call(from,"getY"))!=number(Reflect.call(to,"getY"))||number(Reflect.call(from,"getZ"))!=number(Reflect.call(to,"getZ"));
        if(moved)Reflect.call(event,"setCancelled",true);
    }
    private boolean isAuthenticationCommand(String command) {
        if (command == null) return false;
        String root = command.toLowerCase(Locale.ROOT);
        int namespace = root.lastIndexOf(':');
        if (namespace >= 0) root = root.substring(namespace + 1);
        return Set.of("login", "l", "ltzlogin", "havenlogin",
                "register", "reg", "ltzregister", "havenregister").contains(root);
    }

    private void onCommandPreprocess(Object event) {
        Object player=Reflect.call(event,"getPlayer");
        String message=String.valueOf(Reflect.call(event,"getMessage"));
        String lower=message.toLowerCase(Locale.ROOT).trim();
        // Resolve the command root independently of arguments and plugin namespace.
        // This keeps LTZCore authentication available even when another plugin owns /login or /register.
        String rawCommand = lower.length() > 1 ? lower.substring(1).trim() : "";
        String commandToken = rawCommand.isEmpty() ? "" : rawCommand.split("\\s+", 2)[0];
        String root = commandToken.substring(commandToken.lastIndexOf(':') + 1);
        if(!auth.isAuthenticated(uuid(player))) {
            if(!isAuthenticationCommand(root)) Reflect.call(event,"setCancelled",true);
            return;
        }
        String[] parts=rawCommand.isEmpty()?new String[0]:rawCommand.split("\\s+");
        if(parts.length==0)return;
        if(!admin(player) && !isPublicSurvivalRoot(root) && !(staff(player) && isStaffRoot(root))) {
            Reflect.call(event,"setCancelled",true);
            send(player,"§cUnknown command.");
            return;
        }
        if(root.equals("lifesteal") && parts.length>1 && Set.of("start","stop","admin","revive","reload").contains(parts[1]) && !admin(player)) {
            Reflect.call(event,"setCancelled",true); send(player,"§cUnknown command."); return;
        }
        if(root.equals("shard") && parts.length>1 && parts[1].equals("give") && !admin(player)) {
            Reflect.call(event,"setCancelled",true); send(player,"§cUnknown command."); return;
        }
        if(root.equals("rank") && parts.length>1 && Set.of("create","delete","set","prefix","confirm","pending").contains(parts[1]) && !admin(player)) {
            Reflect.call(event,"setCancelled",true); send(player,"§cUnknown command."); return;
        }
        if(root.equals("spawner") && parts.length>1 && Set.of("give","reload","remove","panel").contains(parts[1]) && !admin(player)) {
            Reflect.call(event,"setCancelled",true); send(player,"§cUnknown command."); return;
        }
        if(root.equals("tool") && !admin(player)) {
            Reflect.call(event,"setCancelled",true); send(player,"§cUnknown command.");
        }
    }

    private void onCommandSend(Object event) {
        try {
            Object player=Reflect.call(event,"getPlayer");
            Object raw=Reflect.call(event,"getCommands");
            if(!(raw instanceof Set<?> set)) return;
            @SuppressWarnings("unchecked") Set<Object> commands=(Set<Object>)set;
            if(!auth.isAuthenticated(uuid(player))) {
                commands.removeIf(value -> !isAuthenticationCommand(commandRoot(String.valueOf(value))));
                return;
            }
            if(admin(player)) return; // Authenticated Admin/Owner sees the full command tree.
            boolean isStaffMember = staff(player);
            commands.removeIf(value -> {
                String r = commandRoot(String.valueOf(value));
                return !isPublicSurvivalRoot(r) && !(isStaffMember && isStaffRoot(r));
            });
        } catch(Exception ignored) {}
    }

    private String commandRoot(String command) {
        String root=command.toLowerCase(Locale.ROOT);
        int colon=root.lastIndexOf(':'); if(colon>=0)root=root.substring(colon+1);
        return root;
    }

    private boolean isPublicSurvivalRoot(String root) {
        return Set.of(
                "register","reg","ltzregister","havenregister","login","l","ltzlogin","havenlogin",
                "logout","changepassword","menu","dn","lifesteal",
                "bal","balance","pay","baltop","shop","sell","rank",
                "sethome","home","homes","delhome","tpa","tpahere","tpaccept","tpdeny",
                "spawn","rtp","warp","daily","playtime","playreward","streak",
                "shard","shardtop","shardshop","heartshop","bounty","bounties",
                "redeem","spawner","ah","orders","order","ec","echest","enderchest","setting","settings","caidat","stats","stat","thongke","team","teams","tc","teamchat"
        ).contains(root);
    }

    private boolean isStaffRoot(String root) {
        return Set.of(
                "spec","gmsp","gms","gamemode","gm","tp","kick","invsee",
                "staffchat","sc","vanish","freeze","ban","unban","mute","unmute","playerinfo",
                "baitbase","fakebase","trapbase","wizoxbase"
        ).contains(root);
    }

    private void onChat(Object event) {
        Object player=Reflect.call(event,"getPlayer");
        UUID pid=uuid(player);
        if(!auth.isAuthenticated(pid)) { Reflect.call(event,"setCancelled",true); return; }
        if(teamCreateDrafts.contains(pid)) {
            Reflect.call(event,"setCancelled",true);
            String msg=String.valueOf(Reflect.call(event,"getMessage")).trim();
            Reflect.scheduleLater(this,()->completeTeamCreateDraft(player,msg),1L);
            return;
        }
        try { if(isPunished(pid,"MUTE")) { Reflect.call(event,"setCancelled",true); send(player,"§cBạn đang bị mute."); return; } } catch(Exception ignored) {}
        if(teamChatToggled.contains(pid)) {
            Reflect.call(event,"setCancelled",true);
            String msg=String.valueOf(Reflect.call(event,"getMessage"));
            Reflect.scheduleLater(this,()->sendTeamChatMessage(player,msg),1L);
            return;
        }
        try {
            String rank=db.rank(uuid(player));
            String prefix=db.rankPrefix(rank).replace("%","%%");
            String format=prefix+" §f"+name(player)+" §8» §f%2$s";
            Reflect.call(event,"setFormat",format);
            if (!staff(player)) {
                Object rec = Reflect.call(event, "getRecipients");
                if (rec instanceof Set<?> set) {
                    UUID senderId = uuid(player);
                    set.removeIf(recipient -> {
                        try {
                            UUID rid = uuid(recipient);
                            return !senderId.equals(rid) && !isPlayerSettingEnabled(rid, "global_chat");
                        } catch (Exception ignored) { return false; }
                    });
                }
            }
        } catch(Exception ignored) {}
    }
    private void lockEvent(Object event) {
        Object player=null;try{player=Reflect.call(event,"getPlayer");}catch(Exception ignored){}
        if(player!=null&&!auth.isAuthenticated(uuid(player)))Reflect.call(event,"setCancelled",true);
    }
    private void onInventoryClick(Object event) {
        Object player=Reflect.call(event,"getWhoClicked");if(!auth.isAuthenticated(uuid(player))){Reflect.call(event,"setCancelled",true);return;}
        gui.handleClick(event);
    }
    private void onInventoryDrag(Object event) {
        Object player=Reflect.call(event,"getWhoClicked");if(!auth.isAuthenticated(uuid(player))){Reflect.call(event,"setCancelled",true);return;}
        boolean handled = gui != null && gui.handleDrag(event);
        if (handled) return;
        try {String title=String.valueOf(Reflect.call(Reflect.call(event,"getView"),"getTitle"));if(title.startsWith("§8LTZCore")||title.startsWith("§8Haven SMP")||title.equals(GuiManager.LIFE)||title.equals(GuiManager.ADMIN))Reflect.call(event,"setCancelled",true);}catch(Exception ignored){}
    }
    private void onInventoryClose(Object event) {
        if (gui != null) gui.handleClose(event);
    }
    private void onEntityDamage(Object event) {
        try {
            Object entity=Reflect.call(event,"getEntity");
            if(isPlayer(entity)&&!auth.isAuthenticated(uuid(entity))){Reflect.call(event,"setCancelled",true);return;}
            // A player inside any protected spawn cuboid is immune to every damage cause.
            if(isPlayer(entity)&&isInSpawnRegion(Reflect.call(entity,"getLocation"))) {
                Reflect.call(event,"setCancelled",true);
            }
        } catch(Exception ignored) {}
    }
    private void onPickup(Object event) {
        try { Object entity=Reflect.call(event,"getEntity"); if(isPlayer(entity)&&!auth.isAuthenticated(uuid(entity)))Reflect.call(event,"setCancelled",true); } catch(Exception ignored) {}
    }
    private void onDeath(Object event) {
        Object victim=Reflect.call(event,"getEntity"); Object killer=null;
        try {
            if (isPlayer(victim) && isPlayerSettingEnabled(uuid(victim), "death_coords")) {
                Object loc = Reflect.call(victim, "getLocation");
                String w = String.valueOf(Reflect.call(Reflect.call(loc, "getWorld"), "getName"));
                int x = ((Number) Reflect.call(loc, "getBlockX")).intValue();
                int y = ((Number) Reflect.call(loc, "getBlockY")).intValue();
                int z = ((Number) Reflect.call(loc, "getBlockZ")).intValue();
                send(victim, "§c☠ §7Tọa độ bạn vừa chết: §f" + w + " §8| §eX: " + x + " Y: " + y + " Z: " + z);
            }
        } catch (Exception ignored) { }
        try { killer=Reflect.call(victim,"getKiller"); } catch(Exception ignored) {}
        try {
            if (isPlayer(victim)) {
                UUID vid = uuid(victim);
                incrementStat(vid, 0, 1, 0, 0, 0);
                if (killer != null && isPlayer(killer) && !uuid(killer).equals(vid)) {
                    incrementStat(uuid(killer), 1, 0, 0, 0, 0);
                }
            }
        } catch (Exception ignored) { }
        life.onKill(killer,victim);
        if(killer!=null) try { long reward=db.claimBounty(uuid(victim),uuid(killer)); if(reward>0) { send(killer,"§6Bạn nhận được bounty §e$"+MoneyFormat.compact(reward)+"§6!"); broadcast("§6[Bounty] §e"+name(killer)+" §7đã nhận §6$"+MoneyFormat.compact(reward)+" §7từ bounty của §f"+name(victim)+"§7."); } } catch(Exception ignored) {}
    }
    private void onRespawn(Object event) { life.onRespawn(Reflect.call(event,"getPlayer")); }

    /** Keep inventory items safe inside the protected spawn: cancel the drop before an item entity is created. */
    private void onSpawnDrop(Object event) {
        try {
            Object player = Reflect.call(event, "getPlayer");
            if (player == null || !isPlayer(player) || !auth.isAuthenticated(uuid(player))) return;
            if (admin(player) && cfgBoolean("spawn-region.allow-staff-drop", false)) return;
            boolean inside = isInSpawnRegion(Reflect.call(player, "getLocation"));
            try {
                Object dropped = Reflect.call(event, "getItemDrop");
                if (dropped != null) inside = inside || isInSpawnRegion(Reflect.call(dropped, "getLocation"));
            } catch (Exception ignored) { }
            if (!inside) return;
            Reflect.call(event, "setCancelled", true);
            warnSpawnProtection(player, "Ném vật phẩm (đã giữ nguyên trong túi)");
        } catch (Exception e) {
            getLogger().fine("Spawn item-drop protection skipped: " + e.getMessage());
        }
    }

    private void warnSpawnProtection(Object player, String action) {
        if (player == null || !isPlayer(player)) return;
        try {
            UUID id=uuid(player);
            long now=System.currentTimeMillis();
            long previous=spawnProtectionWarnings.getOrDefault(id,0L);
            if(now-previous<1200L) return;
            spawnProtectionWarnings.put(id,now);
            String detail = action.startsWith("Ném vật phẩm")
                    ? "không thể ném đồ trong khu spawn; vật phẩm vẫn được giữ trong túi."
                    : action+" không được phép trong khu vực bảo vệ.";
            send(player,"§c§lHAVEN SPAWN §8» §c"+detail);
        } catch(Exception ignored) { }
    }

    private void onSpawnProtection(Object event) {
        try {
            String eventName=event.getClass().getSimpleName();
            Object player=null;
            try { player=Reflect.call(event,"getPlayer"); } catch(Exception ignored) { }
            if(player!=null && admin(player)) return;

            Object block=null;
            for(String method:List.of("getBlockPlaced","getBlock","getClickedBlock","getBlockClicked")) {
                try { block=Reflect.call(event,method); if(block!=null) break; } catch(Exception ignored) { }
            }
            boolean playerInside=player!=null && isInSpawnRegion(Reflect.call(player,"getLocation"));
            boolean blockInside=block!=null && isInSpawnRegion(Reflect.call(block,"getLocation"));
            boolean protectedAction=playerInside||blockInside;

            if(eventName.equals("PlayerBucketEmptyEvent") || eventName.equals("PlayerBucketFillEvent")) {
                try {
                    Object clicked=Reflect.call(event,"getBlockClicked");
                    Object face=Reflect.call(event,"getBlockFace");
                    Object target=Reflect.call(clicked,"getRelative",face);
                    protectedAction=protectedAction || isInSpawnRegion(Reflect.call(target,"getLocation"));
                } catch(Exception ignored) { }
            }
            if(!protectedAction) return;

            // Allow players to open personal Expanded Ender Chests inside Spawn (DonutSMP behavior).
            if(eventName.equals("PlayerInteractEvent") && cfgBoolean("enderchest.enabled", true) && cfgBoolean("enderchest.intercept-block", true)
                    && player!=null && block!=null && "ENDER_CHEST".equals(String.valueOf(Reflect.call(block,"getType")))
                    && String.valueOf(Reflect.call(event,"getAction")).contains("RIGHT_CLICK_BLOCK")) {
                boolean sneakingWithItem = Boolean.TRUE.equals(Reflect.call(player, "isSneaking"));
                if (sneakingWithItem) {
                    Object held = Reflect.call(event, "getItem");
                    sneakingWithItem = held != null && !"AIR".equals(String.valueOf(Reflect.call(held, "getType")));
                }
                if (!sneakingWithItem) return;
            }

            // Inside the cuboid, ordinary interaction is disabled too (chests, doors,
            // buttons, entities and item-use). Players can still use commands and GUIs.
            Reflect.call(event,"setCancelled",true);
            if(player!=null) {
                String action=switch(eventName) {
                    case "BlockBreakEvent" -> "Phá block";
                    case "BlockPlaceEvent" -> "Đặt block";
                    case "PlayerBucketEmptyEvent","PlayerBucketFillEvent" -> "Dùng xô";
                    case "PlayerInteractEvent" -> "Tương tác";
                    default -> "Hành động này";
                };
                warnSpawnProtection(player,action);
            }
        } catch(Exception ignored) { }
    }

    private void onSpawnExplosion(Object event) {
        try {
            Object raw = Reflect.call(event,"blockList");
            if (raw instanceof List<?> blocks) {
                @SuppressWarnings("unchecked") List<Object> mutable = (List<Object>) blocks;
                mutable.removeIf(block -> {
                    try { return isInSpawnRegion(Reflect.call(block,"getLocation")); }
                    catch(Exception ignored) { return false; }
                });
            }
        } catch(Exception ignored) { }
    }

    private void onSpawnPvp(Object event) {
        try {
            Object victim=Reflect.call(event,"getEntity");
            Object damager=Reflect.call(event,"getDamager");
            Object shooter=damager;
            try { Object possible=Reflect.call(damager,"getShooter"); if(possible!=null) shooter=possible; } catch(Exception ignored) { }
            boolean attackerInside=isPlayer(shooter)&&isInSpawnRegion(Reflect.call(shooter,"getLocation"));
            boolean victimInside=isPlayer(victim)&&isInSpawnRegion(Reflect.call(victim,"getLocation"));
            if(attackerInside||victimInside) {
                Reflect.call(event,"setCancelled",true);
                if(isPlayer(shooter)) warnSpawnProtection(shooter,"Tấn công người chơi");
                if(isPlayer(victim)) warnSpawnProtection(victim,"PvP");
            }
        } catch(Exception ignored) { }
    }

    private void onSpawnCreature(Object event) {
        try {
            Object location=Reflect.call(event,"getLocation");
            if(isInSpawnRegion(location)) Reflect.call(event,"setCancelled",true);
        } catch(Exception ignored) { }
    }

    private void onSpawnFlow(Object event) {
        try {
            Object from=Reflect.call(event,"getBlock"); Object to=Reflect.call(event,"getToBlock");
            if(isInSpawnRegion(Reflect.call(from,"getLocation"))||isInSpawnRegion(Reflect.call(to,"getLocation"))) Reflect.call(event,"setCancelled",true);
        } catch(Exception ignored) { }
    }
    private void onSpawnSpread(Object event) {
        try {
            Object target=Reflect.call(event,"getBlock");
            Object source=Reflect.call(event,"getSource");
            if(isInSpawnRegion(Reflect.call(target,"getLocation"))||isInSpawnRegion(Reflect.call(source,"getLocation"))) Reflect.call(event,"setCancelled",true);
        } catch(Exception ignored) { }
    }

    private void onSpawnPiston(Object event) {
        try {
            Object piston=Reflect.call(event,"getBlock");
            if(isInSpawnRegion(Reflect.call(piston,"getLocation"))) { Reflect.call(event,"setCancelled",true); return; }
            Object raw=Reflect.call(event,"getBlocks");
            Object direction=Reflect.call(event,"getDirection");
            int dx=((Number)Reflect.call(direction,"getModX")).intValue();
            int dy=((Number)Reflect.call(direction,"getModY")).intValue();
            int dz=((Number)Reflect.call(direction,"getModZ")).intValue();
            if(raw instanceof Iterable<?> blocks) for(Object block:blocks) {
                Object loc=Reflect.call(block,"getLocation");
                if(isInSpawnRegion(loc)) { Reflect.call(event,"setCancelled",true); return; }
                Object moved=Reflect.call(block,"getRelative",direction);
                if(isInSpawnRegion(Reflect.call(moved,"getLocation"))) { Reflect.call(event,"setCancelled",true); return; }
                // Retraction pulls blocks in the opposite direction; check both destinations conservatively.
                Object world=Reflect.call(loc,"getWorld");
                int x=(int)Math.floor(number(Reflect.call(loc,"getX")));
                int y=(int)Math.floor(number(Reflect.call(loc,"getY")));
                int z=(int)Math.floor(number(Reflect.call(loc,"getZ")));
                Object opposite=Reflect.call(world,"getBlockAt",x-dx,y-dy,z-dz);
                if(isInSpawnRegion(Reflect.call(opposite,"getLocation"))) { Reflect.call(event,"setCancelled",true); return; }
            }
        } catch(Exception ignored) { }
    }
    private void onSpawnIgnite(Object event) {
        try {
            Object block=Reflect.call(event,"getBlock"); Object igniter=null;
            try { igniter=Reflect.call(event,"getPlayer"); } catch(Exception ignored) { }
            if(igniter!=null&&admin(igniter))return;
            boolean inside=isInSpawnRegion(Reflect.call(block,"getLocation")) || (igniter!=null&&isInSpawnRegion(Reflect.call(igniter,"getLocation")));
            if(inside) { Reflect.call(event,"setCancelled",true); warnSpawnProtection(igniter,"Đốt block"); }
        } catch(Exception ignored) { }
    }

    private void onSpawnEntityBlock(Object event) {
        try { Object block=Reflect.call(event,"getBlock"); if(isInSpawnRegion(Reflect.call(block,"getLocation"))) Reflect.call(event,"setCancelled",true); }
        catch(Exception ignored) { }
    }
    private void onSpawnEntityInteract(Object event) {
        try {
            Object player=Reflect.call(event,"getPlayer"); if(admin(player)) return;
            Object target=null;
            try { target=Reflect.call(event,"getRightClicked"); } catch(Exception ignored) { }
            boolean inside=isInSpawnRegion(Reflect.call(player,"getLocation"));
            if(target!=null) inside=inside||isInSpawnRegion(Reflect.call(target,"getLocation"));
            if(inside) { Reflect.call(event,"setCancelled",true); warnSpawnProtection(player,"Tương tác với entity"); }
        } catch(Exception ignored) { }
    }
    private void onSpawnHangingBreak(Object event) {
        try {
            Object remover=Reflect.call(event,"getRemover"); if(remover!=null&&admin(remover)) return;
            Object entity=Reflect.call(event,"getEntity");
            boolean inside=entity!=null&&isInSpawnRegion(Reflect.call(entity,"getLocation"));
            if(remover!=null&&isPlayer(remover)) inside=inside||isInSpawnRegion(Reflect.call(remover,"getLocation"));
            if(inside) { Reflect.call(event,"setCancelled",true); warnSpawnProtection(remover,"Phá vật trang trí"); }
        } catch(Exception ignored) { }
    }

    private boolean isInSpawnRegion(Object location) {
        try {
            SpawnRegion region = spawnRegionCache;
            if (region == null || location == null) return false;
            String world = String.valueOf(Reflect.call(Reflect.call(location,"getWorld"),"getName"));
            if(!world.equalsIgnoreCase(region.world())) return false;
            int x = ((Number)Reflect.call(location,"getBlockX")).intValue();
            int y = ((Number)Reflect.call(location,"getBlockY")).intValue();
            int z = ((Number)Reflect.call(location,"getBlockZ")).intValue();
            boolean fullHeight = cfgBoolean("spawn-region.protect-full-height", true);
            boolean verticalMatch = fullHeight || (y>=region.minY()&&y<=region.maxY());
            return x>=region.minX()&&x<=region.maxX()&&verticalMatch&&z>=region.minZ()&&z<=region.maxZ();
        } catch(Exception e) { return false; }
    }

    private void loadSpawnRegionCache() throws Exception {
        String data=db.getSetting("spawnregion","");
        if(data.isBlank()){spawnRegionCache=null;return;}
        String[] p=data.split(";");
        if(p.length!=7){spawnRegionCache=null;return;}
        int x1=Integer.parseInt(p[1]),y1=Integer.parseInt(p[2]),z1=Integer.parseInt(p[3]);
        int x2=Integer.parseInt(p[4]),y2=Integer.parseInt(p[5]),z2=Integer.parseInt(p[6]);
        spawnRegionCache=new SpawnRegion(p[0],Math.min(x1,x2),Math.min(y1,y2),Math.min(z1,z2),Math.max(x1,x2),Math.max(y1,y2),Math.max(z1,z2));
    }

    private void spawnRegion(CommandSender sender, String[] args) throws Exception {
        if(!admin(sender)) { send(sender,"§cUnknown command."); return; }
        if(args.length==0) { send(sender,"§e/region pos1 | pos2 | create | info | clear"); send(sender,"§7Có thể dùng /region create <x1> <y1> <z1> <x2> <y2> <z2>."); return; }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("clear")||sub.equals("delete")) {
            db.setting("spawnregion",""); spawnRegionCache=null;
            send(sender,"§aĐã xóa vùng bảo vệ spawn."); return;
        }
        if(sub.equals("info")) {
            SpawnRegion r=spawnRegionCache;
            send(sender,r==null?"§cChưa thiết lập vùng spawn.":"§aSpawn region: §f"+r.world()+" X:"+r.minX()+".."+r.maxX()+" Y:"+r.minY()+".."+r.maxY()+" Z:"+r.minZ()+".."+r.maxZ());
            return;
        }
        if(!isPlayer(sender)) { send(sender,"§cLệnh chọn vùng cần chạy trong game."); return; }
        UUID playerId=uuid(sender);
        Map<String,String[]> selection=spawnSelections.computeIfAbsent(playerId,k->new ConcurrentHashMap<>());
        if(sub.equals("pos1")||sub.equals("pos2")) {
            Object loc=Reflect.call(sender,"getLocation"), world=Reflect.call(loc,"getWorld");
            String[] point={String.valueOf(Reflect.call(world,"getName")),String.valueOf(Reflect.call(loc,"getBlockX")),String.valueOf(Reflect.call(loc,"getBlockY")),String.valueOf(Reflect.call(loc,"getBlockZ"))};
            selection.put(sub,point);
            send(sender,"§aĐã chọn §e"+sub+" §atại §f"+point[0]+" "+point[1]+" "+point[2]+" "+point[3]);
            return;
        }
        if(sub.equals("create") && args.length==7) {
            Object world=Reflect.call(Reflect.call(sender,"getLocation"),"getWorld");
            String worldName=String.valueOf(Reflect.call(world,"getName"));
            try {
                String[] a={worldName,args[1],args[2],args[3]};
                String[] b={worldName,args[4],args[5],args[6]};
                saveSpawnRegion(sender,a,b);
            } catch(NumberFormatException ex) { send(sender,"§cTọa độ phải là số nguyên hợp lệ."); }
            return;
        }
        if(sub.equals("create")||sub.equals("save")) {
            if(!selection.containsKey("pos1")||!selection.containsKey("pos2")) { send(sender,"§cHãy chọn /region pos1 và /region pos2 trước."); return; }
            String[] a=selection.get("pos1"),b=selection.get("pos2");
            if(!a[0].equalsIgnoreCase(b[0])) { send(sender,"§cHai vị trí phải thuộc cùng một world."); return; }
            saveSpawnRegion(sender,a,b); return;
        }
        send(sender,"§e/region pos1 | pos2 | create | info | clear");
    }

    private void saveSpawnRegion(CommandSender sender,String[] a,String[] b) throws Exception {
        int x1=Integer.parseInt(a[1]),y1=Integer.parseInt(a[2]),z1=Integer.parseInt(a[3]);
        int x2=Integer.parseInt(b[1]),y2=Integer.parseInt(b[2]),z2=Integer.parseInt(b[3]);
        SpawnRegion region=new SpawnRegion(a[0],Math.min(x1,x2),Math.min(y1,y2),Math.min(z1,z2),Math.max(x1,x2),Math.max(y1,y2),Math.max(z1,z2));
        db.setting("spawnregion",String.join(";",a[0],String.valueOf(x1),String.valueOf(y1),String.valueOf(z1),String.valueOf(x2),String.valueOf(y2),String.valueOf(z2)));
        spawnRegionCache=region;
        send(sender,"§aĐã tạo cuboid bảo vệ spawn thành công.");
        send(sender,"§7World: §f"+region.world()+" §7X: §f"+region.minX()+".."+region.maxX()+" §7Y: §f"+region.minY()+".."+region.maxY()+" §7Z: §f"+region.minZ()+".."+region.maxZ());
        send(sender,"§7Đã bật chặn break/place/interact/PvP/damage/mob spawn trong vùng cho member.");
    }

    private boolean isInCombat(UUID id) {
        if (!cfgBoolean("combat-log.enabled", true)) return false;
        Long until = combatTaggedUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    private void onEnderChestInteract(Object event) {
        try {
            if (!cfgBoolean("enderchest.enabled", true) || !cfgBoolean("enderchest.intercept-block", true)) return;
            String action = String.valueOf(Reflect.call(event, "getAction"));
            if (!action.contains("RIGHT_CLICK_BLOCK")) return;
            Object block = Reflect.call(event, "getClickedBlock");
            if (block == null || !"ENDER_CHEST".equals(String.valueOf(Reflect.call(block, "getType")))) return;
            Object player = Reflect.call(event, "getPlayer");
            if (player == null || !auth.isAuthenticated(uuid(player))) return;
            if (Boolean.TRUE.equals(Reflect.call(player, "isSneaking"))) {
                Object held = Reflect.call(event, "getItem");
                if (held != null && !"AIR".equals(String.valueOf(Reflect.call(held, "getType")))) return;
            }
            Reflect.call(event, "setCancelled", true);
            if (isInCombat(uuid(player)) && !admin(player) && cfgBoolean("enderchest.block-in-combat", true)) {
                send(player, "§cBạn không thể mở Ender Chest khi đang trong combat!");
                return;
            }
            gui.openEnderChest(player, uuid(player), name(player), player);
        } catch (Exception e) {
            getLogger().warning("Could not open expanded Ender Chest on interact: " + e.getMessage());
        }
    }

    private void enderChest(CommandSender sender, String[] args) {
        requirePlayer(sender);
        if (!cfgBoolean("enderchest.enabled", true)) { send(sender, "§cEnder Chest mở rộng đang tắt."); return; }
        if (args.length > 0) {
            if (!staff(sender)) { send(sender, "§cUnknown command."); return; }
            Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
            if (online != null) {
                gui.openEnderChest(sender, uuid(online), name(online), online);
            } else {
                Object offline = Reflect.callStatic("org.bukkit.Bukkit", "getOfflinePlayer", args[0]);
                UUID targetId = (UUID) Reflect.call(offline, "getUniqueId");
                gui.openEnderChest(sender, targetId, args[0], null);
            }
            return;
        }
        UUID id = uuid(sender);
        if (isInCombat(id) && !admin(sender) && cfgBoolean("enderchest.block-in-combat", true)) {
            send(sender, "§cBạn không thể mở Ender Chest khi đang trong combat!");
            return;
        }
        gui.openEnderChest(sender, id, name(sender), sender);
    }

    private void updateShardStatusAndActionBars() {
        try {
            long now = System.currentTimeMillis();
            boolean shardsEnabled = cfgBoolean("shards.enabled", true);
            boolean shardActionBarEnabled = cfgBoolean("shards.actionbar.enabled", false);
            long rewardPerMinute = Math.max(1, cfgInt("shards.reward-per-interval", 1));
            Object raw = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers");
            if (!(raw instanceof Collection<?> players)) return;
            Set<UUID> eligibleNow = new HashSet<>();
            for (Object player : players) {
                UUID id = uuid(player);
                if (!auth.isAuthenticated(id)) continue;
                String rank = db.rank(id);
                boolean eligible = shardsEnabled && Set.of("+", "++", "+++").contains(rank);
                long secondsUntilNext = 0;
                if (eligible) {
                    eligibleNow.add(id);
                    Long since = shardOnlineSince.putIfAbsent(id, now);
                    if (since == null) since = now;
                    long minutes = Math.max(0, (now - since) / 60_000L);
                    if (minutes > 0) {
                        long reward = Math.multiplyExact(minutes, rewardPerMinute);
                        db.addShards(id, reward, "RANK_ONLINE_MINUTE");
                        since = since + minutes * 60_000L;
                        shardOnlineSince.put(id, since);
                        // Shard earnings are intentionally silent in chat: the balance is shown only above the hotbar.
                    }
                    secondsUntilNext = vn.ltzcore.util.ShardCountdown.secondsUntilNext(since, now);
                }
                long combatUntil = combatTaggedUntil.getOrDefault(id, 0L);
                if (combatUntil > 0L && combatUntil <= now) {
                    combatTaggedUntil.remove(id, combatUntil);
                    combatUntil = 0L;
                }
                long combatSeconds = combatUntil > now ? (combatUntil - now + 999L) / 1000L : 0L;
                String status = buildStatusActionBar(id, eligible, secondsUntilNext, rewardPerMinute,
                        shardActionBarEnabled, combatSeconds);
                if (!status.isEmpty()) sendActionBar(player, status);
            }
            shardOnlineSince.keySet().removeIf(id -> !eligibleNow.contains(id));
        } catch (Exception e) {
            getLogger().warning("Shard/combat Action Bar ticker failed: " + e.getMessage());
        }
    }

    /** Shard balance and combat countdown share one Action Bar; no shard wallet messages are sent to chat. */
    private String buildStatusActionBar(UUID id, boolean eligible, long secondsUntilNext, long rewardPerMinute,
                                        boolean shardActionBarEnabled, long combatSeconds) throws Exception {
        List<String> parts = new ArrayList<>();
        if (shardActionBarEnabled) {
            long balance = db.shardBalance(id);
            String shardText = "§d✦ §fShard: §d" + MoneyFormat.compact(balance);
            if (eligible) shardText += " §8(§7+" + rewardPerMinute + " sau §e"
                    + vn.ltzcore.util.ShardCountdown.format(secondsUntilNext) + "§8)";
            parts.add(shardText);
        }
        if (combatSeconds > 0L) parts.add("§c⚔ Combat: §f" + combatSeconds + "s");
        return String.join(" §8| ", parts);
    }

    /** Immediately refreshes the combined Action Bar after login, shard changes or a combat hit. */
    private void notifyShardWallet(Object player, boolean ignoredChat) {
        refreshStatusActionBar(player);
    }

    private void refreshStatusActionBar(Object player) {
        try {
            if (player == null || !isPlayer(player) || !auth.isAuthenticated(uuid(player))) return;
            UUID id = uuid(player);
            long now = System.currentTimeMillis();
            boolean eligible = cfgBoolean("shards.enabled", true) && Set.of("+", "++", "+++").contains(db.rank(id));
            long next = 0L;
            if (eligible) {
                Long since = shardOnlineSince.putIfAbsent(id, now);
                if (since == null) since = now;
                next = vn.ltzcore.util.ShardCountdown.secondsUntilNext(since, now);
            }
            long until = combatTaggedUntil.getOrDefault(id, 0L);
            long combatSeconds = until > now ? (until - now + 999L) / 1000L : 0L;
            String status = buildStatusActionBar(id, eligible, next, Math.max(1, cfgInt("shards.reward-per-interval", 1)),
                    cfgBoolean("shards.actionbar.enabled", false), combatSeconds);
            if (!status.isEmpty()) sendActionBar(player, status);
        } catch (Exception e) {
            getLogger().fine("Could not refresh status Action Bar: " + e.getMessage());
        }
    }

    /** Sends compact status immediately above the hotbar (Action Bar). */
    private void sendActionBar(Object player, String legacyText) {
        String firstFailure = null;
        try {
            Object serializer = Reflect.callStatic("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer", "legacySection");
            Object component = Reflect.call(serializer, "deserialize", legacyText);
            Reflect.call(player, "sendActionBar", component);
            return;
        } catch (Exception e) {
            firstFailure = e.getMessage();
        }
        // Paper keeps the Bungee action-bar bridge for compatibility; fall back if Adventure reflection is unavailable.
        try {
            Object spigot = Reflect.call(player, "spigot");
            Object type = Reflect.enumValue("net.md_5.bungee.api.ChatMessageType", "ACTION_BAR");
            Object components = Reflect.callStatic("net.md_5.bungee.api.chat.TextComponent", "fromLegacyText", legacyText);
            Reflect.call(spigot, "sendMessage", type, components);
        } catch (Exception e) {
            if (!shardActionBarWarningLogged) {
                shardActionBarWarningLogged = true;
                getLogger().warning("Could not send Haven status Action Bar. Adventure: " + firstFailure + "; Bungee: " + e.getMessage());
            }
        }
    }

    private void shard(CommandSender sender, String[] args) throws Exception {
        if(args.length>0 && args[0].equalsIgnoreCase("give")) {
            if(!admin(sender) || args.length<3) { send(sender,"§cUnknown command."); return; }
            Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[1]);
            long amount=MoneyFormat.parse(args[2]);
            if(target==null||amount<=0||amount>1_000_000_000_000L) { send(sender,"§cNgười chơi hoặc số lượng không hợp lệ."); return; }
            db.addShards(uuid(target),amount,"ADMIN_GIVE"); send(sender,"§aĐã cập nhật ví phần thưởng cho "+name(target)+"."); updateScoreboard(target); notifyShardWallet(target,false); return;
        }
        requirePlayer(sender);
        UUID id = uuid(sender);
        updateScoreboard(sender);
        long bal = db.shardBalance(id);
        send(sender, "§d✦ §fShard của bạn: §d" + MoneyFormat.compact(bal) + " §7(hiển thị trực tiếp trên Scoreboard)");
    }

    private void shardTop(CommandSender sender) throws Exception {
        if (!isPlayer(sender)) { send(sender, "Lệnh này chỉ dùng trong game."); return; }
        gui.openShardTop(sender);
    }

    private void bounty(CommandSender sender,String[] args) throws Exception {
        requirePlayer(sender);
        if(args.length<2) { send(sender,"§e/bounty <player> <amount> §7(ví dụ 5k, 2.5m, 1b)"); return; }
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[0]);
        if(target==null) { send(sender,"§cMục tiêu cần đang online."); return; }
        long amount=MoneyFormat.parse(args[1]);
        if(amount<Math.max(1,cfgInt("lifesteal.bounty-minimum",100))||amount>1_000_000_000_000L||uuid(target).equals(uuid(sender))) { send(sender,"§cSố tiền không hợp lệ hoặc thấp hơn mức bounty tối thiểu §6$"+cfgInt("lifesteal.bounty-minimum",100)+"§c."); return; }
        if(db.createBounty(uuid(target),uuid(sender),amount)) {
            send(sender,"§aĐã đặt bounty §6$"+MoneyFormat.compact(amount)+" §alên "+name(target)+".");
            send(target,"§cBạn đang bị truy nã! Bounty hiện tại: §6$"+MoneyFormat.compact(db.bountyTotal(uuid(target)))+"§c.");
        } else send(sender,"§cKhông đủ tiền hoặc không thể tạo bounty.");
    }

    private void bountyTop(CommandSender sender,String[] args) throws Exception {
        if(args.length>0) {
            Object target=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",args[0]); UUID id=(UUID)Reflect.call(target,"getUniqueId");
            send(sender,"§6Bounty của §f"+args[0]+"§6: §e$"+MoneyFormat.compact(db.bountyTotal(id))); return;
        }
        send(sender,"§6§lHAVEN SMP — TOP BOUNTY"); int i=1;
        for(String[] row:db.bountyTop(10)) send(sender,"§e"+i++ +". §f"+row[2]+" §7- §6$"+MoneyFormat.compact(Long.parseLong(row[1])));
    }

    private void punish(CommandSender sender,String[] args,String type) throws Exception {
        if(!staff(sender)) { send(sender,"§cUnknown command."); return; }
        if(args.length<3) { send(sender,"§e/"+(type.equals("BAN")?"ban":"mute")+" <player> <30m|2h|7d|perm> <lý do>"); return; }
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",args[0]);
        UUID id=(UUID)Reflect.call(target,"getUniqueId");
        if(isPlayer(sender) && db.staffLevel(db.rank(id)) >= db.staffLevel(db.rank(uuid(sender)))) { send(sender,"§cKhông thể phạt người có rank ngang hoặc cao hơn bạn."); return; }
        long duration=parseDuration(args[1]);
        if(duration<0) { send(sender,"§eThời hạn: 30m, 2h, 7d hoặc perm."); return; }
        String reason=String.join(" ",Arrays.copyOfRange(args,2,args.length));
        long expires=duration==0?0:System.currentTimeMillis()+duration;
        db.addPunishment(id,type,reason,expires,isPlayer(sender)?name(sender):"CONSOLE");
        Object online=Reflect.callStatic("org.bukkit.Bukkit","getPlayer",id);
        if(type.equals("BAN")&&online!=null) Reflect.call(online,"kickPlayer","§cBạn đã bị ban: §f"+reason);
        send(sender,"§aĐã "+(type.equals("BAN")?"ban":"mute")+" "+args[0]+".");
    }

    private void unpunish(CommandSender sender,String[] args,String type) throws Exception {
        if(!staff(sender)) { send(sender,"§cUnknown command."); return; }
        if(args.length<1) { send(sender,"§e/"+(type.equals("BAN")?"unban":"unmute")+" <player>"); return; }
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",args[0]); UUID id=(UUID)Reflect.call(target,"getUniqueId");
        send(sender,db.removePunishment(id,type)?"§aĐã gỡ "+type+" cho "+args[0]+".":"§cKhông tìm thấy án phạt.");
    }

    private boolean isPunished(UUID id,String type) throws Exception { return db.activePunishment(id,type)!=null; }
    private long parseDuration(String token) {
        if(token.equalsIgnoreCase("perm")||token.equals("0")) return 0;
        try { char unit=Character.toLowerCase(token.charAt(token.length()-1)); long n=Long.parseLong(token.substring(0,token.length()-1)); if(n<=0) return -1; return Math.multiplyExact(n,switch(unit){case 's'->1000L;case 'm'->60_000L;case 'h'->3_600_000L;case 'd'->86_400_000L;default->-1L;}); } catch(Exception e) { return -1; }
    }

    private void freeze(CommandSender sender,String[] args) {
        if(!staff(sender)) { send(sender,"§cUnknown command."); return; }
        if(args.length<1) { send(sender,"§e/freeze <player>"); return; }
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[0]); if(target==null) { send(sender,"§cNgười chơi không online."); return; }
        try { if(isPlayer(sender) && db.staffLevel(db.rank(uuid(target))) >= db.staffLevel(db.rank(uuid(sender)))) { send(sender,"§cKhông thể freeze người có rank ngang hoặc cao hơn."); return; } } catch(Exception ignored) { }
        UUID id=uuid(target); if(frozenPlayers.add(id)) send(target,"§cBạn đã bị freeze."); else { frozenPlayers.remove(id); send(target,"§aBạn đã được unfreeze."); }
        send(sender,"§aĐã cập nhật trạng thái freeze cho "+name(target)+".");
    }

    private void vanish(CommandSender sender,String[] args) {
        if(!staff(sender)||!isPlayer(sender)) { send(sender,"§cUnknown command."); return; }
        if(args.length>0 && !admin(sender)) { send(sender,"§cRank Staff chỉ có thể dùng /vanish cho bản thân."); return; }
        Object target=args.length>0?Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[0]):sender;
        if(target==null||!isPlayer(target)) { send(sender,"§cNgười chơi không online."); return; }
        UUID id=uuid(target); boolean hide=vanishedPlayers.add(id);
        Object raw=Reflect.callStatic("org.bukkit.Bukkit","getOnlinePlayers");
        if(raw instanceof Collection<?> players) for(Object viewer:players) if(!uuid(viewer).equals(id)) {
            try { if(hide) Reflect.call(viewer,"hidePlayer",this,target); else Reflect.call(viewer,"showPlayer",this,target); } catch(Exception ignored) { }
        }
        send(target,hide?"§7Bạn đã vanish.":"§aBạn đã unvanish.");
    }

    private void giveItem(CommandSender sender,String[] args) {
        if(!admin(sender)||args.length<3) { send(sender,"§cUnknown command."); return; }
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[0]); int amount=parseInt(args[2],-1);
        if(target==null||amount<1||amount>64) { send(sender,"§cNgười chơi hoặc số lượng không hợp lệ."); return; }
        try {
            Object material=Reflect.enumValue("org.bukkit.Material",args[1].toUpperCase(Locale.ROOT));
            Object stack=Reflect.construct("org.bukkit.inventory.ItemStack",material,amount);
            // Use Inventory#addItem(ItemStack...) through a correctly typed single-element array.
            Object array=java.lang.reflect.Array.newInstance(Reflect.type("org.bukkit.inventory.ItemStack"),1); java.lang.reflect.Array.set(array,0,stack);
            Object leftovers=Reflect.call(Reflect.call(target,"getInventory"),"addItem",array);
            if(leftovers instanceof Map<?,?> map&&!map.isEmpty()) for(Object leftover:map.values()) Reflect.call(Reflect.call(target,"getWorld"),"dropItemNaturally",Reflect.call(target,"getLocation"),leftover);
            send(sender,"§aĐã tặng vật phẩm cho "+name(target)+".");
        } catch(Exception e) { send(sender,"§cMaterial không hợp lệ hoặc không thể giao vật phẩm."); }
    }

    private void playerInfo(CommandSender sender,String[] args) throws Exception {
        if(!staff(sender)) { send(sender,"§cUnknown command."); return; }
        if(args.length<1) { send(sender,"§e/playerinfo <player>"); return; }
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",args[0]); UUID id=(UUID)Reflect.call(target,"getUniqueId");
        send(sender,"§6Player: §f"+args[0]+" §7UUID: §f"+id);
        send(sender,"§7Rank: "+db.rankPrefix(db.rank(id))+" §7Money: §6$"+MoneyFormat.compact(db.balance(id)));
        send(sender,"§7Homes: §f"+db.homeCount(id)+" §7Playtime: §f"+db.playtime(id)+"s");
    }

    private void register(CommandSender sender,String[] args) {
        if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới đăng ký được.");return;}
        if(isOwnerName(name(sender))){send(sender,"§cTài khoản Owner chỉ được khởi tạo từ console. Hãy nhờ console chạy lệnh: ltzcore ownerpassword.");return;}
        if(args.length<2){send(sender,"§eCách dùng: /register <mật khẩu> <xác nhận>");return;}
        AuthManager.Result r=auth.register(uuid(sender),name(sender),args[0],args[1]);
        switch(r){case OK->{shardOnlineSince.put(uuid(sender),System.currentTimeMillis());refreshStaffPermissions(sender);try{Reflect.call(sender,"updateCommands");}catch(Exception ignored){}send(sender,"§aĐăng ký thành công! Bạn đã đăng nhập.");updateScoreboard(sender);syncAllNameTags();notifyShardWallet(sender, true);life.applyOnJoin(sender);gui.openMain(sender);}case ALREADY_REGISTERED->send(sender,"§cTài khoản đã đăng ký. Hãy dùng /login.");case TOO_SHORT->send(sender,"§cMật khẩu cần ít nhất 8 ký tự.");case BAD_PASSWORD->send(sender,"§cHai mật khẩu không trùng nhau.");default->send(sender,"§cKhông thể đăng ký, thử lại sau.");}
    }
    private void login(CommandSender sender,String[] args) {
        if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới đăng nhập được.");return;}
        if(args.length<1){send(sender,"§eCách dùng: /login <mật khẩu>");return;}
        AuthManager.Result r=auth.login(uuid(sender),args[0]);
        switch(r){case OK->{
            try { if(isOwnerName(name(sender))) db.setRank(uuid(sender),"Owner",0); } catch(Exception ignored) {}
            send(sender,"§aĐăng nhập thành công!");
            refreshStaffPermissions(sender);
            try { Reflect.call(sender,"updateCommands"); } catch(Exception ignored) {}
            shardOnlineSince.put(uuid(sender),System.currentTimeMillis());
            updateScoreboard(sender);
            syncAllNameTags();
            notifyShardWallet(sender, true);
            life.applyOnJoin(sender);gui.openMain(sender);
        }case NOT_REGISTERED->send(sender,"§cBạn chưa đăng ký. Dùng /register <mật khẩu> <xác nhận>.");case LOCKED->send(sender,"§cBạn nhập sai quá nhiều. Thử lại sau "+Math.max(1,auth.lockRemaining(uuid(sender))/1000)+" giây.");case BAD_PASSWORD->send(sender,"§cMật khẩu không chính xác.");default->send(sender,"§cKhông thể đăng nhập.");}
    }
    private void changePassword(CommandSender sender,String[] args){if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới dùng được.");return;}if(args.length<2){send(sender,"§e/changepassword <mật khẩu cũ> <mật khẩu mới>");return;}AuthManager.Result r=auth.changePassword(uuid(sender),args[0],args[1]);send(sender,r==AuthManager.Result.OK?"§aĐã đổi mật khẩu.":r==AuthManager.Result.TOO_SHORT?"§cMật khẩu mới cần ít nhất 8 ký tự.":"§cKhông đổi được mật khẩu. Kiểm tra mật khẩu cũ.");}
    private void resetPassword(CommandSender sender,String[] args){if(!admin(sender)){send(sender,"§cBạn không có quyền.");return;}if(args.length<2){send(sender,"§e/resetpassword <player> <mật khẩu mới>");return;}Object offline=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",args[0]);UUID id=(UUID)Reflect.call(offline,"getUniqueId");AuthManager.Result r=auth.adminReset(id,args[1]);send(sender,r==AuthManager.Result.OK?"§aĐã reset mật khẩu.":"§cKhông thể reset mật khẩu.");}

    private void lifeSteal(CommandSender sender,String[] args) throws Exception {
        if(args.length==0||args[0].equalsIgnoreCase("gui")){requirePlayer(sender);gui.openLife(sender);return;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("status")){send(sender,"§eLifeSteal: "+(life.enabled()?"§aON":"§cOFF")+" §7| còn "+life.remainingSeconds()+" giây");return;}
        if(sub.equals("start")||sub.equals("stop")){if(!lifeAdmin(sender)){send(sender,"§cBạn không có quyền quản lý event.");return;}if(sub.equals("start")){life.start();broadcast("§aLifeSteal Event đã bắt đầu!");}else{life.stop();awardLifeStealRewards();broadcast("§cLifeSteal Event đã kết thúc. SMP trở về bình thường.");}return;}
        if(sub.equals("admin")){if(!lifeAdmin(sender)){send(sender,"§cBạn không có quyền.");return;}requirePlayer(sender);gui.openAdmin(sender);return;}
        if(sub.equals("revive")){if(!lifeAdmin(sender)||args.length<2){send(sender,"§c/lifesteal revive <player>");return;}Object target=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",args[1]);UUID id=(UUID)Reflect.call(target,"getUniqueId");send(sender,life.revive(id,cfgInt("lifesteal.revive-hearts",5))?"§aĐã revive người chơi.":"§cKhông thể revive.");return;}
        send(sender,"§e/lifesteal [gui|status|start|stop|admin|revive <player>]");
    }

    private void pay(CommandSender sender,String[] args) {
        if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới chuyển tiền được.");return;}if(args.length<2){send(sender,"§e/pay <player> <số tiền> §7(ví dụ 5k, 2.5m, 1b)");return;}
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[0]);if(target==null){send(sender,"§cNgười chơi không online.");return;}
        long amount=MoneyFormat.parse(args[1]);if(amount<=0){send(sender,"§cSố tiền không hợp lệ. Dùng ví dụ 5k, 2.5m hoặc 1b.");return;}
        UUID to=uuid(target);
        if(!isPlayerSettingEnabled(to,"pay") && !admin(sender)){send(sender,"§c"+name(target)+" đã tắt nhận tiền (/pay) trong /setting.");return;}
        if(economy.pay(uuid(sender),to,amount)){send(sender,"§aĐã chuyển §6$"+MoneyFormat.compact(amount)+" §acho "+name(target)+".");send(target,"§aBạn nhận được §6$"+MoneyFormat.compact(amount)+" §atừ "+name(sender)+".");playUiSound(target,"entity.experience_orb.pickup");}else send(sender,"§cKhông đủ tiền hoặc giao dịch không hợp lệ.");
    }
private void baltop(CommandSender sender){send(sender,"§6§lTOP BALANCE");int i=1;for(String[] row:economy.top(10))send(sender,"§e"+i++ +". §f"+row[2]+" §7- §6$"+MoneyFormat.compact(Long.parseLong(row[1])));}
    private void sell(CommandSender sender,String[] args){
        if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới bán đồ được.");return;}
        if(args.length==0){gui.openSell(sender);return;}
        if(!args[0].equalsIgnoreCase("hand")){send(sender,"§e/sell [hand] - mở Sell GUI hoặc bán vật phẩm đang cầm");return;}
        Object inv=Reflect.call(sender,"getInventory"),item=Reflect.call(inv,"getItemInMainHand");
        String material=String.valueOf(Reflect.call(item,"getType"));int amount=((Number)Reflect.call(item,"getAmount")).intValue();
        long price=cfgLong("sell.prices."+material.toLowerCase(Locale.ROOT),0L);
        try { Object meta=Reflect.call(item,"getItemMeta"); if(Boolean.TRUE.equals(Reflect.call(meta,"hasDisplayName"))||Boolean.TRUE.equals(Reflect.call(meta,"hasLore"))) price=0; } catch(Exception ignored) { price=0; }
        if(price<=0||amount<=0||material.equals("AIR")){send(sender,"§cVật phẩm này không bán được.");return;}
        long total;try{total=Math.multiplyExact(price,amount);}catch(ArithmeticException ex){send(sender,"§cGiá trị giao dịch vượt giới hạn.");return;}
        Object original=Reflect.call(item,"clone");
        try {
            Reflect.call(inv,"setItemInMainHand",Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material","AIR")));
            economy.deposit(uuid(sender),total,"SELL_HAND");
            send(sender,"§aĐã bán "+amount+"x "+material+" với giá §6$"+MoneyFormat.compact(total)+"§a.");
        } catch(Exception ex) {
            Reflect.call(inv,"setItemInMainHand",original);
            send(sender,"§cGiao dịch lỗi; vật phẩm đã được trả lại.");
        }
    }

    private void rank(CommandSender sender,String[] args) throws Exception {
        if(args.length==0){if(isPlayer(sender))gui.openRanks(sender);else rankList(sender);return;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("list")){rankList(sender);return;}
        if(sub.equals("info")&&args.length>=2){String r=args[1];if(!db.rankExists(r)){send(sender,"§cRank không tồn tại.");return;}send(sender,"§6Rank §f"+r+" §7| Prefix: "+db.rankPrefix(r)+" §7| Home: "+db.homeLimit(r)+" §7| Staff: "+db.staffLevel(r));return;}
        if(sub.equals("buy")&&args.length>=2){
            requirePlayer(sender); String requested=args[1];
            if(requested.equals("+")) gui.purchaseRankMoney(sender, "+", cfgLong("rank-shop.prices.plus", 50_000));
            else if(requested.equals("++")) gui.purchaseRankMoney(sender, "++", cfgLong("rank-shop.prices.double-plus", 100_000));
            else if(requested.equals("+++")) gui.purchaseRankMoney(sender, "+++", cfgLong("rank-shop.prices.triple-plus", 500_000));
            else send(sender,"§cRank có thể mua: +, ++, +++.");
            return;
        }
        if(sub.equals("pending")){
            if(!admin(sender)){send(sender,"§cUnknown command.");return;}
            send(sender,"§6§lPENDING RANK PAYMENTS");
            for(String[] row:db.pendingRankPurchases(20)) send(sender,"§e#"+row[0]+" §f"+row[1]+" §7→ §b"+row[2]+" §7| §6"+row[3]+" VND");
            return;
        }
        if(sub.equals("confirm")){
            if(!admin(sender)||args.length<2){send(sender,"§cUnknown command.");return;}
            long requestId=parseLong(args[1],-1); if(requestId<1){send(sender,"§cMã yêu cầu không hợp lệ.");return;}
            String[] confirmed=db.confirmRankPurchase(requestId);
            if(confirmed==null){send(sender,"§cYêu cầu không tồn tại hoặc đã được xử lý.");return;}
            UUID targetId=UUID.fromString(confirmed[0]); Object online=Reflect.callStatic("org.bukkit.Bukkit","getPlayer",targetId);
            if(online!=null){refreshStaffPermissions(online);updateScoreboard(online);syncAllNameTags();try{Reflect.call(online,"updateCommands");}catch(Exception ignored){}send(online,"§aThanh toán đã xác nhận! Rank của bạn: §e"+confirmed[2]+"§a.");}
            send(sender,"§aĐã xác nhận đơn #"+requestId+" và cấp rank "+confirmed[2]+" cho "+confirmed[1]+".");
            return;
        }
        if(sub.equals("create")){
            if(!admin(sender)){send(sender,"§cUnknown command.");return;}
            if(args.length<4){send(sender,"§e/rank create <name> <prefix> <homeLimit> [normal|staff|admin|owner]");return;}
            String rankName=args[1]; if(!rankName.matches("[-A-Za-z0-9_+]{1,16}")){send(sender,"§cTên rank chỉ được gồm chữ, số, _, +, - và tối đa 16 ký tự.");return;}
            if(db.rankExists(rankName)){send(sender,"§cRank đã tồn tại.");return;}
            int home=parseInt(args[3],-1);if(home<1||home>100){send(sender,"§cHome limit phải từ 1 đến 100.");return;}
            int level=0;String role=args.length>=5?args[4].toLowerCase(Locale.ROOT):"normal";
            if(!role.equals("normal")){
                if(!owner(sender)){send(sender,"§cChỉ Owner mới được tạo rank có quyền quản trị.");return;}
                if(role.equals("staff"))level=1;else if(role.equals("admin"))level=2;else if(role.equals("owner"))level=3;else{send(sender,"§cRole phải là normal, staff, admin hoặc owner.");return;}
            }
            int priority=100;for(String[] row:db.rankDefinitions())priority=Math.max(priority,parseInt(row[3],0)+10);
            db.createRank(rankName,colorize(args[2]),home,priority,level);
            send(sender,"§aĐã tạo rank §f"+rankName+" §7(prefix="+colorize(args[2])+", homes="+home+", staff="+role+").");return;
        }
        if(sub.equals("set")){
            if(!admin(sender)){send(sender,"§cUnknown command.");return;}
            if(args.length<3){send(sender,"§e/rank set <player> <rank> [days]");return;}
            String targetRank=args[2];if(!db.rankExists(targetRank)){send(sender,"§cRank không tồn tại. Dùng /rank list.");return;}
            int targetLevel=db.staffLevel(targetRank);if(targetLevel>=2&&!owner(sender)){send(sender,"§cChỉ Owner mới được cấp rank Admin/Owner.");return;}
            Object target=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",args[1]);UUID id=(UUID)Reflect.call(target,"getUniqueId");
            int existingLevel=db.staffLevel(db.rank(id));
            if(existingLevel>=2&&!owner(sender)){send(sender,"§cChỉ Owner mới được thay đổi rank của Admin/Owner.");return;}
            long expires=0;if(args.length>=4){long days=parseLong(args[3],-1);if(days<1||days>3650){send(sender,"§cSố ngày phải từ 1 đến 3650.");return;}expires=System.currentTimeMillis()+days*86_400_000L;}
            db.setRank(id,targetRank,expires);send(sender,"§aĐã đặt rank "+targetRank+" cho "+args[1]+(expires>0?" trong "+args[3]+" ngày":""));
            Object online=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[1]);if(online!=null){refreshStaffPermissions(online);updateScoreboard(online);syncAllNameTags();try{Reflect.call(online,"updateCommands");}catch(Exception ignored){}send(online,"§aRank của bạn đã được cập nhật thành §f"+targetRank+"§a.");}
            return;
        }
        if(sub.equals("delete")){
            if(!admin(sender)||args.length<2){send(sender,"§cUnknown command.");return;}
            String r=args[1];if(db.staffLevel(r)>0&&!owner(sender)){send(sender,"§cChỉ Owner mới xóa được rank staff.");return;}
            send(sender,db.deleteRank(r)?"§aĐã xóa rank. Người đang dùng rank này được chuyển về Member.":"§cKhông thể xóa rank này.");return;
        }
        if(sub.equals("prefix")){
            if(!admin(sender)||args.length<3){send(sender,"§cUnknown command.");return;}
            if(db.staffLevel(args[1])>0&&!owner(sender)){send(sender,"§cChỉ Owner được sửa prefix staff.");return;}
            db.updateRankPrefix(args[1],colorize(args[2]));syncAllNameTags();send(sender,"§aĐã cập nhật prefix rank.");return;
        }
        if(isPlayer(sender))gui.openRanks(sender);else send(sender,"§e/rank list | info | create | set | delete | prefix");
    }

    private void createRankPayment(CommandSender sender,String rank,int price) throws Exception {
        UUID id=uuid(sender); if(db.rankPriority(db.rank(id))>=db.rankPriority(rank)){send(sender,"§cBạn đã có rank này hoặc rank cao hơn.");return;}
        long requestId=db.createRankPurchase(id,name(sender),rank,price);
        send(sender,"§aĐã tạo yêu cầu mua rank §e"+rank+" §7(#"+requestId+")");
        send(sender,"§eChuyển §6"+String.format(Locale.US,"%,d",price)+" VND §etới §f"+cfgString("rank-payment.bank-name","Chưa cấu hình ngân hàng")+" §7- §f"+cfgString("rank-payment.account-name","Haven SMP"));
        send(sender,"§eSTK: §f"+cfgString("rank-payment.account-number","CHUA-CAU-HINH")+" §e| Nội dung: §f"+cfgString("rank-payment.content-prefix","HAVEN")+requestId);
        send(sender,"§7Admin chỉ xác nhận sau khi đã kiểm tra giao dịch thực tế.");
    }

    private void rankList(CommandSender sender) throws Exception {
        send(sender,"§6§lHAVEN SMP — RANKS");
        for(String[] row:db.rankDefinitions()){int lvl=Integer.parseInt(row[4]);String tag=lvl>=3?" §6| OWNER":lvl==2?" §c| ADMIN":lvl==1?" §3| STAFF":"";send(sender,row[1]+" §f"+row[0]+" §7| homes: "+row[2]+tag);}
    }

    private void setHome(CommandSender sender,String[] args) throws Exception {
        requirePlayer(sender);
        if(args.length==0){gui.openSetHome(sender);return;}
        String home=args[0].toLowerCase(Locale.ROOT);
        if(!home.matches("[a-z0-9_-]{1,16}")){send(sender,"§cTên home không hợp lệ.");return;}
        int limit=homeLimit(db.rank(uuid(sender)));
        if(db.homeCount(uuid(sender))>=limit&&!db.homeNames(uuid(sender)).contains(home)){send(sender,"§cRank của bạn chỉ được đặt tối đa "+limit+" home.");return;}
        Object loc=Reflect.call(sender,"getLocation");Object world=Reflect.call(loc,"getWorld");
        db.saveHome(uuid(sender),home,String.valueOf(Reflect.call(world,"getName")),number(Reflect.call(loc,"getX")),number(Reflect.call(loc,"getY")),number(Reflect.call(loc,"getZ")),((Number)Reflect.call(loc,"getYaw")).floatValue(),((Number)Reflect.call(loc,"getPitch")).floatValue());
        send(sender,"§aĐã đặt home §e"+home+"§a.");
    }
    private void home(CommandSender sender,String[] args) throws Exception {
        requirePlayer(sender);
        if(args.length==0){gui.openHomeSelector(sender);return;}
        String home=args[0].toLowerCase(Locale.ROOT);String world=db.homeWorld(uuid(sender),home);double[] loc=db.home(uuid(sender),home);
        if(loc==null||world==null){gui.openHomeSelector(sender);send(sender,"§cKhông tìm thấy home §e"+home+"§c.");return;}
        teleport(sender,world,loc);send(sender,"§aĐã dịch chuyển tới home §e"+home+"§a.");
    }
    private void listHomes(CommandSender sender){if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới dùng được.");return;}gui.openHomeSelector(sender);}
    private void deleteHome(CommandSender sender,String[] args) throws Exception {
        requirePlayer(sender);
        if(args.length==0){gui.openHomeSelector(sender);send(sender,"§eTrong bảng Homes, click phải vào home để xóa.");return;}
        send(sender,db.deleteHome(uuid(sender),args[0])?"§aĐã xóa home.":"§cKhông tìm thấy home.");
    }
    private int homeLimit(String rank){try{return db.homeLimit(rank);}catch(Exception e){return 1;}}

    private void tpa(CommandSender sender,String[] args){sendTpaRequest(sender,args,false);}
    private void tpaHere(CommandSender sender,String[] args){sendTpaRequest(sender,args,true);}
    private void sendTpaRequest(CommandSender sender,String[] args,boolean here){
        if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới dùng được.");return;}
        if(args.length==0){send(sender,here?"§e/tpahere <player>":"§e/tpa <player>");return;}
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[0]);
        if(target==null){send(sender,"§cNgười chơi không online.");return;}
        UUID from=uuid(sender),to=uuid(target);
        if(from.equals(to)){send(sender,"§cKhông thể tự gửi yêu cầu cho mình.");return;}
        if(!isPlayerSettingEnabled(to,"tpa")&&!admin(sender)){send(sender,"§c"+name(target)+" đã tắt nhận yêu cầu TPA trong /setting.");return;}
        TpaRequest req=new TpaRequest(from,here);
        tpaRequests.put(to,req);
        if(here){
            send(sender,"§aĐã gửi yêu cầu §e/tpahere §atới §f"+name(target)+"§a.");
            send(target,"§e"+name(sender)+" §7muốn dịch chuyển §fBẠN §7tới chỗ họ (§e/tpahere§7). Dùng §a/tpaccept §7hoặc §c/tpdeny§7.");
        } else {
            send(sender,"§aĐã gửi yêu cầu TPA tới §f"+name(target)+"§a.");
            send(target,"§e"+name(sender)+" §7muốn dịch chuyển tới bạn. Dùng §a/tpaccept §7hoặc §c/tpdeny§7.");
        }
        playUiSound(target,"entity.experience_orb.pickup");
        Reflect.scheduleLater(this,()->tpaRequests.remove(to,req),600L);
    }
    private void tpAccept(CommandSender sender){
        if(!isPlayer(sender)){send(sender,"Chỉ người chơi mới dùng được.");return;}
        UUID to=uuid(sender);
        TpaRequest req=tpaRequests.remove(to);
        if(req==null){send(sender,"§cBạn không có yêu cầu TPA nào.");return;}
        Object requester=Reflect.callStatic("org.bukkit.Bukkit","getPlayer",req.requester());
        if(requester==null){send(sender,"§cNgười gửi không còn online.");return;}
        if(req.here()){
            Reflect.call(sender,"teleport",Reflect.call(requester,"getLocation"));
            send(sender,"§aĐã chấp nhận §e/tpahere §avà dịch chuyển tới §f"+name(requester)+"§a.");
            send(requester,"§a"+name(sender)+" đã chấp nhận §e/tpahere §avà dịch chuyển tới bạn.");
        } else {
            Reflect.call(requester,"teleport",Reflect.call(sender,"getLocation"));
            send(sender,"§aĐã chấp nhận TPA.");
            send(requester,"§aYêu cầu TPA đã được chấp nhận.");
        }
        playUiSound(sender,"entity.enderman.teleport");
        playUiSound(requester,"entity.enderman.teleport");
    }
    private void tpDeny(CommandSender sender){
        if(!isPlayer(sender))return;
        TpaRequest req=tpaRequests.remove(uuid(sender));
        if(req==null){send(sender,"§cKhông có yêu cầu TPA.");return;}
        Object requester=Reflect.callStatic("org.bukkit.Bukkit","getPlayer",req.requester());
        if(requester!=null)send(requester,"§cYêu cầu "+(req.here()?"/tpahere":"/tpa")+" đã bị từ chối.");
        send(sender,"§eĐã từ chối yêu cầu dịch chuyển.");
    }

    private void setSpawn(CommandSender sender) throws Exception {if(!admin(sender)){send(sender,"§cBạn không có quyền.");return;}requirePlayer(sender);saveLocation("spawn",sender);send(sender,"§aĐã đặt spawn.");}
    private void teleportSetting(CommandSender sender,String key) throws Exception {requirePlayer(sender);teleportFromSetting(sender,key);}
    private void saveLocation(String key,Object player) throws Exception {Object loc=Reflect.call(player,"getLocation"),world=Reflect.call(loc,"getWorld");String v=Reflect.call(world,"getName")+";"+Reflect.call(loc,"getX")+";"+Reflect.call(loc,"getY")+";"+Reflect.call(loc,"getZ")+";"+Reflect.call(loc,"getYaw")+";"+Reflect.call(loc,"getPitch");db.setting(key,v);}
    private void teleportFromSetting(Object player,String key) throws Exception {String v=db.getSetting(key,"");if(v.isBlank()){send(player,"§cSpawn chưa được thiết lập.");return;}String[] p=v.split(";");if(p.length!=6){send(player,"§cDữ liệu tọa độ không hợp lệ.");return;}teleport(player,p[0],new double[]{Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Double.parseDouble(p[4]),Double.parseDouble(p[5])});}
    private void teleport(Object player,String worldName,double[] p) {Object world=Reflect.callStatic("org.bukkit.Bukkit","getWorld",worldName);if(world==null){send(player,"§cWorld không tồn tại hoặc chưa được tải.");return;}Object loc=Reflect.construct("org.bukkit.Location",world,p[0],p[1],p[2],(float)p[3],(float)p[4]);Reflect.call(player,"teleport",loc);}
    /**
     * Fast RTP: find a safe location in already-generated terrain while a fixed
     * five-second countdown runs. It never generates a new chunk during /rtp,
     * so terrain generation cannot stall a small server. The async callback is
     * delivered on Paper's main server thread before Bukkit block access.
     */
    private void randomTeleport(CommandSender sender) {
        if (!isPlayer(sender)) {
            send(sender, "Chỉ người chơi mới dùng được.");
            return;
        }
        UUID playerId = uuid(sender);
        if (!auth.isAuthenticated(playerId)) return;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0, cfgInt("rtp.cooldown-seconds", 30)) * 1000L;
        long lastUse = rtpCooldowns.getOrDefault(playerId, 0L);
        if (cooldownMs > 0 && now - lastUse < cooldownMs) {
            long remaining = (cooldownMs - (now - lastUse) + 999L) / 1000L;
            send(sender, "§cBạn cần chờ " + remaining + " giây nữa mới dùng được /rtp.");
            return;
        }
        UUID operation = UUID.randomUUID();
        if (rtpOperations.putIfAbsent(playerId, operation) != null) {
            send(sender, "§eYêu cầu /rtp trước đó vẫn đang xử lý.");
            return;
        }

        Object playerLocation = Reflect.call(sender, "getLocation");
        Object world = Reflect.call(playerLocation, "getWorld");
        Object spawn = Reflect.call(world, "getSpawnLocation");
        int centerX = (int) Math.floor(number(Reflect.call(spawn, "getX")));
        int centerZ = (int) Math.floor(number(Reflect.call(spawn, "getZ")));
        int radius = Math.max(300, Math.min(10000, cfgInt("rtp.radius", 1500)));
        int minDistance = Math.max(100, Math.min(radius - 1, cfgInt("rtp.min-distance", 300)));
        int attempts = Math.max(10, Math.min(120, cfgInt("rtp.max-attempts", 80)));
        int windowSeconds = Math.max(3, Math.min(8, cfgInt("rtp.search-window-seconds", 5)));

        rtpDestinations.remove(playerId);
        try { rtpOrigins.put(playerId, Reflect.call(playerLocation, "clone")); }
        catch (Exception ignored) { rtpOrigins.put(playerId, playerLocation); }

        send(sender, "§eRTP trong §65 giây§e... Đang tìm vị trí an toàn trong khu vực đã tạo.");
        // Countdown notices are deliberately lightweight: only four one-shot tasks per request.
        for (int remaining = windowSeconds - 1; remaining >= 1; remaining--) {
            final int seconds = remaining;
            Reflect.scheduleLater(this, () -> {
                if (operation.equals(rtpOperations.get(playerId))) {
                    Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", playerId);
                    if (online != null) send(online, "§eRTP sau §6" + seconds + "§e giây...");
                }
            }, (windowSeconds - remaining) * 20L);
        }

        Reflect.scheduleLater(this, () -> finishRandomTeleport(playerId, operation), windowSeconds * 20L);
        tryRandomTeleport(playerId, operation, world, centerX, centerZ, radius, minDistance, attempts, new Random());
    }

    private void finishRandomTeleport(UUID playerId, UUID operation) {
        if (!operation.equals(rtpOperations.get(playerId))) return;
        Object destination = rtpDestinations.remove(playerId);
        Object origin = rtpOrigins.remove(playerId);
        Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", playerId);
        rtpOperations.remove(playerId, operation);
        if (online == null || !Boolean.TRUE.equals(Reflect.call(online, "isOnline"))) return;
        if (destination == null) {
            send(online, "§cKhông tìm được vị trí an toàn trong 5 giây. Hãy nhờ Admin pregenerate khu RTP rồi thử lại.");
            return;
        }
        if (origin != null && !sameRtpPosition(origin, Reflect.call(online, "getLocation"))) {
            send(online, "§cRTP đã bị hủy vì bạn di chuyển.");
            return;
        }
        try {
            Object result = Reflect.call(online, "teleport", destination);
            if (Boolean.FALSE.equals(result)) {
                send(online, "§cDịch chuyển thất bại. Hãy thử lại.");
                return;
            }
            rtpCooldowns.put(playerId, System.currentTimeMillis());
            send(online, "§aĐã RTP thành công! Chúc bạn sinh tồn vui vẻ.");
        } catch (Exception ex) {
            getLogger().warning("RTP teleport failed for " + playerId + ": " + ex.getMessage());
            send(online, "§cKhông thể dịch chuyển tới vị trí này. Hãy thử lại.");
        }
    }

    private boolean sameRtpPosition(Object a, Object b) {
        try {
            String aw = String.valueOf(Reflect.call(Reflect.call(a, "getWorld"), "getName"));
            String bw = String.valueOf(Reflect.call(Reflect.call(b, "getWorld"), "getName"));
            if (!aw.equals(bw)) return false;
            double dx = number(Reflect.call(a, "getX")) - number(Reflect.call(b, "getX"));
            double dy = number(Reflect.call(a, "getY")) - number(Reflect.call(b, "getY"));
            double dz = number(Reflect.call(a, "getZ")) - number(Reflect.call(b, "getZ"));
            return dx * dx + dy * dy + dz * dz < 0.04;
        } catch (Exception ignored) { return true; }
    }

    private void tryRandomTeleport(UUID playerId, UUID operation, Object world,
                                   int centerX, int centerZ, int radius, int minDistance,
                                   int attemptsLeft, Random random) {
        if (!operation.equals(rtpOperations.get(playerId)) || rtpDestinations.containsKey(playerId)) return;
        Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", playerId);
        if (online == null || !Boolean.TRUE.equals(Reflect.call(online, "isOnline"))) {
            rtpOperations.remove(playerId, operation);
            rtpOrigins.remove(playerId);
            return;
        }
        if (attemptsLeft <= 0) {
            // Leave the fixed countdown responsible for the final timeout message.
            return;
        }

        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = Math.sqrt(minDistance * (double) minDistance
                + random.nextDouble() * (radius * (double) radius - minDistance * (double) minDistance));
        int x = centerX + (int) Math.round(Math.cos(angle) * distance);
        int z = centerZ + (int) Math.round(Math.sin(angle) * distance);
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);

        try {
            // This Paper API metadata check avoids requesting ungenerated terrain.
            boolean generated = Boolean.TRUE.equals(Reflect.call(world, "isChunkGenerated", chunkX, chunkZ));
            if (!generated) {
                Reflect.scheduleLater(this, () -> tryRandomTeleport(playerId, operation, world,
                        centerX, centerZ, radius, minDistance, attemptsLeft - 1, random), 1L);
                return;
            }

            // gen=false: load an existing chunk asynchronously; never generate terrain from /rtp.
            Reflect.call(world, "getChunkAtAsync", chunkX, chunkZ, false, false,
                    (java.util.function.Consumer<Object>) chunk -> {
                        if (!operation.equals(rtpOperations.get(playerId)) || rtpDestinations.containsKey(playerId)) return;
                        Object currentPlayer = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", playerId);
                        if (currentPlayer == null || !Boolean.TRUE.equals(Reflect.call(currentPlayer, "isOnline"))) {
                            rtpOperations.remove(playerId, operation);
                            rtpOrigins.remove(playerId);
                            return;
                        }
                        try {
                            if (chunk != null) {
                                int surfaceY = ((Number) Reflect.call(world, "getHighestBlockYAt", x, z)).intValue();
                                int minHeight = ((Number) Reflect.call(world, "getMinHeight")).intValue();
                                int maxHeight = ((Number) Reflect.call(world, "getMaxHeight")).intValue();
                                // getHighestBlockYAt is the surface block; feet/head are one/two blocks above it.
                                if (surfaceY >= minHeight && surfaceY + 2 < maxHeight) {
                                    Object ground = Reflect.call(world, "getBlockAt", x, surfaceY, z);
                                    Object feet = Reflect.call(world, "getBlockAt", x, surfaceY + 1, z);
                                    Object head = Reflect.call(world, "getBlockAt", x, surfaceY + 2, z);
                                    Object groundType = Reflect.call(ground, "getType");
                                    String groundName = String.valueOf(groundType);
                                    boolean solid = Boolean.TRUE.equals(Reflect.call(groundType, "isSolid"));
                                    boolean feetPassable = Boolean.TRUE.equals(Reflect.call(feet, "isPassable"));
                                    boolean headPassable = Boolean.TRUE.equals(Reflect.call(head, "isPassable"));
                                    boolean dangerous = Set.of("LAVA", "MAGMA_BLOCK", "CACTUS", "FIRE", "SOUL_FIRE",
                                            "CAMPFIRE", "SOUL_CAMPFIRE", "POWDER_SNOW", "SWEET_BERRY_BUSH",
                                            "WITHER_ROSE", "POINTED_DRIPSTONE", "MANGROVE_ROOTS")
                                            .contains(groundName) || groundName.endsWith("LEAVES");
                                    Object destination = Reflect.construct("org.bukkit.Location", world,
                                            x + 0.5, (double) (surfaceY + 1), z + 0.5, 0.0f, 0.0f);
                                    if (solid && !dangerous && feetPassable && headPassable && !isInSpawnRegion(destination)) {
                                        rtpDestinations.putIfAbsent(playerId, destination);
                                        return;
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            getLogger().fine("RTP candidate rejected: " + ex.getMessage());
                        }
                        Reflect.scheduleLater(this, () -> tryRandomTeleport(playerId, operation, world,
                                centerX, centerZ, radius, minDistance, attemptsLeft - 1, random), 1L);
                    });
        } catch (Exception ex) {
            getLogger().fine("RTP candidate could not be loaded: " + ex.getMessage());
            Reflect.scheduleLater(this, () -> tryRandomTeleport(playerId, operation, world,
                    centerX, centerZ, radius, minDistance, attemptsLeft - 1, random), 1L);
        }
    }
    private void warp(CommandSender sender,String[] args) throws Exception {if(args.length==0){send(sender,"§e/warp <tên>");return;}String world=db.warpWorld(args[0]);double[] p=db.warp(args[0]);if(world==null||p==null){send(sender,"§cWarp không tồn tại.");return;}requirePlayer(sender);teleport(sender,world,p);}
    private void setWarp(CommandSender sender,String[] args) throws Exception {if(!admin(sender)){send(sender,"§cBạn không có quyền.");return;}requirePlayer(sender);if(args.length==0){send(sender,"§e/setwarp <tên>");return;}Object loc=Reflect.call(sender,"getLocation"),world=Reflect.call(loc,"getWorld");db.saveWarp(args[0],String.valueOf(Reflect.call(world,"getName")),number(Reflect.call(loc,"getX")),number(Reflect.call(loc,"getY")),number(Reflect.call(loc,"getZ")),((Number)Reflect.call(loc,"getYaw")).floatValue(),((Number)Reflect.call(loc,"getPitch")).floatValue());send(sender,"§aĐã tạo warp.");}
    private void deleteWarp(CommandSender sender,String[] args) throws Exception {if(!admin(sender)){send(sender,"§cBạn không có quyền.");return;}if(args.length==0){send(sender,"§e/delwarp <tên>");return;}send(sender,db.deleteWarp(args[0])?"§aĐã xóa warp.":"§cWarp không tồn tại.");}

    private void code(CommandSender sender,String[] args) throws Exception {
        if(!admin(sender)){send(sender,"§cUnknown command.");return;}
        if(args.length>=4&&args[0].equalsIgnoreCase("create")) {
            // Backward compatibility: /code create <code> <money> <uses> [expiryDays]
            if(MoneyFormat.parse(args[2])>0) {
                long reward=MoneyFormat.parse(args[2]); int uses=(int)parseLong(args[3],1); long days=args.length>=5?parseLong(args[4],0):0;
                if(reward<0||uses<0||days<0) { send(sender,"§cThông số code không hợp lệ."); return; }
                db.createCode(args[1],reward,uses,days==0?0:System.currentTimeMillis()+days*86_400_000L);
                send(sender,"§aĐã tạo code §e"+args[1]+" §7thưởng $"+MoneyFormat.compact(reward)+", tổng lượt="+(uses==0?"không giới hạn":uses)+"."); return;
            }
            if(args.length<6) { send(sender,"§e/code create <code> <money|item|key|rank|shard> <value> <amount> <uses> [expiryDays]"); return; }
            String type=args[2].toLowerCase(Locale.ROOT), value=args[3]; if(type.equals("money")){long parsedMoney=MoneyFormat.parse(value);if(parsedMoney<=0){send(sender,"§cGiá trị tiền không hợp lệ. Ví dụ 5k, 2.5m hoặc 1b.");return;}value=Long.toString(parsedMoney);} int amount=parseInt(args[4],-1), uses=parseInt(args[5],-1); long days=args.length>=7?parseLong(args[6],0):0;
            if(amount<1||amount>10000||uses<0||days<0||days>3650) { send(sender,"§cSố lượng/lượt/ngày hết hạn không hợp lệ."); return; }
            if(type.equals("item")) Reflect.enumValue("org.bukkit.Material",value.toUpperCase(Locale.ROOT));
            if(type.equals("rank")&&!db.rankExists(value)) { send(sender,"§cRank thưởng chưa tồn tại."); return; }
            if(!Set.of("money","item","key","rank","shard").contains(type)) { send(sender,"§cLoại thưởng không hợp lệ."); return; }
            long expires=days==0?0:System.currentTimeMillis()+days*86_400_000L;
            db.createCodeReward(args[1],type,value,amount,uses,expires);
            send(sender,"§aĐã tạo code §e"+args[1]+" §7| reward="+type+":"+value+" x"+amount+" | uses="+(uses==0?"∞":uses)+"."); return;
        }
        if(args.length>=2&&args[0].equalsIgnoreCase("delete")){db.deleteCode(args[1]);send(sender,"§aĐã xóa code.");return;}
        if(args.length>=1&&args[0].equalsIgnoreCase("list")){
            send(sender,"§6§lHAVEN SMP — REDEEM CODES");
            for(String[] row:db.codeList(30)) send(sender,"§e"+row[0]+" §7| "+row[1]+":"+row[2]+" x"+row[3]+" §8uses="+row[4]+"/"+(row[5].equals("0")?"∞":row[5])+" active="+row[7]);
            return;
        }
        if(args.length>=2&&args[0].equalsIgnoreCase("info")){send(sender,db.codeInfo(args[1]));return;}
        if(args.length>=7&&args[0].equalsIgnoreCase("bulk")) {
            String prefix=args[1]; int count=parseInt(args[2],0); String type=args[3].toLowerCase(Locale.ROOT); String value=args[4]; if(type.equals("money")){long parsedMoney=MoneyFormat.parse(value);if(parsedMoney<=0){send(sender,"§cGiá trị tiền không hợp lệ. Ví dụ 5k, 2.5m hoặc 1b.");return;}value=Long.toString(parsedMoney);} int amount=parseInt(args[5],-1); int uses=parseInt(args[6],-1); long days=args.length>=8?parseLong(args[7],0):0;
            if(count<1||count>100||amount<1||uses<0||days<0||days>3650) { send(sender,"§cBulk phải từ 1–100 code và tham số hợp lệ."); return; }
            if(type.equals("item")) Reflect.enumValue("org.bukkit.Material",value.toUpperCase(Locale.ROOT));
            if(type.equals("rank")&&!db.rankExists(value)) { send(sender,"§cRank thưởng chưa tồn tại."); return; }
            long expires=days==0?0:System.currentTimeMillis()+days*86_400_000L; List<String> codes=new ArrayList<>();
            for(int i=0;i<count;i++){String generated=prefix+randomCode();db.createCodeReward(generated,type,value,amount,uses,expires);codes.add(generated);}
            send(sender,"§aĐã tạo "+count+" code:");
            for(int i=0;i<codes.size();i+=5) send(sender,"§f"+String.join("§7, §f",codes.subList(i,Math.min(i+5,codes.size()))));
            return;
        }
        send(sender,"§e/code create <code> <type> <value> <amount> <uses> [days] | /code delete <code> | /code bulk <prefix> <count> <type> <value> <amount> <uses> [days]");
    }

    private String randomCode(){String chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";StringBuilder b=new StringBuilder();java.security.SecureRandom random=new java.security.SecureRandom();for(int i=0;i<8;i++)b.append(chars.charAt(random.nextInt(chars.length())));return b.toString();}

    private void redeem(CommandSender sender,String[] args) throws Exception {
        requirePlayer(sender); if(args.length==0){send(sender,"§e/redeem <code>");return;}
        String[] reward=db.redeemCodeReward(args[0],uuid(sender));
        if(reward==null){send(sender,"§cCode không hợp lệ, hết hạn, hết lượt hoặc bạn đã sử dụng.");return;}
        String type=reward[0].toLowerCase(Locale.ROOT),value=reward[1];int amount=parseInt(reward[2],1);
        switch(type) {
            case "money" -> send(sender,"§aRedeem thành công! Tiền đã được cộng vào tài khoản.");
            case "item" -> { if(!giveMaterial(sender,value,amount)) send(sender,"§cCode đã được redeem nhưng không thể giao vật phẩm. Báo admin với tên code: "+args[0]); else send(sender,"§aBạn nhận được "+amount+"x "+value+"."); }
            case "key" -> { db.addKey(uuid(sender),value,amount); send(sender,"§aBạn nhận được "+amount+" key "+value+"."); }
            case "shard" -> { db.addShards(uuid(sender),amount,"REDEEM:"+args[0]); notifyShardWallet(sender,false); send(sender,"§aRedeem thành công."); }
            case "rank" -> {
                if(!db.rankExists(value)||db.staffLevel(value)>0) { send(sender,"§cRank thưởng không hợp lệ; hãy báo admin."); return; }
                long expires=amount>0?System.currentTimeMillis()+amount*86_400_000L:0; db.setRank(uuid(sender),value,expires); refreshStaffPermissions(sender); updateScoreboard(sender); send(sender,"§aBạn nhận được rank §e"+value+" §atrong "+amount+" ngày.");
            }
            default -> send(sender,"§cLoại phần thưởng không hợp lệ.");
        }
    }

    private boolean giveMaterial(Object player,String material,int amount) {
        try {
            Object stack=Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material",material.toUpperCase(Locale.ROOT)),amount);
            Object array=java.lang.reflect.Array.newInstance(Reflect.type("org.bukkit.inventory.ItemStack"),1); java.lang.reflect.Array.set(array,0,stack);
            Object leftovers=Reflect.call(Reflect.call(player,"getInventory"),"addItem",array);
            if(leftovers instanceof Map<?,?> map&&!map.isEmpty()) {
                Object world=Reflect.call(player,"getWorld"),location=Reflect.call(player,"getLocation");
                for(Object leftover:map.values()) Reflect.call(world,"dropItemNaturally",location,leftover);
            }
            return true;
        } catch(Exception e) { return false; }
    }

    private void crate(CommandSender sender,String[] args) throws Exception {
        if(!admin(sender)){send(sender,"§cUnknown command.");return;}
        if(args.length>=1&&args[0].equalsIgnoreCase("editor")){requirePlayer(sender);gui.openCrateEditor(sender,args.length>=2?args[1]:"common");return;}
        if(args.length>=2&&args[0].equalsIgnoreCase("preview")){requirePlayer(sender);gui.openCratePreview(sender,args[1]);return;}
        if(args.length<4||(!args[0].equalsIgnoreCase("give")&&!args[0].equalsIgnoreCase("key"))){send(sender,"§e/crate give <player> <crate> <amount> | /crate preview <crate> | /crate editor");return;}
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[1]);if(target==null){send(sender,"§cNgười chơi không online.");return;}
        int amount=(int)parseLong(args[3],0);if(amount<=0||amount>1000){send(sender,"§cSố lượng không hợp lệ.");return;}
        db.addKey(uuid(target),args[2],amount);send(sender,"§aĐã cấp "+amount+" key cho "+name(target)+".");send(target,"§aBạn nhận được "+amount+" key "+args[2]+".");
    }

    private void daily(CommandSender sender) throws Exception {
        requirePlayer(sender);
        long day=java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toEpochDay();
        long reward=db.claimDailyReward(uuid(sender),day,cfgInt("rewards.daily-money",100),cfgInt("rewards.daily-streak-bonus",20));
        if(reward<0){send(sender,"§cBạn đã nhận daily hôm nay rồi. Streak hiện tại: §e"+db.dailyStreak(uuid(sender))+" ngày§c.");return;}
        send(sender,"§aBạn nhận được daily reward §6$"+MoneyFormat.compact(reward)+"§a. Streak: §e"+db.dailyStreak(uuid(sender))+" ngày§a.");
    }
    private void streak(CommandSender sender) throws Exception {requirePlayer(sender);send(sender,"§eDaily streak hiện tại: §6"+db.dailyStreak(uuid(sender))+" ngày §7| Nhận bằng /daily mỗi ngày.");}
    private void playtime(CommandSender sender) throws Exception {requirePlayer(sender);long seconds=totalPlaytime(uuid(sender));send(sender,"§ePlaytime: §f"+(seconds/3600)+" giờ "+((seconds%3600)/60)+" phút");}
    private long totalPlaytime(UUID id) throws Exception {long stored=db.playtime(id);long current=(System.currentTimeMillis()-joinedAt.getOrDefault(id,System.currentTimeMillis()))/1000L;return stored+Math.max(0,current);}
    private void playtimeReward(CommandSender sender) throws Exception {
        requirePlayer(sender);long interval=Math.max(1,cfgInt("rewards.playtime-minutes",60))*60L;long reward=Math.max(0,cfgInt("rewards.playtime-money",500));
        long claimed=db.claimPlaytimeReward(uuid(sender),totalPlaytime(uuid(sender)),interval,reward);
        if(claimed<=0)send(sender,"§cChưa có mốc playtime mới để nhận. Mốc thưởng: §e"+(interval/60)+" phút§c.");
        else send(sender,"§aBạn nhận được §6$"+MoneyFormat.compact(claimed)+" §atừ Playtime Rewards.");
    }
    private void admin(CommandSender sender,String[] args) throws Exception {
        if(!admin(sender)){send(sender,"§cUnknown command.");return;}
        if(args.length==0||args[0].equalsIgnoreCase("info")){send(sender,"§6Haven SMP Core 0.5.10 §7| SQLite: OK §7| LifeSteal: "+(life.enabled()?"ON":"OFF"));return;}
        if(args[0].equalsIgnoreCase("gui")){if(!isPlayer(sender)){send(sender,"§cConsole không có GUI.");return;}gui.openAdmin(sender);return;}
        if(args[0].equalsIgnoreCase("ownerpassword")){
            if(isPlayer(sender)){send(sender,"§cLệnh này chỉ dùng từ console.");return;}
            if(args.length>1){send(sender,"§cKhông nhập mật khẩu trực tiếp vào command để tránh lộ trong command log. Dùng: ltzcore ownerpassword");return;}
            String ownerName=ownerName();Object offline=Reflect.callStatic("org.bukkit.Bukkit","getOfflinePlayer",ownerName);UUID id=(UUID)Reflect.call(offline,"getUniqueId");
            String oneTimePassword=generateOwnerPassword();
            AuthManager.Result result=auth.consoleSetPassword(id,ownerName,oneTimePassword);
            if(result==AuthManager.Result.OK){db.setRank(id,"Owner",0);send(sender,"§aĐã thiết lập tài khoản Owner cho §f"+ownerName+"§a. Mật khẩu tạm thời (chỉ hiển thị lần này): §e"+oneTimePassword+"§a. Vào game /login bằng mật khẩu này rồi dùng /changepassword để đổi.");}
            else send(sender,"§cKhông thể thiết lập tài khoản Owner.");
            return;
        }
        if(args[0].equalsIgnoreCase("logs")){
            send(sender,"§6§lHAVEN SMP — RECENT TRANSACTIONS");
            for(String[] row:db.recentTransactions(15)) send(sender,"§7"+new java.util.Date(Long.parseLong(row[3]))+" §f"+row[0]+" §e$"+MoneyFormat.compact(Long.parseLong(row[1]))+" §8"+row[2]);
            return;
        }
        if(args[0].equalsIgnoreCase("backup")){File backups=new File(getDataFolder(),"backups");backups.mkdirs();File target=new File(backups,"database-"+System.currentTimeMillis()+".db");try{db.backupTo(target);send(sender,"§aĐã tạo bản backup: "+target.getName());}catch(Exception e){send(sender,"§cBackup thất bại: "+e.getMessage());}return;}
        if(args[0].equalsIgnoreCase("reload")){reloadConfig();send(sender,"§aĐã reload config. Một số giá trị chỉ áp dụng sau khi restart.");return;}
        send(sender,"§e/ltzcore [info|gui|reload|backup|logs|ownerpassword (console only)]");
    }

    private void awardLifeStealRewards() {
        try {
            long eventStart=Long.parseLong(db.getSetting("lifesteal.eventStartedAt","0"));
            if(eventStart<=0||Long.parseLong(db.getSetting("lifesteal.rewardedEventStartedAt","0"))==eventStart)return;
            List<String[]> top=life.eventTop(eventStart,100); Map<UUID,Long> payouts=new LinkedHashMap<>();
            long participation=Math.max(0,cfgInt("lifesteal.rewards.participation",250));
            for(String[] row:top) { try { payouts.put(UUID.fromString(row[0]),participation); } catch(Exception ignored) { } }
            long first=Math.max(0,cfgInt("lifesteal.rewards.first",10000));
            long second=Math.max(0,cfgInt("lifesteal.rewards.second",5000));
            long third=Math.max(0,cfgInt("lifesteal.rewards.third",2500));
            if(top.size()>0)payouts.put(UUID.fromString(top.get(0)[0]),first);
            if(top.size()>1)payouts.put(UUID.fromString(top.get(1)[0]),second);
            if(top.size()>2)payouts.put(UUID.fromString(top.get(2)[0]),third);
            if(db.payLifeStealEvent(eventStart,payouts)) {
                broadcast("§6§lHAVEN SMP §7— LifeSteal Event rewards:");
                if(top.size()>0)broadcast("§e#1 §f"+top.get(0)[2]+" §7nhận §6$"+MoneyFormat.compact(first));
                if(top.size()>1)broadcast("§e#2 §f"+top.get(1)[2]+" §7nhận §6$"+MoneyFormat.compact(second));
                if(top.size()>2)broadcast("§e#3 §f"+top.get(2)[2]+" §7nhận §6$"+MoneyFormat.compact(third));
                if(top.size()>3&&participation>0)broadcast("§aNgười tham gia khác nhận §6$"+MoneyFormat.compact(participation)+"§a.");
            }
        } catch(Exception e) { getLogger().warning("Could not award LifeSteal event rewards: "+e.getMessage()); }
    }

    // -------------------- Player /setting & Mob Spawn Control --------------------
    private void loadPlayerSettings(UUID id) {
        try {
            playerSettingsCache.put(id, new ConcurrentHashMap<>(db.playerSettings(id)));
        } catch (Exception ignored) {
            playerSettingsCache.putIfAbsent(id, new ConcurrentHashMap<>());
        }
    }

    public boolean isPlayerSettingEnabled(UUID id, String key) {
        if (id == null || key == null) return true;
        Map<String, Boolean> map = playerSettingsCache.get(id);
        if (map == null) {
            loadPlayerSettings(id);
            map = playerSettingsCache.get(id);
        }
        return map == null || map.getOrDefault(key.toLowerCase(Locale.ROOT), true);
    }

    public boolean togglePlayerSetting(Object player, String key) {
        UUID id = uuid(player);
        String k = key.toLowerCase(Locale.ROOT);
        boolean next = !isPlayerSettingEnabled(id, k);
        playerSettingsCache.computeIfAbsent(id, u -> new ConcurrentHashMap<>()).put(k, next);
        try { db.setPlayerSetting(id, k, next); } catch (Exception e) { getLogger().warning("Could not save setting " + k + ": " + e.getMessage()); }
        if (k.equals("scoreboard")) {
            if (next) updateScoreboard(player);
            else hideScoreboard(player);
        }
        playUiSound(player, "ui.button.click");
        return next;
    }

    public void resetPlayerSettings(Object player) {
        UUID id = uuid(player);
        playerSettingsCache.put(id, new ConcurrentHashMap<>());
        try { db.resetPlayerSettings(id); } catch (Exception ignored) { }
        updateScoreboard(player);
        playUiSound(player, "ui.button.click");
        send(player, "§aĐã khôi phục toàn bộ cài đặt về mặc định.");
    }

    public void playUiSound(Object player, String soundKey) {
        if (player == null || !isPlayer(player)) return;
        try {
            if (!isPlayerSettingEnabled(uuid(player), "sounds")) return;
            Object loc = Reflect.call(player, "getLocation");
            Reflect.call(player, "playSound", loc, soundKey, 0.7f, 1.15f);
        } catch (Exception ignored) { }
    }

    private void hideScoreboard(Object player) {
        try {
            UUID id = uuid(player);
            scoreboards.remove(id);
            objectives.remove(id);
            scoreEntries.remove(id);
            Object manager = Reflect.callStatic("org.bukkit.Bukkit", "getScoreboardManager");
            Object main = Reflect.call(manager, "getMainScoreboard");
            syncNameTagTeams(main);
            Reflect.call(player, "setScoreboard", main);
        } catch (Exception ignored) { }
    }

    private void onMobSpawnSetting(Object event) {
        try {
            if (Boolean.TRUE.equals(Reflect.call(event, "isCancelled"))) return;
            String reason = String.valueOf(Reflect.call(event, "getSpawnReason"));
            if (!Set.of("NATURAL", "DEFAULT", "REINFORCEMENTS", "JOCKEY", "PATROL", "CHUNK_GEN", "VILLAGE_INVASION").contains(reason)) return;
            Object entity = Reflect.call(event, "getEntity");
            if (!isHostileMob(entity)) return;
            Object loc = Reflect.call(event, "getLocation");
            Object world = Reflect.call(loc, "getWorld");
            int radius = Math.max(16, Math.min(128, cfgInt("settings.mob-spawn-radius", 64)));
            double radiusSq = (double) radius * radius;
            Object players = Reflect.call(world, "getPlayers");
            if (players instanceof Iterable<?> iterable) {
                for (Object player : iterable) {
                    UUID pid = uuid(player);
                    if (!auth.isAuthenticated(pid) || isPlayerSettingEnabled(pid, "mob_spawn")) continue;
                    Object pLoc = Reflect.call(player, "getLocation");
                    double distSq = ((Number) Reflect.call(pLoc, "distanceSquared", loc)).doubleValue();
                    if (distSq <= radiusSq) {
                        Reflect.call(event, "setCancelled", true);
                        return;
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private boolean isHostileMob(Object entity) {
        if (entity == null) return false;
        try {
            String typeName = String.valueOf(Reflect.call(entity, "getType"));
            if (Set.of("ENDER_DRAGON", "WITHER", "WARDEN").contains(typeName)) return false;
            try {
                if (Reflect.type("org.bukkit.entity.Enemy").isInstance(entity)) return true;
            } catch (Exception ignored) { }
            return Reflect.type("org.bukkit.entity.Monster").isInstance(entity)
                    || Set.of("SLIME", "MAGMA_CUBE", "PHANTOM", "GHAST", "HOGLIN", "SHULKER").contains(typeName);
        } catch (Exception e) { return false; }
    }

    // -------------------- Staff Commands (/spec, /gms, /gm, /tp, /kick, /invsee, /sc) --------------------
    private void specCommand(CommandSender sender) {
        if (!staff(sender) || !isPlayer(sender)) { send(sender, "§cUnknown command."); return; }
        String current = String.valueOf(Reflect.call(sender, "getGameMode"));
        String next = "SPECTATOR".equals(current) ? "SURVIVAL" : "SPECTATOR";
        Reflect.call(sender, "setGameMode", Reflect.enumValue("org.bukkit.GameMode", next));
        send(sender, "SPECTATOR".equals(next) ? "§3[Staff] §aĐã bật chế độ §bSpectator§a." : "§3[Staff] §aĐã quay lại chế độ §fSurvival§a.");
    }

    private void gmsCommand(CommandSender sender) {
        if (!staff(sender) || !isPlayer(sender)) { send(sender, "§cUnknown command."); return; }
        Reflect.call(sender, "setGameMode", Reflect.enumValue("org.bukkit.GameMode", "SURVIVAL"));
        send(sender, "§3[Staff] §aĐã chuyển sang chế độ §fSurvival§a.");
    }

    private void gamemodeCommand(CommandSender sender, String[] args) {
        if (!staff(sender)) { send(sender, "§cUnknown command."); return; }
        if (args.length == 0) {
            send(sender, admin(sender) ? "§e/gm <s|c|a|sp> [player]" : "§e/gm <sp|s> §7hoặc dùng nhanh §e/spec §7/ §e/gms");
            return;
        }
        String mode = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "s", "0", "survival" -> "SURVIVAL";
            case "c", "1", "creative" -> "CREATIVE";
            case "a", "2", "adventure" -> "ADVENTURE";
            case "sp", "spec", "3", "spectator" -> "SPECTATOR";
            default -> null;
        };
        if (mode == null) { send(sender, "§cChế độ không hợp lệ (s, c, a, sp)."); return; }
        boolean isAdm = admin(sender);
        if (!isAdm && !mode.equals("SPECTATOR") && !mode.equals("SURVIVAL")) {
            send(sender, "§cRank Staff chỉ được dùng Spectator (/spec) và Survival (/gms).");
            return;
        }
        Object target;
        if (args.length >= 2) {
            if (!isAdm) { send(sender, "§cRank Staff chỉ đổi gamemode cho bản thân."); return; }
            target = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[1]);
            if (target == null) { send(sender, "§cNgười chơi không online."); return; }
        } else {
            if (!isPlayer(sender)) { send(sender, "§e/gm <mode> <player>"); return; }
            target = sender;
        }
        Reflect.call(target, "setGameMode", Reflect.enumValue("org.bukkit.GameMode", mode));
        send(sender, "§aĐã đặt chế độ §e" + mode + " §acho §f" + name(target) + "§a.");
        if (target != sender) send(target, "§aChế độ chơi của bạn đã được đổi sang §e" + mode + "§a.");
    }

    private void tpCommand(CommandSender sender, String[] args) {
        if (!staff(sender)) { send(sender, "§cUnknown command."); return; }
        if (args.length == 1) {
            if (!isPlayer(sender)) { send(sender, "§e/tp <player> <target>"); return; }
            Object target = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
            if (target == null) { send(sender, "§cNgười chơi không online."); return; }
            Reflect.call(sender, "teleport", Reflect.call(target, "getLocation"));
            send(sender, "§3[Staff] §aĐã dịch chuyển tới §f" + name(target) + "§a.");
            return;
        }
        if (args.length >= 2 && admin(sender)) {
            Object from = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
            Object to = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[1]);
            if (from == null || to == null) { send(sender, "§cMột trong hai người chơi không online."); return; }
            Reflect.call(from, "teleport", Reflect.call(to, "getLocation"));
            send(sender, "§aĐã dịch chuyển §f" + name(from) + " §atới §f" + name(to) + "§a.");
            return;
        }
        send(sender, admin(sender) ? "§e/tp <player> [target]" : "§e/tp <player>");
    }

    private void kickCommand(CommandSender sender, String[] args) {
        if (!staff(sender)) { send(sender, "§cUnknown command."); return; }
        if (args.length < 1) { send(sender, "§e/kick <player> [lý do]"); return; }
        Object target = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
        if (target == null) { send(sender, "§cNgười chơi không online."); return; }
        try {
            if (isPlayer(sender) && db.staffLevel(db.rank(uuid(target))) >= db.staffLevel(db.rank(uuid(sender)))) {
                send(sender, "§cKhông thể kick người có rank ngang hoặc cao hơn.");
                return;
            }
        } catch (Exception ignored) { }
        String reason = args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Vi phạm nội quy Haven SMP";
Reflect.call(target, "kickPlayer", "§cBạn đã bị kick khỏi Haven SMP\n§7Lý do: §f" + reason);
        broadcast("§3[Staff] §f" + name(target) + " §7đã bị kick bởi §f" + (isPlayer(sender) ? name(sender) : "CONSOLE") + " §8(§f" + reason + "§8)");
    }

    private void invseeCommand(CommandSender sender, String[] args) {
        if (!staff(sender) || !isPlayer(sender)) { send(sender, "§cUnknown command."); return; }
        if (args.length < 1) { send(sender, "§e/invsee <player>"); return; }
        Object target = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
        if (target == null) { send(sender, "§cNgười chơi không online."); return; }
        Reflect.call(sender, "openInventory", Reflect.call(target, "getInventory"));
        send(sender, "§3[Staff] §7Đang xem túi đồ của §f" + name(target) + "§7.");
    }

    private void staffChatCommand(CommandSender sender, String[] args) {
        if (!staff(sender)) { send(sender, "§cUnknown command."); return; }
        if (args.length == 0) { send(sender, "§e/sc <tin nhắn>"); return; }
        String text = String.join(" ", args);
        String who = isPlayer(sender) ? rankPrefix(safeRankName(uuid(sender))) + "§f" + name(sender) : "§6[Console]";
        String formatted = "§3§l[STAFF] " + who + " §8» §b" + text;
        Object raw = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers");
        if (raw instanceof Collection<?> players) {
            for (Object p : players) {
                if (staff(p)) send(p, formatted);
            }
        }
        getLogger().info("[STAFF] " + (isPlayer(sender) ? name(sender) : "CONSOLE") + ": " + text);
    }

    private String safeRankName(UUID id) {
        try { return db.rank(id); } catch (Exception e) { return "Member"; }
    }

    // -------------------- Player /stats [player] Tracking & GUI --------------------
    private void incrementStat(UUID id, long kills, long deaths, long mobs, long broken, long placed) {
        if (id == null) return;
        long[] buf = pendingStats.computeIfAbsent(id, k -> new long[5]);
        synchronized (buf) {
            buf[0] += kills;
            buf[1] += deaths;
            buf[2] += mobs;
            buf[3] += broken;
            buf[4] += placed;
        }
    }

    private void flushPendingStats(UUID id) {
        if (id == null || db == null) return;
        long[] buf = pendingStats.remove(id);
        if (buf == null) return;
        long k, d, m, b, p;
        synchronized (buf) { k = buf[0]; d = buf[1]; m = buf[2]; b = buf[3]; p = buf[4]; }
        try { db.addPlayerStats(id, k, d, m, b, p); } catch (Exception ignored) { }
    }

    private void flushAllPendingStats() {
        for (UUID id : new ArrayList<>(pendingStats.keySet())) flushPendingStats(id);
    }

    private void onEntityDeathStat(Object event) {
        try {
            Object entity = Reflect.call(event, "getEntity");
            if (entity == null || isPlayer(entity)) return;
            Object killer = Reflect.call(entity, "getKiller");
            if (killer != null && isPlayer(killer) && auth.isAuthenticated(uuid(killer))) {
                incrementStat(uuid(killer), 0, 0, 1, 0, 0);
            }
        } catch (Exception ignored) { }
    }

    private void onBlockBreakStat(Object event) {
        try {
            if (Boolean.TRUE.equals(Reflect.call(event, "isCancelled"))) return;
            Object player = Reflect.call(event, "getPlayer");
            if (player != null && auth.isAuthenticated(uuid(player))) {
                incrementStat(uuid(player), 0, 0, 0, 1, 0);
            }
        } catch (Exception ignored) { }
    }

    private void onBlockPlaceStat(Object event) {
        try {
            if (Boolean.TRUE.equals(Reflect.call(event, "isCancelled"))) return;
            Object player = Reflect.call(event, "getPlayer");
            if (player != null && auth.isAuthenticated(uuid(player))) {
                incrementStat(uuid(player), 0, 0, 0, 0, 1);
            }
        } catch (Exception ignored) { }
    }

    private void statsCommand(CommandSender sender, String[] args) throws Exception {
        requirePlayer(sender);
        UUID targetId;
        String targetName;
        boolean targetOnline;
        if (args.length == 0) {
            targetId = uuid(sender);
            targetName = name(sender);
            targetOnline = true;
        } else {
            Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
            if (online != null) {
                targetId = uuid(online);
                targetName = name(online);
                targetOnline = true;
            } else {
                String[] found = db.findUserByName(args[0]);
                if (found == null) {
                    send(sender, "§cKhông tìm thấy người chơi §f" + args[0] + " §ctrong dữ liệu server.");
                    return;
                }
                targetId = UUID.fromString(found[0]);
                targetName = found[1];
                targetOnline = false;
            }
        }
        flushPendingStats(targetId);
        long pt = targetOnline ? totalPlaytimeSeconds(targetId) : db.playtime(targetId);
        long[] stats = db.playerStats(targetId);
        gui.openStats(sender, targetId, targetName, targetOnline, pt, stats);
    }

    // -------------------- Name Tag Rank Prefix Sync (Above-Head + TAB) --------------------
    private void syncAllNameTags() {
        try {
            Object manager = Reflect.callStatic("org.bukkit.Bukkit", "getScoreboardManager");
            if (manager == null) return;
            Object mainBoard = Reflect.call(manager, "getMainScoreboard");
            if (mainBoard != null) syncNameTagTeams(mainBoard);
            for (Object board : scoreboards.values()) {
                if (board != null) syncNameTagTeams(board);
            }
        } catch (Exception ignored) { }
    }

    /** Explicit TAB & NameTag rank slot: Owner (#1) -> Staff (#2) -> Admin (#3) -> +++ -> ++ -> + -> Member. */
    private int tabRankBucket(String rank, int dbPriority) {
        if (rank == null) return 900;
        return switch (rank.toLowerCase(Locale.ROOT)) {
            case "owner" -> 10;
            case "staff" -> 20;
            case "admin" -> 30;
            case "+++" -> 40;
            case "++" -> 50;
            case "+" -> 60;
            case "member" -> 900;
            default -> Math.max(35, Math.min(890, 500 - dbPriority));
        };
    }

    private void syncNameTagTeams(Object board) {
        if (board == null) return;
        try {
            Object raw = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers");
            if (!(raw instanceof Collection<?> players)) return;
            for (Object p : players) {
                UUID pid = uuid(p);
                if (!auth.isAuthenticated(pid)) continue;
                String pName = name(p);
                String rank = db.rank(pid);
                int priority = db.rankPriority(rank);
                int bucket = tabRankBucket(rank, priority);
                String prefix = db.rankPrefix(rank) + " ";
                String teamId = "ltz_" + String.format(Locale.ROOT, "%03d", bucket)
                        + "_" + Integer.toHexString(rank.toLowerCase(Locale.ROOT).hashCode() & 0xFFFF);

                Object existing = Reflect.call(board, "getEntryTeam", pName);
                if (existing != null) {
                    String existingName = String.valueOf(Reflect.call(existing, "getName"));
                    if (existingName.startsWith("ltz_") && !existingName.equals(teamId)) {
                        try { Reflect.call(existing, "removeEntry", pName); } catch (Exception ignored) { }
                    }
                }
                Object team = Reflect.call(board, "getTeam", teamId);
                if (team == null) team = Reflect.call(board, "registerNewTeam", teamId);
                try {
                    Object serializer = Reflect.callStatic("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer", "legacySection");
                    Object comp = Reflect.call(serializer, "deserialize", prefix);
                    Reflect.call(team, "prefix", comp);
                } catch (Exception legacy) {
                    try { Reflect.call(team, "setPrefix", prefix); } catch (Exception ignored) { }
                }
                if (!Boolean.TRUE.equals(Reflect.call(team, "hasEntry", pName))) {
                    try { Reflect.call(team, "addEntry", pName); } catch (Exception ignored) { }
                }
                // Paper 1.21.2+ native TAB ordering (higher value appears higher in TAB list).
                try { Reflect.call(p, "setPlayerListOrder", Math.max(0, 1000 - bucket)); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    // -------------------- Quick Staff Commands (/addstaff, /removestaff) --------------------
    private void addStaffCommand(CommandSender sender, String[] args) throws Exception {
        if (!admin(sender)) { send(sender, "§cUnknown command."); return; }
        if (args.length < 1) { send(sender, "§e/addstaff <player>"); return; }
        Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
        UUID targetId;
        String targetName;
        if (online != null) {
            targetId = uuid(online);
            targetName = name(online);
        } else {
            String[] found = db.findUserByName(args[0]);
            if (found != null) {
                targetId = UUID.fromString(found[0]);
                targetName = found[1];
            } else {
                Object offline = Reflect.callStatic("org.bukkit.Bukkit", "getOfflinePlayer", args[0]);
                targetId = (UUID) Reflect.call(offline, "getUniqueId");
                targetName = args[0];
            }
        }
        int existingLevel = db.staffLevel(db.rank(targetId));
        if (existingLevel >= 2 && !owner(sender)) {
            send(sender, "§cKhông thể đổi rank của Admin/Owner.");
            return;
        }
        db.setRank(targetId, "Staff", 0);
        send(sender, "§aĐã cấp rank §3[Staff] §acho §f" + targetName + "§a.");
        if (online != null) {
            refreshStaffPermissions(online);
            updateScoreboard(online);
            syncAllNameTags();
            try { Reflect.call(online, "updateCommands"); } catch (Exception ignored) { }
            send(online, "§aBạn đã được cấp rank §3[Staff]§a! Dùng §e/spec§a, §e/gms§a, §e/tp§a, §e/vanish§a, §e/baitbase§a, §e/sc§a.");
        }
    }

    private void removeStaffCommand(CommandSender sender, String[] args) throws Exception {
        if (!admin(sender)) { send(sender, "§cUnknown command."); return; }
        if (args.length < 1) { send(sender, "§e/removestaff <player>"); return; }
        Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[0]);
        UUID targetId;
        String targetName;
        if (online != null) {
            targetId = uuid(online);
            targetName = name(online);
        } else {
            String[] found = db.findUserByName(args[0]);
            if (found != null) {
                targetId = UUID.fromString(found[0]);
                targetName = found[1];
            } else {
                Object offline = Reflect.callStatic("org.bukkit.Bukkit", "getOfflinePlayer", args[0]);
                targetId = (UUID) Reflect.call(offline, "getUniqueId");
                targetName = args[0];
            }
        }
        String current = db.rank(targetId);
        if (!"Staff".equalsIgnoreCase(current)) {
            send(sender, "§cNgười chơi §f" + targetName + " §chiện đang là rank §f" + current + "§c, không phải Staff.");
            return;
        }
        db.setRank(targetId, "Member", 0);
        send(sender, "§aĐã gỡ rank §3[Staff] §acủa §f" + targetName + " §avà chuyển về Member.");
        if (online != null) {
            refreshStaffPermissions(online);
            updateScoreboard(online);
            syncAllNameTags();
            try { Reflect.call(online, "updateCommands"); } catch (Exception ignored) { }
            send(online, "§eRank Staff của bạn đã được gỡ về §7[Member]§e.");
        }
    }

    // -------------------- Wizox-Style Mini Anti-Cheater Bait Base (/baitbase) --------------------
    private static final String[] BAIT_SPAWNER_MOBS = {
            "ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "BLAZE",
            "COW", "PIG", "IRON_GOLEM", "ENDERMAN", "WITCH", "SLIME"
    };

    private void baitBaseCommand(CommandSender sender, String[] args) throws Exception {
        if (!staff(sender) || !isPlayer(sender)) { send(sender, "§cUnknown command."); return; }
        UUID staffId = uuid(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("undo")) {
            BaitBaseSnapshot snap = lastBaitBases.remove(staffId);
            if (snap == null) { send(sender, "§cBạn chưa tạo bait base nào gần đây để hoàn tác."); return; }
            Object world = Reflect.callStatic("org.bukkit.Bukkit", "getWorld", snap.world());
            if (world == null) { send(sender, "§cWorld không tồn tại."); return; }
            db.deleteSpawner(snap.world(), snap.spawnerX(), snap.spawnerY(), snap.spawnerZ());
            for (SavedBlock sb : snap.blocks()) {
                try {
                    Object blk = Reflect.call(world, "getBlockAt", sb.x(), sb.y(), sb.z());
                    Reflect.call(blk, "setType", Reflect.enumValue("org.bukkit.Material", sb.material()), false);
                } catch (Exception ignored) { }
            }
            activeBaitBases.removeIf(z -> z.creator().equals(staffId) && z.world().equals(snap.world())
                    && snap.spawnerX() >= z.minX() && snap.spawnerX() <= z.maxX()
                    && snap.spawnerY() >= z.minY() && snap.spawnerY() <= z.maxY()
                    && snap.spawnerZ() >= z.minZ() && snap.spawnerZ() <= z.maxZ());
            send(sender, "§aĐã hoàn tác và xóa sạch mini bait base vừa tạo.");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            activeBaitBases.removeIf(z -> z.creator().equals(staffId));
            send(sender, "§aĐã tắt cảnh báo các bait base do bạn đặt.");
            return;
        }

        Object loc = Reflect.call(sender, "getLocation");
        if (isInSpawnRegion(loc)) { send(sender, "§cKhông nên tạo bait base ngay bên trong vùng Spawn."); return; }
        Object world = Reflect.call(loc, "getWorld");
        String worldName = String.valueOf(Reflect.call(world, "getName"));
        int cx = ((Number) Reflect.call(loc, "getBlockX")).intValue();
        int cy = ((Number) Reflect.call(loc, "getBlockY")).intValue();
        int cz = ((Number) Reflect.call(loc, "getBlockZ")).intValue();

        String wallMat = cy < 0 ? "COBBLED_DEEPSLATE" : "COBBLESTONE";
        Object wallEnum = Reflect.enumValue("org.bukkit.Material", wallMat);
        Object airEnum = Reflect.enumValue("org.bukkit.Material", "AIR");

        List<SavedBlock> snapshot = new ArrayList<>(125);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Object blk = Reflect.call(world, "getBlockAt", cx + dx, cy + dy, cz + dz);
                    snapshot.add(new SavedBlock(cx + dx, cy + dy, cz + dz, String.valueOf(Reflect.call(blk, "getType"))));
                }
            }
        }

        // Build enclosed 5x5x5 mini room (3x3x3 hollow interior)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean shell = (dx == -2 || dx == 2 || dy == -1 || dy == 3 || dz == -2 || dz == 2);
                    Object blk = Reflect.call(world, "getBlockAt", cx + dx, cy + dy, cz + dz);
                    Reflect.call(blk, "setType", shell ? wallEnum : airEnum, false);
                }
            }
        }

        // Place ONLY the 3 exact bait items: 1 random Mob Spawner, 1 Cake, 1 Amethyst Cluster
        int spX = cx - 1, spY = cy, spZ = cz - 1;
        String mob = BAIT_SPAWNER_MOBS[java.util.concurrent.ThreadLocalRandom.current().nextInt(BAIT_SPAWNER_MOBS.length)];
        Object spawnerBlock = Reflect.call(world, "getBlockAt", spX, spY, spZ);
        Reflect.call(spawnerBlock, "setType", Reflect.enumValue("org.bukkit.Material", "SPAWNER"), false);
        try {
            Object state = Reflect.call(spawnerBlock, "getState");
            Reflect.call(state, "setSpawnedType", Reflect.enumValue("org.bukkit.entity.EntityType", mob));
            Reflect.call(state, "update", true, false);
        } catch (Exception ignored) { }
        db.saveSpawner(worldName, spX, spY, spZ, mob, 1, staffId.toString());

        Object cakeBlock = Reflect.call(world, "getBlockAt", cx, cy, cz - 1);
        Reflect.call(cakeBlock, "setType", Reflect.enumValue("org.bukkit.Material", "CAKE"), false);

        Object amethystBlock = Reflect.call(world, "getBlockAt", cx + 1, cy, cz - 1);
        Reflect.call(amethystBlock, "setType", Reflect.enumValue("org.bukkit.Material", "AMETHYST_CLUSTER"), false);

        lastBaitBases.put(staffId, new BaitBaseSnapshot(worldName, spX, spY, spZ, snapshot));
        activeBaitBases.add(new BaitBaseZone(staffId, worldName, cx - 2, cy - 1, cz - 2, cx + 2, cy + 3, cz + 2));

        // Position staff safely inside room facing the 3 bait blocks
        try {
            Object safeLoc = Reflect.construct("org.bukkit.Location", world, cx + 0.5, (double) cy, cz + 0.8, 180.0f, 15.0f);
            Reflect.call(sender, "teleport", safeLoc);
        } catch (Exception ignored) { }

        send(sender, "§3§l[BAIT BASE] §aĐã tạo mini base bẫy cheater tại §f" + worldName + " " + cx + " " + cy + " " + cz + "§a!");
        send(sender, "§7Gồm: §d1x " + mob + " Spawner §7+ §f1x Bánh Kem §7+ §d1x Amethyst§7. Khi có người chạm vào, Staff sẽ nhận cảnh báo ngay!");
        send(sender, "§7Dùng §e/baitbase undo §7để xóa và trả địa hình về như cũ.");
    }

    private void onBaitBaseTrigger(Object event) {
        try {
            if (activeBaitBases.isEmpty()) return;
            Object player = Reflect.call(event, "getPlayer");
            if (player == null || staff(player)) return;
            Object block = Reflect.call(event, "getBlock");
            if (block == null) return;
            checkBaitBaseAlert(player, Reflect.call(block, "getLocation"), "đập block");
        } catch (Exception ignored) { }
    }

    private void onBaitBaseInteract(Object event) {
        try {
            if (activeBaitBases.isEmpty()) return;
            if (!String.valueOf(Reflect.call(event, "getAction")).contains("RIGHT_CLICK_BLOCK")) return;
            Object player = Reflect.call(event, "getPlayer");
            if (player == null || staff(player)) return;
            Object block = Reflect.call(event, "getClickedBlock");
            if (block == null) return;
            String type = String.valueOf(Reflect.call(block, "getType"));
            if (!Set.of("SPAWNER", "CAKE", "AMETHYST_CLUSTER").contains(type)) return;
            checkBaitBaseAlert(player, Reflect.call(block, "getLocation"), "tương tác " + type);
        } catch (Exception ignored) { }
    }

    private void checkBaitBaseAlert(Object player, Object loc, String action) {
        String w = String.valueOf(Reflect.call(Reflect.call(loc, "getWorld"), "getName"));
        int x = ((Number) Reflect.call(loc, "getBlockX")).intValue();
        int y = ((Number) Reflect.call(loc, "getBlockY")).intValue();
        int z = ((Number) Reflect.call(loc, "getBlockZ")).intValue();
        boolean matched = false;
        for (BaitBaseZone zone : activeBaitBases) {
            if (zone.world().equalsIgnoreCase(w)
                    && x >= zone.minX() && x <= zone.maxX()
                    && y >= zone.minY() && y <= zone.maxY()
                    && z >= zone.minZ() && z <= zone.maxZ()) {
                matched = true;
                break;
            }
        }
        if (!matched) return;
        UUID pid = uuid(player);
        long now = System.currentTimeMillis();
        if (now - baitAlertCooldowns.getOrDefault(pid, 0L) < 5000L) return;
        baitAlertCooldowns.put(pid, now);
        String msg = "§c§l[BAIT BASE] §e" + name(player) + " §7vừa §f" + action + " §7trong base bẫy tại §f" + w + " " + x + " " + y + " " + z + " §8(/tp " + name(player) + ")";
        Object raw = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers");
        if (raw instanceof Collection<?> players) {
            for (Object p : players) {
                if (staff(p)) {
                    send(p, msg);
                    playUiSound(p, "entity.experience_orb.pickup");
                }
            }
        }
        getLogger().info("[BAIT BASE] " + name(player) + " " + action + " at " + w + " " + x + " " + y + " " + z);
    }

    // -------------------- Clean Beginner-Friendly /team & /tc System --------------------
    private void refreshPlayerTeamCache(UUID id) {
        try {
            String[] t = db.teamOfPlayer(id);
            playerTeamNames.put(id, t == null ? "" : t[1]);
        } catch (Exception ignored) { }
    }

    private void refreshTeamMembersCache(long teamId) {
        try {
            String[] t = db.teamById(teamId);
            String tName = t == null ? "" : t[1];
            for (String[] m : db.teamMembers(teamId)) {
                UUID mid = UUID.fromString(m[0]);
                playerTeamNames.put(mid, tName);
                Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", mid);
                if (online != null) updateScoreboard(online);
            }
        } catch (Exception ignored) { }
    }

    public boolean isTeamChatToggled(UUID id) { return teamChatToggled.contains(id); }

    public String pendingTeamInviteLabel(UUID id) {
        TeamInvite inv = teamInvites.get(id);
        if (inv == null) return null;
        if (inv.expiresAt() < System.currentTimeMillis()) {
            teamInvites.remove(id, inv);
            return null;
        }
        return inv.teamName() + " §7(từ §f" + inv.inviterName() + "§7)";
    }

    public void beginTeamCreateDraft(Object player) {
        UUID id = uuid(player);
        teamCreateDrafts.add(id);
        send(player, "§a§l[TEAM] §fNhập §etên Team §fbạn muốn tạo vào khung chat §7(2–16 ký tự, chữ/số/_).");
        send(player, "§7Gõ §cHỦY §7để thoát.");
    }

    private void completeTeamCreateDraft(Object player, String input) {
        UUID id = uuid(player);
        teamCreateDrafts.remove(id);
        if (!Boolean.TRUE.equals(Reflect.call(player, "isOnline"))) return;
        if (input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("hủy") || input.equalsIgnoreCase("cancel")) {
            send(player, "§eĐã hủy tạo team.");
            return;
        }
        String teamName = input.split("\s+")[0];
        if (createTeamInternal(player, teamName)) {
            gui.openTeamMenu(player);
        }
    }

    private boolean createTeamInternal(Object player, String rawName) {
        try {
            UUID id = uuid(player);
            if (db.playerTeamId(id) > 0) { send(player, "§cBạn đã ở trong một team rồi."); return false; }
            String name = rawName == null ? "" : rawName.trim();
            if (!name.matches("[A-Za-z0-9_]{2,16}")) {
                send(player, "§cTên team phải dài 2–16 ký tự và chỉ gồm chữ, số, dấu gạch dưới (_).");
                return false;
            }
            String tag = name.length() <= 4 ? name.toUpperCase(Locale.ROOT) : name.substring(0, 4).toUpperCase(Locale.ROOT);
            long tid = db.createTeam(name, tag, id);
            if (tid == -2L) { send(player, "§cTên team §f" + name + " §cđã tồn tại. Hãy chọn tên khác!"); return false; }
            if (tid <= 0L) { send(player, "§cKhông thể tạo team. Hãy thử lại."); return false; }
            playerTeamNames.put(id, name);
            updateScoreboard(player);
            playUiSound(player, "entity.experience_orb.pickup");
            send(player, "§a§l[TEAM] §aĐã tạo team §b" + name + " §athành công! Gõ §e/team §ađể mở bảng quản lý & mời bạn bè.");
            return true;
        } catch (Exception e) {
            send(player, "§cLỗi tạo team: " + e.getMessage());
            return false;
        }
    }

    public void teamInviteTarget(Object inviter, Object target) {
        try {
            UUID id = uuid(inviter), tid = uuid(target);
            if (id.equals(tid)) { send(inviter, "§cKhông thể tự mời bản thân."); return; }
            String[] team = db.teamOfPlayer(id);
            if (team == null) { send(inviter, "§cBạn chưa có team."); return; }
            String role = db.teamMemberRole(id);
            if (!"OWNER".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) { send(inviter, "§cChỉ Owner hoặc Admin team được mời."); return; }
            long teamId = Long.parseLong(team[0]);
            int maxMembers = Math.max(2, Math.min(50, cfgInt("teams.max-members", 10)));
            if (db.teamMemberCount(teamId) >= maxMembers) { send(inviter, "§cTeam đã đầy (§f" + maxMembers + " §cthành viên)."); return; }
            if (db.playerTeamId(tid) > 0) { send(inviter, "§c" + name(target) + " đã ở trong một team khác."); return; }
            TeamInvite invite = new TeamInvite(teamId, team[1], id, name(inviter), System.currentTimeMillis() + 60_000L);
            teamInvites.put(tid, invite);
            send(inviter, "§aĐã gửi lời mời vào team §b" + team[1] + " §acho §f" + name(target) + "§a.");
            send(target, "§b§l[TEAM] §f" + name(inviter) + " §7mời bạn vào team §b" + team[1] + "§7! Gõ §a/team accept §7hoặc mở §e/team §7để vào.");
            playUiSound(target, "entity.experience_orb.pickup");
        } catch (Exception e) { send(inviter, "§cKhông thể gửi lời mời."); }
    }

    public void teamAcceptInvite(Object player) {
        try {
            UUID id = uuid(player);
            if (db.playerTeamId(id) > 0) { send(player, "§cBạn đã có team rồi."); return; }
            TeamInvite inv = teamInvites.remove(id);
            if (inv == null || inv.expiresAt() < System.currentTimeMillis()) { send(player, "§cBạn không có lời mời vào team nào (hoặc đã hết hạn)."); return; }
            String[] team = db.teamById(inv.teamId());
            if (team == null) { send(player, "§cTeam này không còn tồn tại."); return; }
            int maxMembers = Math.max(2, Math.min(50, cfgInt("teams.max-members", 10)));
            if (db.teamMemberCount(inv.teamId()) >= maxMembers) { send(player, "§cTeam đã đạt số thành viên tối đa."); return; }
            if (!db.addTeamMember(inv.teamId(), id)) { send(player, "§cKhông thể tham gia team."); return; }
            playerTeamNames.put(id, team[1]);
            updateScoreboard(player);
            notifyTeam(inv.teamId(), "§b§l[TEAM] §a" + name(player) + " §7đã gia nhập team!");
            playUiSound(player, "entity.experience_orb.pickup");
        } catch (Exception e) { send(player, "§cKhông thể chấp nhận lời mời."); }
    }

    public void teamDenyInvite(Object player) {
        TeamInvite inv = teamInvites.remove(uuid(player));
        if (inv == null) { send(player, "§cBạn không có lời mời nào."); return; }
        send(player, "§eĐã từ chối lời mời vào team §b" + inv.teamName() + "§e.");
    }

    public void teamLeave(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) { send(player, "§cBạn chưa ở trong team nào."); return; }
            if ("OWNER".equalsIgnoreCase(db.teamMemberRole(id))) {
                send(player, "§cBạn là Chủ team! Dùng §e/team disband §choặc Shift+Click nút TNT trong §e/team §cđể giải tán.");
                return;
            }
            long teamId = Long.parseLong(team[0]);
            db.removeTeamMember(teamId, id);
            teamChatToggled.remove(id);
            playerTeamNames.put(id, "");
            updateScoreboard(player);
            send(player, "§eBạn đã rời khỏi team §b" + team[1] + "§e.");
            notifyTeam(teamId, "§b§l[TEAM] §e" + name(player) + " §7đã rời khỏi team.");
        } catch (Exception e) { send(player, "§cKhông thể rời team."); }
    }

    public void teamDisband(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) { send(player, "§cBạn chưa ở trong team nào."); return; }
            if (!"OWNER".equalsIgnoreCase(db.teamMemberRole(id))) { send(player, "§cChỉ Chủ team mới được giải tán team."); return; }
            long teamId = Long.parseLong(team[0]);
            List<String[]> members = db.teamMembers(teamId);
            notifyTeam(teamId, "§c§l[TEAM] §7Team §b" + team[1] + " §7đã bị giải tán bởi §f" + name(player) + "§7.");
            db.disbandTeam(teamId);
            for (String[] m : members) {
                UUID mid = UUID.fromString(m[0]);
                teamChatToggled.remove(mid);
                playerTeamNames.put(mid, "");
                Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", mid);
                if (online != null) updateScoreboard(online);
            }
        } catch (Exception e) { send(player, "§cKhông thể giải tán team."); }
    }

    public void teamKickByUuid(Object actor, UUID targetId, String targetName) {
        try {
            UUID id = uuid(actor);
            String[] team = db.teamOfPlayer(id);
            if (team == null) return;
            long teamId = Long.parseLong(team[0]);
            String myRole = db.teamMemberRole(id);
            String targetRole = db.teamMemberRole(targetId);
            if (targetRole == null || db.playerTeamId(targetId) != teamId) { send(actor, "§cNgười chơi không ở trong team của bạn."); return; }
            boolean canKick = "OWNER".equalsIgnoreCase(myRole) || ("ADMIN".equalsIgnoreCase(myRole) && "MEMBER".equalsIgnoreCase(targetRole));
            if (!canKick) { send(actor, "§cBạn không có quyền kick người này."); return; }
            db.removeTeamMember(teamId, targetId);
            teamChatToggled.remove(targetId);
            playerTeamNames.put(targetId, "");
            Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", targetId);
            if (online != null) {
                updateScoreboard(online);
                send(online, "§cBạn đã bị kick khỏi team §b" + team[1] + "§c.");
            }
            notifyTeam(teamId, "§b§l[TEAM] §c" + targetName + " §7đã bị kick khỏi team.");
        } catch (Exception e) { send(actor, "§cKhông thể kick thành viên."); }
    }

    public void teamToggleMemberRole(Object actor, UUID targetId, String targetName) {
        try {
            UUID id = uuid(actor);
            String[] team = db.teamOfPlayer(id);
            if (team == null || !"OWNER".equalsIgnoreCase(db.teamMemberRole(id))) {
                send(actor, "§cChỉ Chủ team mới được thăng/hạ chức thành viên.");
                return;
            }
            long teamId = Long.parseLong(team[0]);
            if (db.playerTeamId(targetId) != teamId) return;
            String cur = db.teamMemberRole(targetId);
            if ("OWNER".equalsIgnoreCase(cur)) return;
            String next = "ADMIN".equalsIgnoreCase(cur) ? "MEMBER" : "ADMIN";
            db.setTeamMemberRole(teamId, targetId, next);
            notifyTeam(teamId, "§b§l[TEAM] §f" + targetName + " §7giờ là " + ("ADMIN".equals(next) ? "§bAdmin" : "§7Member") + " §7của team.");
        } catch (Exception e) { send(actor, "§cKhông thể đổi vai trò."); }
    }

    public void teamSetHome(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) { send(player, "§cBạn chưa có team."); return; }
            String role = db.teamMemberRole(id);
            if (!"OWNER".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) { send(player, "§cChỉ Owner hoặc Admin team được đặt Team Home."); return; }
            Object loc = Reflect.call(player, "getLocation");
            Object world = Reflect.call(loc, "getWorld");
            long teamId = Long.parseLong(team[0]);
            db.setTeamHome(teamId, String.valueOf(Reflect.call(world, "getName")),
                    number(Reflect.call(loc, "getX")), number(Reflect.call(loc, "getY")), number(Reflect.call(loc, "getZ")),
                    ((Number) Reflect.call(loc, "getYaw")).floatValue(), ((Number) Reflect.call(loc, "getPitch")).floatValue());
            notifyTeam(teamId, "§b§l[TEAM] §a" + name(player) + " §7đã cập nhật §6Team Home§7!");
        } catch (Exception e) { send(player, "§cKhông thể đặt Team Home."); }
    }

    public void teamTeleportHome(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) { send(player, "§cBạn chưa có team."); return; }
            if (team[4] == null || team[4].isBlank()) { send(player, "§cTeam chưa đặt Team Home. Owner/Admin dùng §e/team sethome§c."); return; }
            teleport(player, team[4], new double[]{
                    Double.parseDouble(team[5]), Double.parseDouble(team[6]), Double.parseDouble(team[7]),
                    Double.parseDouble(team[8]), Double.parseDouble(team[9])
            });
            send(player, "§aĐã dịch chuyển về §6Team Home§a!");
            playUiSound(player, "entity.enderman.teleport");
        } catch (Exception e) { send(player, "§cKhông thể dịch chuyển về Team Home."); }
    }

    public void teamTogglePvp(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) { send(player, "§cBạn chưa có team."); return; }
            String role = db.teamMemberRole(id);
            if (!"OWNER".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) { send(player, "§cChỉ Owner hoặc Admin team được chỉnh Team PvP."); return; }
            long teamId = Long.parseLong(team[0]);
            boolean next = !"1".equals(team[10]);
            db.setTeamPvp(teamId, next);
            notifyTeam(teamId, "§b§l[TEAM] §7Team PvP (đánh nhau trong team) đã chuyển sang: " + (next ? "§c§lBẬT" : "§a§lTẮT"));
        } catch (Exception e) { send(player, "§cKhông thể đổi cài đặt PvP."); }
    }

    public void teamToggleChat(Object player) {
        try {
            UUID id = uuid(player);
            if (db.playerTeamId(id) <= 0) { teamChatToggled.remove(id); send(player, "§cBạn chưa ở trong team nào."); return; }
            if (teamChatToggled.add(id)) send(player, "§b§l[TEAM] §aĐã BẬT chế độ Team Chat. Mọi tin nhắn bạn gõ sẽ chỉ gửi cho team.");
            else send(player, "§b§l[TEAM] §eĐã TẮT chế độ Team Chat (quay lại chat toàn server).");
        } catch (Exception ignored) { }
    }

    private void sendTeamChatMessage(Object player, String message) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) {
                teamChatToggled.remove(id);
                send(player, "§cBạn chưa có team.");
                return;
            }
            long teamId = Long.parseLong(team[0]);
            String formatted = "§b§l[" + team[2] + "] §f" + name(player) + " §8» §f" + message;
            notifyTeam(teamId, formatted);
        } catch (Exception ignored) { }
    }

    private void notifyTeam(long teamId, String message) {
        try {
            for (String[] m : db.teamMembers(teamId)) {
                Object online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", UUID.fromString(m[0]));
                if (online != null) send(online, message);
            }
        } catch (Exception ignored) { }
    }

    private void onTeamFriendlyFire(Object event) {
        try {
            if (Boolean.TRUE.equals(Reflect.call(event, "isCancelled"))) return;
            Object victim = Reflect.call(event, "getEntity");
            Object damager = Reflect.call(event, "getDamager");
            if (!isPlayer(damager)) {
                try { Object shooter = Reflect.call(damager, "getShooter"); if (isPlayer(shooter)) damager = shooter; } catch (Exception ignored) { }
            }
            if (!isPlayer(victim) || !isPlayer(damager)) return;
            UUID vid = uuid(victim), did = uuid(damager);
            if (vid.equals(did)) return;
            long vTeam = db.playerTeamId(vid);
            if (vTeam <= 0 || vTeam != db.playerTeamId(did)) return;
            String[] team = db.teamById(vTeam);
            if (team != null && !"1".equals(team[10])) {
                Reflect.call(event, "setCancelled", true);
            }
        } catch (Exception ignored) { }
    }

    private void teamChatCommand(CommandSender sender, String[] args) {
        requirePlayer(sender);
        if (args.length == 0) { teamToggleChat(sender); return; }
        sendTeamChatMessage(sender, String.join(" ", args));
    }

    private void teamCommand(CommandSender sender, String[] args) throws Exception {
        requirePlayer(sender);
        if (args.length == 0) { gui.openTeamMenu(sender); return; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui", "menu" -> gui.openTeamMenu(sender);
            case "create" -> {
                if (args.length < 2) beginTeamCreateDraft(sender);
                else if (createTeamInternal(sender, args[1])) gui.openTeamMenu(sender);
            }
            case "invite" -> {
                if (args.length < 2) { gui.openTeamInviteGui(sender); return; }
                Object target = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[1]);
                if (target == null) { send(sender, "§cNgười chơi không online."); return; }
                teamInviteTarget(sender, target);
            }
            case "accept", "join" -> teamAcceptInvite(sender);
            case "deny", "reject" -> teamDenyInvite(sender);
            case "leave" -> teamLeave(sender);
            case "disband", "delete" -> teamDisband(sender);
            case "sethome" -> teamSetHome(sender);
            case "home" -> teamTeleportHome(sender);
            case "pvp" -> teamTogglePvp(sender);
            case "list" -> gui.openTeamList(sender);
            case "members" -> gui.openTeamMembers(sender);
            case "chat", "c" -> {
                if (args.length < 2) teamToggleChat(sender);
                else sendTeamChatMessage(sender, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "kick" -> {
                if (args.length < 2) { send(sender, "§e/team kick <player>"); return; }
                String[] found = db.findUserByName(args[1]);
                if (found == null) { send(sender, "§cKhông tìm thấy người chơi này."); return; }
                teamKickByUuid(sender, UUID.fromString(found[0]), found[1]);
            }
            case "promote", "demote" -> {
                if (args.length < 2) { send(sender, "§e/team " + sub + " <player>"); return; }
                String[] found = db.findUserByName(args[1]);
                if (found == null) { send(sender, "§cKhông tìm thấy người chơi này."); return; }
                teamToggleMemberRole(sender, UUID.fromString(found[0]), found[1]);
            }
            case "info" -> {
                String[] team = args.length >= 2 ? db.teamByName(args[1]) : db.teamOfPlayer(uuid(sender));
                if (team == null) { send(sender, "§cKhông tìm thấy team."); return; }
                long teamId = Long.parseLong(team[0]);
                List<String[]> members = db.teamMembers(teamId);
                List<String> names = new ArrayList<>();
                for (String[] m : members) names.add(("OWNER".equals(m[2]) ? "§6★" : "ADMIN".equals(m[2]) ? "§b★" : "§7") + m[1]);
                send(sender, "§b§lTEAM " + team[1] + " §7[" + team[2] + "] §8| §7PvP: " + ("1".equals(team[10]) ? "§cBẬT" : "§aTẮT"));
                send(sender, "§7Thành viên (" + members.size() + "): " + String.join("§7, ", names));
            }
            default -> {
                send(sender, "§b§lHAVEN TEAM §7— Gõ §e/team §7để mở GUI nhanh!");
                send(sender, "§e/team create <tên> §7| §e/team invite <player> §7| §e/team accept §7| §e/team leave");
                send(sender, "§e/team home §7| §e/team sethome §7| §e/team pvp §7| §e/tc <tin nhắn> §7| §e/team list");
            }
        }
    }

    private void updateAllScoreboards() {
        try { if(life.tick()) { awardLifeStealRewards(); broadcast("§cLifeSteal Event đã tự động kết thúc. SMP trở về bình thường."); } } catch(Exception ignored) {}
        try { if(!cfgBoolean("scoreboard.enabled",true)) return;
            Collection<?> players=(Collection<?>)Reflect.callStatic("org.bukkit.Bukkit","getOnlinePlayers");
            Object manager=Reflect.callStatic("org.bukkit.Bukkit","getScoreboardManager");
            if(manager!=null) syncNameTagTeams(Reflect.call(manager,"getMainScoreboard"));
            for(Object player:players){if(!auth.isAuthenticated(uuid(player)))continue;updateScoreboard(player);}
        } catch(Exception e){getLogger().fine("Scoreboard update skipped: "+e.getMessage());}
    }
    private void updateScoreboard(Object player) {
        try {
            UUID id=uuid(player);
            if (!isPlayerSettingEnabled(id, "scoreboard")) {
                hideScoreboard(player);
                return;
            }
            Object board=scoreboards.get(id),objective=objectives.get(id);
            if(board==null||objective==null){Object manager=Reflect.callStatic("org.bukkit.Bukkit","getScoreboardManager");board=Reflect.call(manager,"getNewScoreboard");objective=Reflect.call(board,"registerNewObjective","ltzcore","dummy","§6§lHAVEN SMP");Reflect.call(objective,"setDisplaySlot",Reflect.enumValue("org.bukkit.scoreboard.DisplaySlot","SIDEBAR"));scoreboards.put(id,board);objectives.put(id,objective);Reflect.call(player,"setScoreboard",board);}
            String rank = db.rank(id);
            long shardBal = db.shardBalance(id);
            boolean shardEligible = cfgBoolean("shards.enabled", true) && Set.of("+", "++", "+++").contains(rank);
            String shardLine;
            if (shardEligible) {
                long now = System.currentTimeMillis();
                Long since = shardOnlineSince.putIfAbsent(id, now);
                if (since == null) since = now;
                long next = vn.ltzcore.util.ShardCountdown.secondsUntilNext(since, now);
                shardLine = "§d✦ §7Shards: §d" + MoneyFormat.compact(shardBal) + " §8(§e" + vn.ltzcore.util.ShardCountdown.format(next) + "§8)";
            } else {
                shardLine = "§d✦ §7Shards: §d" + MoneyFormat.compact(shardBal);
            }
            int online = ((Collection<?>)Reflect.callStatic("org.bukkit.Bukkit","getOnlinePlayers")).size();
            String worldName = String.valueOf(Reflect.call(Reflect.call(Reflect.call(player,"getLocation"),"getWorld"),"getName"));

            List<String> lines=new ArrayList<>();
            lines.add("§8 ");
            lines.add("§6▪ §7Player: §f"+name(player));
            lines.add("§6▪ §7Rank: "+db.rankPrefix(rank));
            lines.add("§6▪ §7Team: "+scoreboardTeamName(player));
            lines.add("§8  ");
            lines.add("§a$ §7Money: §a$"+MoneyFormat.compact(economy.balance(id)));
            lines.add(shardLine);
            lines.add("§b▪ §7Playtime: §f"+formatPlaytime(totalPlaytimeSeconds(id)));
            lines.add("§8   ");
            lines.add("§e▪ §7Online: §f"+online);
            lines.add("§e▪ §7World: §f"+worldName);
            if(life.enabled()) {
                lines.add("§c❤ §7Hearts: §c"+life.hearts(id)+"❤");
                long remaining=life.remainingSeconds();
                lines.add("§c⚔ §7Event: §aON §f"+(remaining>0?formatDuration(remaining):"∞"));
            } else {
                lines.add("§c❤ §7LifeSteal: §cOFF");
            }
            lines.add("§8Haven SMP • Survival");

            for(String old:scoreEntries.getOrDefault(id,List.of()))Reflect.call(board,"resetScores",old);
            int score=lines.size();for(String line:lines)Reflect.call(Reflect.call(objective,"getScore",line),"setScore",score--);
            scoreEntries.put(id,lines);
            syncNameTagTeams(board);
            String prefix=rankPrefix(rank);Reflect.call(player,"setPlayerListName",prefix+name(player));
            try{Reflect.call(player,"setPlayerListHeaderFooter","§6§lHAVEN SMP\n§7Chào mừng bạn!","§eOnline: §f"+online+"\n§7Dùng /menu");}catch(Exception ignored){}
        } catch(Exception e){getLogger().fine("Could not refresh scoreboard for a player: "+e.getMessage());}
    }
    private String formatDuration(long seconds){long m=seconds/60,s=seconds%60;return m+":"+String.format(Locale.ROOT,"%02d",s);}
    private String rankPrefix(String rank){try{return db.rankPrefix(rank)+" ";}catch(Exception e){return "§7[Member] ";}}

    private String cfgString(String path,String fallback){try{Object c=Reflect.call(this,"getConfig");Object v=Reflect.call(c,"getString",path,fallback);return v==null?fallback:String.valueOf(v);}catch(Exception e){return fallback;}}
    private boolean cfgBoolean(String path,boolean fallback){try{Object cfg=Reflect.call(this,"getConfig");return Boolean.TRUE.equals(Reflect.call(cfg,"getBoolean",path,fallback));}catch(Exception e){return fallback;}}
    private long cfgLong(String path,long fallback){try{Object cfg=Reflect.call(this,"getConfig");Object v=Reflect.call(cfg,"getLong",path,fallback);return ((Number)v).longValue();}catch(Exception e){return fallback;}}
    private int cfgInt(String path,int fallback){try{Object cfg=Reflect.call(this,"getConfig");Object v=Reflect.call(cfg,"getInt",path,fallback);return ((Number)v).intValue();}catch(Exception e){return fallback;}}
    private boolean lifeAdmin(Object sender){return admin(sender);}
    private boolean staff(Object sender){
        if(!isPlayer(sender))return true;
        try { if(!auth.isAuthenticated(uuid(sender)))return false; return db.staffLevel(db.rank(uuid(sender)))>=1; }
        catch(Exception e){return false;}
    }
    private boolean admin(Object sender){
        if(!isPlayer(sender))return true;
        try { if(!auth.isAuthenticated(uuid(sender)))return false; return db.staffLevel(db.rank(uuid(sender)))>=2; }
        catch(Exception e){return false;}
    }
    private void refreshStaffPermissions(Object player){
        if(!isPlayer(player))return;
        UUID id=uuid(player);
        removeStaffPermissions(player);
        if(!staff(player))return;
        try {
            Object attachment=Reflect.call(player,"addAttachment",this);
            Reflect.call(attachment,"setPermission","ltzcore.staff",true);
            if(admin(player)) {
                Reflect.call(attachment,"setPermission","ltzcore.admin",true);
                Reflect.call(attachment,"setPermission","ltzcore.lifesteal.admin",true);
            }
            Reflect.call(player,"recalculatePermissions");
            staffAttachments.put(id,attachment);
        } catch(Exception e){getLogger().fine("Could not attach staff permissions: "+e.getMessage());}
    }
    private void removeStaffPermissions(Object player){
        if(!isPlayer(player))return;
        UUID id=uuid(player);Object attachment=staffAttachments.remove(id);
        if(attachment!=null){try{Reflect.call(attachment,"remove");Reflect.call(player,"recalculatePermissions");}catch(Exception ignored){}}
    }
    private boolean owner(Object sender){
        if(!isPlayer(sender))return true;
        try { return auth.isAuthenticated(uuid(sender)) && db.staffLevel(db.rank(uuid(sender)))>=3; }
        catch(Exception e){return false;}
    }
    private String generateOwnerPassword(){
        final String alphabet="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom random=new java.security.SecureRandom();
        StringBuilder out=new StringBuilder(24);
        for(int i=0;i<24;i++)out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return out.toString();
    }
    private String ownerName(){try{Object c=Reflect.call(this,"getConfig");String n=String.valueOf(Reflect.call(c,"getString","security.owner-name","Ltzpaul_"));return n.isBlank()?"Ltzpaul_":n;}catch(Exception e){return "Ltzpaul_";}}
    private boolean isOwnerName(String playerName){return playerName!=null && playerName.equalsIgnoreCase(ownerName());}
    private int parseInt(String value,int fallback){try{return Integer.parseInt(value);}catch(Exception e){return fallback;}}
    private String colorize(String value){return value.replace('&','§');}
    public boolean isPlayerAuthenticated(UUID id) { return auth != null && auth.isAuthenticated(id); }

    private boolean isPlayer(Object sender){try{return Reflect.type("org.bukkit.entity.Player").isInstance(sender);}catch(Exception e){return false;}}
    private void requirePlayer(Object sender){if(!isPlayer(sender))throw new IllegalArgumentException("Chỉ người chơi mới dùng được lệnh này.");}
    private UUID uuid(Object player){return (UUID)Reflect.call(player,"getUniqueId");}
    private String name(Object player){return String.valueOf(Reflect.call(player,"getName"));}
    private void send(Object sender,String message){try{Reflect.call(sender,"sendMessage",message);}catch(Exception ignored){}}
    private void broadcast(String message){Reflect.callStatic("org.bukkit.Bukkit","broadcastMessage",message);}
    private long parseLong(String value,long fallback){try{return Long.parseLong(value);}catch(Exception e){return fallback;}}
    private long parseMoney(String value){return MoneyFormat.parse(value);}
    private double number(Object value){return value instanceof Number n?n.doubleValue():0.0;}
}
