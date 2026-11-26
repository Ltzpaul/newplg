package vn.ltzcore.gui;

import vn.ltzcore.core.Reflect;
import vn.ltzcore.economy.EconomyManager;
import vn.ltzcore.lifesteal.LifeStealManager;
import vn.ltzcore.storage.Database;
import vn.ltzcore.util.MoneyFormat;
import vn.ltzcore.HavenExpansion;

import java.lang.reflect.Array;
import java.util.*;

/** Lightweight inventory menus. A single click listener handles all LTZCore menus. */
public final class GuiManager {
    public static final String MAIN = "§8Haven SMP • Menu";
    public static final String UTILITY = "§8Haven SMP • Tiện ích";
    public static final String LIFE = "§8LifeSteal Event";
    public static final String SHOP = "§8SHOP";
    public static final String SELL = "§8Haven SMP • Sell";
    public static final String CRATES = "§8Haven SMP • Crates";
    public static final String RANKS = "§8Haven SMP • Ranks";
    public static final String ADMIN = "§cHaven SMP • Admin";
    public static final String HEART_SHOP = "§8Haven SMP • Heart Shop";
    public static final String CRATE_PREVIEW = "§8Crate • Preview";
    public static final String CRATE_EDITOR_PREFIX = "§8Crate Editor: ";
    private static final String CRATE_ANIMATION = "§8Haven Crate • Opening";
    private static final String PROFILE = "§8Haven SMP • Hồ sơ";
    public static final String HOME_SELECT = "§8Haven SMP • Homes";
    public static final String HOME_SET = "§8Haven SMP • Đặt Home";
    public static final String SHARD_TOP = "§8Haven SMP • Top Shards";
    public static final String SHOP_BUY = "§8SHOP • Buy";
    public static final String SHOP_SHARD = "§8SHOP • Shards";
    public static final String SETTINGS = "§8Haven SMP • Settings";
    public static final String ENDER_CHEST_PREFIX = "§8Ender Chest • ";
    public static final String STATS_PREFIX = "§8STATS • ";
    public static final String TEAM_EMPTY = "§8TEAM • Bắt đầu";
    public static final String TEAM_MEMBERS = "§8TEAM • Thành viên";
    public static final String TEAM_INVITE = "§8TEAM • Mời người chơi";
    public static final String TEAM_LIST = "§8TEAM • Danh sách";
    public static final String TEAM_MAIN_PREFIX = "§8TEAM • ";
    private static final String EC_LOCKED_MARKER = "§0HAVEN_EC_LOCKED";
    private static final List<Integer> SHOP_ITEMS = List.of(9, 10, 11, 12, 13, 14, 15, 16, 17);
    private record ShopSelection(String category, String itemKey, String displayName, long unitPrice, int quantity) {}
    private final Object plugin;
    private final Database db;
    private final EconomyManager economy;
    private final LifeStealManager life;
    private final Runnable eventEndCallback;
    private final Map<UUID, String> crateSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, List<String>> homeMenuNames = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Boolean> homeSetMode = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, ShopSelection> shopSelections = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Object> activeEnderChests = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, UUID> enderChestViewers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Integer> enderChestUnlockedSlots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, List<String[]>> teamMemberCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> teamInviteCandidates = new java.util.concurrent.ConcurrentHashMap<>();
    private HavenExpansion expansion;
    public void setExpansion(HavenExpansion expansion) { this.expansion = expansion; }

    public GuiManager(Object plugin, Database db, EconomyManager economy, LifeStealManager life, Runnable eventEndCallback) {
        this.plugin = plugin; this.db = db; this.economy = economy; this.life = life; this.eventEndCallback=eventEndCallback;
    }

    public void openMain(Object player) {
        Object inv = inventory(MAIN, 27); fill(inv, 27);
        // The entire middle row (slots 9-17) is occupied, keeping the menu visually centred.
        item(inv, 9, "PLAYER_HEAD", "§b§lHồ sơ của bạn", List.of("§7Rank: " + safePrefix(player), "§7Số dư: §6$" + MoneyFormat.compact(safeBalance(player)), "§7Shard: §d" + MoneyFormat.compact(shardBalance(player)), "§eNhấn để xem chi tiết"));
        item(inv, 10, "EMERALD", "§a§lHaven Market", List.of("§7Shop tài nguyên, đồ End, Survival Gear và spawner", "§eNhấn để mở"));
        item(inv, 11, "HOPPER", "§6§lBán vật phẩm", List.of("§7Mở khung bán và chọn đồ muốn bán", "§7Dùng /sell để mở", "§eNhấn để mở"));
        item(inv, 12, "NAME_TAG", "§6§lRanks", List.of("§7Member • + • ++ • +++", "§eNhấn để xem rank"));
        item(inv, 13, "CLOCK", "§b§lPlaytime", List.of("§7Thời gian đã chơi: §f" + currentPlaytime(player), "§eDùng /playtime để xem chi tiết"));
        item(inv, 14, "AMETHYST_SHARD", "§d§lShard Wallet", List.of("§7Số shard: §f" + MoneyFormat.compact(shardBalance(player)), "§71 shard/phút với rank + / ++ / +++", "§7Shard chỉ dùng cho các mục được hỗ trợ"));
        item(inv, 15, "PAPER", "§b§lOrders", List.of("§7Đơn mua vật phẩm của người chơi", "§eNhấn để xem"));
        item(inv, 16, "BOOK", "§6§lAuction House", List.of("§7Chợ mua bán người chơi", "§7Dùng /ah sell <giá> để đăng bán", "§eNhấn để mở"));
        item(inv, 17, "SPAWNER", "§d§lMob Spawners", List.of("§7Spawner stack + kho item/EXP ảo", "§7Giá: §d800 shard / spawner", "§eNhấn để mua"));
        item(inv, 22, "WHITE_BED", "§a§lTravel & More", List.of("§7LifeSteal • Homes • Redeem • Daily", "§eNhấn để mở tiện ích"));
        open(player, inv);
    }

    private long safeBalance(Object player) { try { return economy.balance(uuid(player)); } catch (Exception e) { return 0; } }

    private void openProfile(Object player) {
        Object inv = inventory(PROFILE, 27); fill(inv, 27);
        item(inv, 13, "PLAYER_HEAD", "§b" + String.valueOf(Reflect.call(player, "getName")), List.of("§7Rank: " + safePrefix(player), "§7Số dư: §6$" + MoneyFormat.compact(safeBalance(player)), "§7Shard: §d" + MoneyFormat.compact(shardBalance(player)), "§7UUID: §f" + uuid(player)));
        item(inv, 22, "ARROW", "§eQuay lại", List.of());
        item(inv, 26, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    /** Clean DonutSMP-style /stats [player] GUI for both self and other online/offline players. */
    public void openStats(Object viewer, UUID targetId, String targetName, boolean online, long playtimeSeconds, long[] stats) {
        Object inv = inventory(STATS_PREFIX + targetName, 27);
        String rankName = "Member";
        String prefix = "§7[Member]";
        long money = 0L, shards = 0L, bounty = 0L, streak = 0L;
        int hearts = 10;
        try {
            rankName = db.rank(targetId);
            prefix = db.rankPrefix(rankName);
            money = economy.balance(targetId);
            shards = db.shardBalance(targetId);
            bounty = db.bountyTotal(targetId);
            streak = db.dailyStreak(targetId);
            hearts = life.hearts(targetId);
        } catch (Exception ignored) { }

        long kills = stats.length > 0 ? stats[0] : 0L;
        long deaths = stats.length > 1 ? stats[1] : 0L;
        long mobs = stats.length > 2 ? stats[2] : 0L;
        long broken = stats.length > 3 ? stats[3] : 0L;
        long placed = stats.length > 4 ? stats[4] : 0L;
        String kdr = deaths == 0 ? String.valueOf(kills) : String.format(Locale.ROOT, "%.2f", (double) kills / (double) deaths);
        long hours = playtimeSeconds / 3600L;
        long mins = (playtimeSeconds % 3600L) / 60L;
        String pt = hours >= 24 ? (hours / 24) + "d " + (hours % 24) + "h " + mins + "m" : hours + "h " + mins + "m";

        item(inv, 4, "PLAYER_HEAD", prefix + " §f" + targetName, List.of(
                "§7Trạng thái: " + (online ? "§a● Online" : "§7● Offline"),
                "§7Rank: " + prefix,
                "§7Daily Streak: §e" + streak + " ngày" + (life.enabled() ? " §8| §c" + hearts + "❤" : "")));
        item(inv, 10, "GOLD_INGOT", "§a§lTiền & Truy Nã", List.of(
                "§7Số dư: §a$" + MoneyFormat.compact(money),
                "§7Bounty hiện tại: §6$" + MoneyFormat.compact(bounty)));
        item(inv, 11, "AMETHYST_SHARD", "§d§lShards", List.of(
                "§7Số dư Shard: §d" + MoneyFormat.compact(shards)));
        item(inv, 12, "CLOCK", "§b§lThời Gian Chơi", List.of(
                "§7Tổng Playtime: §f" + pt));
        item(inv, 14, "DIAMOND_SWORD", "§c§lChiến Đấu (PvP)", List.of(
                "§7Hạ gục (Kills): §a" + kills,
                "§7Tử vong (Deaths): §c" + deaths,
                "§7Tỉ lệ K/D: §e" + kdr));
        item(inv, 15, "ZOMBIE_HEAD", "§e§lQuái Đã Tiêu Diệt", List.of(
                "§7Mobs Killed: §f" + MoneyFormat.compact(mobs)));
        item(inv, 16, "DIAMOND_PICKAXE", "§b§lXây Dựng & Đào", List.of(
                "§7Block đã đào: §f" + MoneyFormat.compact(broken),
                "§7Block đã đặt: §f" + MoneyFormat.compact(placed)));
        item(inv, 22, "BARRIER", "§cĐóng", List.of());
        open(viewer, inv);
    }

    // -------------------- Clean Beginner-Friendly /team GUIs --------------------
    public void openTeamMenu(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) {
                openTeamEmpty(player);
                return;
            }
            long teamId = Long.parseLong(team[0]);
            String teamName = team[1];
            String tag = team[2];
            String ownerUuid = team[3];
            boolean hasHome = team[4] != null && !team[4].isBlank();
            boolean pvp = "1".equals(team[10]);
            int count = db.teamMemberCount(teamId);
            int maxMembers = Math.max(2, Math.min(50, configInt("teams.max-members", 10)));
            String role = db.teamMemberRole(id);
            boolean isOwner = "OWNER".equalsIgnoreCase(role);
            boolean isManager = isOwner || "ADMIN".equalsIgnoreCase(role);
            boolean teamChatOn = Boolean.TRUE.equals(Reflect.call(plugin, "isTeamChatToggled", id));
            String ownerName = db.userName(UUID.fromString(ownerUuid));
            if (ownerName == null) ownerName = "Unknown";

            Object inv = inventory(TEAM_MAIN_PREFIX + teamName, 27);
            item(inv, 4, "NETHER_STAR", "§b§lTEAM " + teamName + " §7[" + tag + "]", List.of(
                    "§7Chủ team: §f" + ownerName,
                    "§7Vai trò của bạn: " + formatTeamRole(role),
                    "§7Thành viên: §a" + count + "§7/§f" + maxMembers,
                    "§7Team PvP: " + (pvp ? "§cĐANG BẬT" : "§aTẮT (An toàn)")));
            item(inv, 10, "PLAYER_HEAD", "§e§lThành Viên §7(" + count + "/" + maxMembers + ")", List.of(
                    "§7Xem danh sách thành viên trong team",
                    isOwner ? "§aClick trái: §fThăng/Hạ chức §8| §cClick phải: §fKick" : "§eNhấn để xem"));
            item(inv, 11, "LIME_DYE", "§a§lMời Người Chơi", List.of(
                    "§7Chọn nhanh người đang online để mời",
                    "§7Hoặc dùng lệnh §f/team invite <tên>",
                    isManager ? "§eNhấn để mở danh sách mời" : "§cChỉ Owner/Admin team được mời"));
            item(inv, 12, "RED_BED", "§6§lTeam Home", List.of(
                    "§7Trạng thái: " + (hasHome ? "§aĐã đặt" : "§cChưa đặt"),
                    "§aClick trái: §fDịch chuyển về Team Home",
                    isManager ? "§eClick phải: §fĐặt Team Home tại đây" : "§7Owner/Admin có thể đặt home"));
            item(inv, 13, "WRITABLE_BOOK", "§b§lTeam Chat", List.of(
                    "§7Chế độ chat team: " + (teamChatOn ? "§a§lĐANG BẬT" : "§7ĐANG TẮT"),
                    "§7Hoặc gõ nhanh §f/tc <tin nhắn>",
                    "§eNhấn để Bật/Tắt"));
            item(inv, 14, "SHIELD", "§c§lTeam PvP (Đánh Nhau)", List.of(
                    "§7Trạng thái: " + (pvp ? "§c§lBẬT (Đánh được nhau)" : "§a§lTẮT (Không đánh nhầm)"),
                    isManager ? "§eNhấn để Bật/Tắt" : "§7Chỉ Owner/Admin team được chỉnh"));
            item(inv, 15, "BOOK", "§f§lDanh Sách Team", List.of(
                    "§7Xem các team đang hoạt động trên server",
                    "§eNhấn để xem"));
            item(inv, 16, isOwner ? "TNT" : "RED_CONCRETE",
                    isOwner ? "§4§lGiải Tán Team" : "§c§lRời Khỏi Team",
                    isOwner ? List.of("§7Xóa vĩnh viễn team này", "§cShift + Click để xác nhận giải tán")
                            : List.of("§7Rời khỏi team §f" + teamName, "§cClick để rời team"));
            item(inv, 22, "BARRIER", "§cĐóng", List.of());
            open(player, inv);
        } catch (Exception e) {
            tell(player, "§cKhông thể mở Team GUI: " + e.getMessage());
        }
    }

    private void openTeamEmpty(Object player) {
        UUID id = uuid(player);
        Object inv = inventory(TEAM_EMPTY, 27);
        String pendingInfo = null;
        try { pendingInfo = (String) Reflect.call(plugin, "pendingTeamInviteLabel", id); } catch (Exception ignored) { }

        item(inv, 11, "LIME_CONCRETE", "§a§lTạo Team Mới", List.of(
                "§7Cực dễ cho người mới!",
                "§7Nhấn vào đây rồi §egõ tên Team vào chat",
                "§7Hoặc dùng lệnh §f/team create <tên>",
                "§a▸ Nhấn để tạo ngay"));
        if (pendingInfo != null && !pendingInfo.isBlank()) {
            item(inv, 13, "ENCHANTED_BOOK", "§e§lLời Mời Vào Team", List.of(
                    "§7Bạn được mời vào: §b" + pendingInfo,
                    "§a▸ Click trái: §fChấp nhận vào team",
                    "§c▸ Click phải: §fTừ chối lời mời"));
        } else {
            item(inv, 13, "PAPER", "§7Lời Mời Vào Team", List.of(
                    "§7Hiện bạn chưa có lời mời nào.",
                    "§7Khi bạn bè mời, nhấn vào đây để vào ngay!"));
        }
        item(inv, 15, "BOOK", "§b§lDanh Sách Team", List.of(
                "§7Xem các team đang hoạt động trên server",
                "§e▸ Nhấn để xem"));
        item(inv, 22, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    public void openTeamMembers(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) { openTeamEmpty(player); return; }
            long teamId = Long.parseLong(team[0]);
            String myRole = db.teamMemberRole(id);
            boolean isOwner = "OWNER".equalsIgnoreCase(myRole);
            boolean isManager = isOwner || "ADMIN".equalsIgnoreCase(myRole);
            List<String[]> members = db.teamMembers(teamId);
            teamMemberCache.put(id, members);

            Object inv = inventory(TEAM_MEMBERS, 54);
            for (int i = 45; i < 54; i++) item(inv, i, "BLACK_STAINED_GLASS_PANE", "§r", List.of());
            for (int i = 0; i < members.size() && i < 45; i++) {
                String[] m = members.get(i);
                UUID mid = UUID.fromString(m[0]);
                boolean online = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", mid) != null;
                List<String> lore = new ArrayList<>();
                lore.add("§7Vai trò: " + formatTeamRole(m[2]));
                lore.add("§7Trạng thái: " + (online ? "§a● Online" : "§7● Offline"));
                if (!mid.equals(id)) {
                    if (isOwner) {
                        lore.add("§8──────────────");
                        lore.add("§aClick trái: §fĐổi Member ↔ Admin");
                        lore.add("§cClick phải: §fKick khỏi team");
                    } else if (isManager && "MEMBER".equalsIgnoreCase(m[2])) {
                        lore.add("§8──────────────");
                        lore.add("§cClick phải: §fKick khỏi team");
                    }
                }
                item(inv, i, "PLAYER_HEAD", (online ? "§a" : "§7") + m[1], lore);
            }
            item(inv, 45, "ARROW", "§eQuay lại Team", List.of());
            item(inv, 49, "PAPER", "§bTeam " + team[1] + " §7(" + members.size() + ")", List.of());
            item(inv, 53, "BARRIER", "§cĐóng", List.of());
            open(player, inv);
        } catch (Exception e) {
            tell(player, "§cKhông thể tải danh sách thành viên: " + e.getMessage());
        }
    }

    public void openTeamInviteGui(Object player) {
        try {
            UUID id = uuid(player);
            String[] team = db.teamOfPlayer(id);
            if (team == null) { openTeamEmpty(player); return; }
            String myRole = db.teamMemberRole(id);
            if (!"OWNER".equalsIgnoreCase(myRole) && !"ADMIN".equalsIgnoreCase(myRole)) {
                tell(player, "§cChỉ Owner hoặc Admin team mới được mời thành viên.");
                return;
            }
            List<UUID> candidates = new ArrayList<>();
            Object inv = inventory(TEAM_INVITE, 54);
            for (int i = 45; i < 54; i++) item(inv, i, "BLACK_STAINED_GLASS_PANE", "§r", List.of());
            Object raw = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers");
            int slot = 0;
            if (raw instanceof Collection<?> players) {
                for (Object other : players) {
                    if (slot >= 45) break;
                    UUID oid = uuid(other);
                    if (oid.equals(id)) continue;
                    if (!Boolean.TRUE.equals(Reflect.call(plugin, "isPlayerAuthenticated", oid))) continue;
                    if (db.playerTeamId(oid) > 0) continue;
                    String oName = String.valueOf(Reflect.call(other, "getName"));
                    candidates.add(oid);
                    item(inv, slot++, "PLAYER_HEAD", "§a" + oName, List.of(
                            "§7Chưa có team",
                            "§a▸ Click để gửi lời mời vào team §b" + team[1]));
                }
            }
            teamInviteCandidates.put(id, candidates);
            if (candidates.isEmpty()) {
                item(inv, 22, "PAPER", "§7Không có người chơi trống", List.of("§7Tất cả người chơi online khác đều đã có team."));
            }
            item(inv, 45, "ARROW", "§eQuay lại Team", List.of());
            item(inv, 49, "LIME_DYE", "§aMời Nhanh Người Online", List.of("§7Click vào đầu người chơi để mời"));
            item(inv, 53, "BARRIER", "§cĐóng", List.of());
            open(player, inv);
        } catch (Exception e) {
            tell(player, "§cKhông thể mở bảng mời người chơi: " + e.getMessage());
        }
    }

    public void openTeamList(Object player) {
        try {
            List<String[]> teams = db.listTeams(45);
            Object inv = inventory(TEAM_LIST, 54);
            for (int i = 45; i < 54; i++) item(inv, i, "BLACK_STAINED_GLASS_PANE", "§r", List.of());
            if (teams.isEmpty()) {
                item(inv, 22, "PAPER", "§7Chưa có team nào", List.of("§7Hãy là người đầu tiên tạo team bằng §f/team§7!"));
            } else {
                for (int i = 0; i < teams.size() && i < 45; i++) {
                    String[] t = teams.get(i);
                    item(inv, i, "SHIELD", "§b§l" + t[1] + " §7[" + t[2] + "]", List.of(
                            "§7Chủ team: §f" + t[3],
                            "§7Thành viên: §a" + t[4]));
                }
            }
            item(inv, 45, "ARROW", "§eQuay lại", List.of());
            item(inv, 49, "BOOK", "§b§lDanh Sách Team §7(" + teams.size() + ")", List.of());
            item(inv, 53, "BARRIER", "§cĐóng", List.of());
            open(player, inv);
        } catch (Exception e) {
            tell(player, "§cKhông thể tải danh sách team: " + e.getMessage());
        }
    }

    private String formatTeamRole(String role) {
        if ("OWNER".equalsIgnoreCase(role)) return "§6Owner";
        if ("ADMIN".equalsIgnoreCase(role)) return "§bAdmin";
        return "§7Member";
    }

    private void handleTeamGuiClick(Object event, Object player, String title, int slot) {
        try {
            UUID id = uuid(player);
            String clickType = String.valueOf(Reflect.call(event, "getClick"));
            boolean rightClick = clickType.contains("RIGHT");
            boolean shiftClick = clickType.contains("SHIFT");
            if (title.equals(TEAM_EMPTY)) {
                if (slot == 11) {
                    Reflect.call(player, "closeInventory");
                    Reflect.call(plugin, "beginTeamCreateDraft", player);
                } else if (slot == 13) {
                    if (rightClick) Reflect.call(plugin, "teamDenyInvite", player);
                    else Reflect.call(plugin, "teamAcceptInvite", player);
                    openTeamMenu(player);
                } else if (slot == 15) {
                    openTeamList(player);
                } else if (slot == 22) {
                    Reflect.call(player, "closeInventory");
                }
                return;
            }
            if (title.equals(TEAM_MEMBERS)) {
                if (slot == 45) { openTeamMenu(player); return; }
                if (slot == 53) { Reflect.call(player, "closeInventory"); return; }
                if (slot >= 0 && slot < 45) {
                    List<String[]> members = teamMemberCache.getOrDefault(id, List.of());
                    if (slot >= members.size()) return;
                    String[] m = members.get(slot);
                    UUID targetId = UUID.fromString(m[0]);
                    if (targetId.equals(id)) return;
                    if (rightClick) {
                        Reflect.call(plugin, "teamKickByUuid", player, targetId, m[1]);
                    } else {
                        Reflect.call(plugin, "teamToggleMemberRole", player, targetId, m[1]);
                    }
                    openTeamMembers(player);
                }
                return;
            }
            if (title.equals(TEAM_INVITE)) {
                if (slot == 45) { openTeamMenu(player); return; }
                if (slot == 53) { Reflect.call(player, "closeInventory"); return; }
                if (slot >= 0 && slot < 45) {
                    List<UUID> cands = teamInviteCandidates.getOrDefault(id, List.of());
                    if (slot >= cands.size()) return;
                    Object target = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", cands.get(slot));
                    if (target != null) Reflect.call(plugin, "teamInviteTarget", player, target);
                    openTeamInviteGui(player);
                }
                return;
            }
            if (title.equals(TEAM_LIST)) {
                if (slot == 45) openTeamMenu(player);
                else if (slot == 53) Reflect.call(player, "closeInventory");
                return;
            }
            if (title.startsWith(TEAM_MAIN_PREFIX)) {
                if (slot == 10) openTeamMembers(player);
                else if (slot == 11) openTeamInviteGui(player);
                else if (slot == 12) {
                    if (rightClick) {
                        Reflect.call(plugin, "teamSetHome", player);
                        openTeamMenu(player);
                    } else {
                        Reflect.call(player, "closeInventory");
                        Reflect.call(plugin, "teamTeleportHome", player);
                    }
                } else if (slot == 13) {
                    Reflect.call(plugin, "teamToggleChat", player);
                    openTeamMenu(player);
                } else if (slot == 14) {
                    Reflect.call(plugin, "teamTogglePvp", player);
                    openTeamMenu(player);
                } else if (slot == 15) {
                    openTeamList(player);
                } else if (slot == 16) {
                    String role = db.teamMemberRole(id);
                    if ("OWNER".equalsIgnoreCase(role)) {
                        if (!shiftClick) {
                            tell(player, "§cHãy giữ §eShift + Click §cvào nút TNT để xác nhận giải tán team!");
                        } else {
                            Reflect.call(plugin, "teamDisband", player);
                            openTeamMenu(player);
                        }
                    } else {
                        Reflect.call(plugin, "teamLeave", player);
                        openTeamMenu(player);
                    }
                } else if (slot == 22) {
                    Reflect.call(player, "closeInventory");
                }
            }
        } catch (Exception e) {
            tell(player, "§cLỗi xử lý Team GUI: " + e.getMessage());
        }
    }

    public void openUtility(Object player) {
        Object inv = inventory(UTILITY, 27); fill(inv, 27);
        item(inv, 10, "HEART_OF_THE_SEA", "§cLifeSteal Event", List.of("§7Trạng thái: " + (life.enabled() ? "§aĐang mở" : "§cĐang tắt"), "§eNhấn để xem event"));
        item(inv, 11, "ENDER_CHEST", "§5Ender Chest (54 Ô)", List.of("§7Rương Ender mở rộng kiểu DonutSMP", "§7Dùng /ec hoặc click rương Ender", "§eNhấn để mở"));
        item(inv, 12, "WRITABLE_BOOK", "§dRedeem Code", List.of("§7Nhập code bằng /redeem <mã>"));
        item(inv, 13, "COMPARATOR", "§bCài đặt (/setting)", List.of("§7Tắt quái spawn, TPA, Pay, Scoreboard, Chat...", "§eNhấn để mở"));
        item(inv, 14, "WHITE_BED", "§aHomes & Warps", List.of("§7/sethome • /home • /homes", "§7/warp <tên> • /spawn"));
        item(inv, 15, "SHIELD", "§bTeam (/team)", List.of("§7Tạo team, mời bạn bè, Team Home & Chat", "§eNhấn để mở"));
        item(inv, 16, "CHEST", "§eDaily & Playtime Rewards", List.of("§7Nhấn để nhận quà hằng ngày"));
        item(inv, 18, "CLOCK", "§bPlaytime Reward", List.of("§7Nhận thưởng mốc giờ chơi", "§eNhấn để mở /playreward"));
        item(inv, 20, "AMETHYST_SHARD", "§dShard Wallet", List.of("§7Số dư: §d" + MoneyFormat.compact(shardBalance(player))));
        item(inv, 22, "ARROW", "§eQuay lại Menu", List.of());
        item(inv, 26, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    /** Shard leaderboard is GUI-only so shard balances are not printed into chat. */
    public void openShardTop(Object player) {
        Object inv = inventory(SHARD_TOP, 27);
        fill(inv, 27);
        try {
            List<String[]> rows = db.shardTop(10);
            for (int i = 0; i < rows.size() && i < 10; i++) {
                String[] row = rows.get(i);
                String rank = switch (i) { case 0 -> "§6#1"; case 1 -> "§f#2"; case 2 -> "§c#3"; default -> "§e#" + (i + 1); };
                item(inv, 10 + i, i == 0 ? "AMETHYST_SHARD" : "PRISMARINE_SHARD",
                        rank + " §f" + row[2], List.of("§7Shard: §d" + MoneyFormat.compact(Long.parseLong(row[1]))));
            }
        } catch (Exception ignored) {
            item(inv, 13, "BARRIER", "§cKhông tải được bảng xếp hạng", List.of("§7Thử lại sau."));
        }
        item(inv, 22, "ARROW", "§eQuay lại", List.of());
        item(inv, 26, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    public void openLife(Object player) {
        Object inv = inventory(LIFE, 27); fill(inv, 27);
        UUID id = uuid(player);
        item(inv, 4, life.enabled() ? "LIME_DYE" : "GRAY_DYE", life.enabled() ? "§aEVENT ĐANG MỞ" : "§cEVENT ĐANG TẮT",
                List.of("§7Thời gian còn lại: §f" + life.remainingSeconds() + "s", "§7Online: §f" + onlineCount()));
        item(inv, 11, "REDSTONE", "§cTim của bạn", List.of("§7Số tim: §c" + life.hearts(id) + "❤", "§7Chỉ áp dụng khi event mở"));
        item(inv, 13, "GOLD_INGOT", "§6Top tim", topHeartLore());
        item(inv, 15, "BOOK", "§eLuật event", List.of("§7Hạ người chơi hợp lệ để lấy tim", "§7Không cố tình farm kill", "§7Tắt event sẽ trả về SMP bình thường"));
        item(inv, 22, "GOLDEN_APPLE", "§6Heart Shop", List.of("§7Mua thêm tim bằng tiền game", "§eNhấn để mở"));
        if (hasAdmin(player)) item(inv, 26, "COMMAND_BLOCK", "§cQuản lý event", List.of("§7Bật/tắt LifeSteal", "§eNhấn để chuyển trạng thái"));
        open(player, inv);
    }

    /** Classic DonutSMP /shop (27-slot clean empty-slot layout: End, Nether, Gear, Food, Shard Shop). */
    public void openShop(Object player) {
        Object inv = inventory(SHOP, 27);
        item(inv, 11, "END_STONE", "§5§lEND", List.of("§7Ender Chest, Ender Pearl, End Stone,", "§7Dragon's Breath, Chorus, Shulker...", "§eClick to open"));
        item(inv, 12, "NETHERRACK", "§c§lNETHER", List.of("§7Blaze Rod, Nether Wart, Glowstone,", "§7Ghast Tear, Quartz, Soul Sand...", "§eClick to open"));
        item(inv, 13, "TOTEM_OF_UNDYING", "§6§lGEAR", List.of("§7Obsidian, End Crystal, Respawn Anchor,", "§7Totem, Golden Apple, Slow Falling...", "§eClick to open"));
        item(inv, 14, "COOKED_BEEF", "§a§lFOOD", List.of("§7Potato, Sweet Berries, Carrot, Apple,", "§7Steak, Golden Carrot, Golden Apple...", "§eClick to open"));
        item(inv, 15, "AMETHYST_SHARD", "§d§lSHARD SHOP", List.of("§7Mob Spawners & Haven 3x3 Tools", "§7Shard: §d" + MoneyFormat.compact(shardBalance(player)), "§eClick to open"));
        open(player, inv);
    }

    /** KingSMP & DonutSMP-style /setting GUI (45 slots: icon row + ON/OFF dye toggle row). */
    public void openSettings(Object player) {
        UUID id = uuid(player);
        Object inv = inventory(SETTINGS, 45); fill(inv, 45);
        int radius = Math.max(16, Math.min(128, configInt("settings.mob-spawn-radius", 64)));
        item(inv, 4, "COMPARATOR", "§b§lCÀI ĐẶT CÁ NHÂN", List.of("§7Nhấn vào biểu tượng hoặc nút BẬT/TẮT bên dưới", "§7Mọi cài đặt được lưu tự động vào database"));

        boolean mobSpawn = settingEnabled(id, "mob_spawn");
        boolean tpa = settingEnabled(id, "tpa");
        boolean pay = settingEnabled(id, "pay");
        boolean sb = settingEnabled(id, "scoreboard");
        boolean chat = settingEnabled(id, "global_chat");
        boolean deathCoords = settingEnabled(id, "death_coords");
        boolean sounds = settingEnabled(id, "sounds");

        renderSettingPair(inv, 10, 19, "ZOMBIE_HEAD", "§cQuái Tự Nhiên Spawn", mobSpawn,
                List.of("§7Khi §cTẮT§7: quái thù địch không thể", "§7spawn tự nhiên quanh bạn (§f" + radius + " block§7).", "§7Không ảnh hưởng đến Mob Spawner."));
        renderSettingPair(inv, 11, 20, "ENDER_PEARL", "§bYêu Cầu TPA / TPAHere", tpa,
                List.of("§7Cho phép hoặc chặn người chơi khác", "§7gửi lời mời §f/tpa §7và §f/tpahere §7tới bạn."));
        renderSettingPair(inv, 12, 21, "GOLD_INGOT", "§6Nhận Tiền (/pay)", pay,
                List.of("§7Cho phép hoặc chặn người chơi khác", "§7chuyển tiền cho bạn qua lệnh §f/pay§7."));
        renderSettingPair(inv, 13, 22, "PAINTING", "§eHiển Thị Scoreboard", sb,
                List.of("§7Bật hoặc ẩn bảng thông tin", "§7ở bên phải màn hình."));
        renderSettingPair(inv, 14, 23, "PAPER", "§fChat Toàn Server", chat,
                List.of("§7Bật hoặc ẩn tin nhắn chat chung", "§7từ người chơi khác (tin Admin vẫn hiện)."));
        renderSettingPair(inv, 15, 24, "COMPASS", "§aBáo Tọa Độ Khi Chết", deathCoords,
                List.of("§7Tự động gửi tọa độ §fX / Y / Z", "§7vào khung chat riêng khi bạn chết."));
        renderSettingPair(inv, 16, 25, "NOTE_BLOCK", "§dÂm Thanh Thông Báo", sounds,
                List.of("§7Phát âm thanh ping khi nhận lời mời", "§f/tpa§7, §f/tpahere§7, §f/pay §7hoặc bấm nút."));

        item(inv, 36, "BUCKET", "§eĐặt lại mặc định", List.of("§7Bật lại toàn bộ cài đặt về mặc định", "§eNhấn để khôi phục"));
        item(inv, 40, "ARROW", "§eQuay lại Menu", List.of());
        item(inv, 44, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    private void renderSettingPair(Object inv, int iconSlot, int dyeSlot, String material, String title, boolean enabled, List<String> desc) {
        List<String> iconLore = new ArrayList<>(desc);
        iconLore.add("§7Trạng thái: " + (enabled ? "§a§lĐANG BẬT" : "§c§lĐANG TẮT"));
        iconLore.add("§eNhấn để " + (enabled ? "TẮT" : "BẬT"));
        item(inv, iconSlot, material, title, iconLore);
        item(inv, dyeSlot, enabled ? "LIME_DYE" : "GRAY_DYE",
                enabled ? "§a§lĐANG BẬT" : "§c§lĐANG TẮT",
                List.of("§7Mục: " + title, "§eNhấn để chuyển sang " + (enabled ? "§cTẮT" : "§aBẬT")));
    }

    private void handleSettingsClick(Object player, int slot) {
        String key = switch (slot) {
            case 10, 19 -> "mob_spawn";
            case 11, 20 -> "tpa";
            case 12, 21 -> "pay";
            case 13, 22 -> "scoreboard";
            case 14, 23 -> "global_chat";
            case 15, 24 -> "death_coords";
            case 16, 25 -> "sounds";
            default -> null;
        };
        if (key != null) {
            try { Reflect.call(plugin, "togglePlayerSetting", player, key); } catch (Exception ignored) { }
            openSettings(player);
            return;
        }
        if (slot == 36) {
            try { Reflect.call(plugin, "resetPlayerSettings", player); } catch (Exception ignored) { }
            openSettings(player);
        } else if (slot == 40) {
            openMain(player);
        } else if (slot == 44) {
            Reflect.call(player, "closeInventory");
        }
    }

    private boolean settingEnabled(UUID id, String key) {
        try { return Boolean.TRUE.equals(Reflect.call(plugin, "isPlayerSettingEnabled", id, key)); }
        catch (Exception e) { return true; }
    }



    /** /sethome opens this selector. Clicking an existing slot overwrites that home at the current position;
     * clicking an empty slot creates the next available home name within the player's rank limit. */
    public void openSetHome(Object player) { openHomeMenu(player, true); }

    /** /home and /homes open the saved-home selector. Left click teleports; right click deletes. */
    public void openHomeSelector(Object player) { openHomeMenu(player, false); }

    private void openHomeMenu(Object player, boolean setMode) {
        UUID id = uuid(player);
        try {
            List<String> names = db.homeNames(id);
            homeMenuNames.put(id, new ArrayList<>(names));
            homeSetMode.put(id, setMode);
            Object inv = inventory(setMode ? HOME_SET : HOME_SELECT, 54);
            fill(inv, 54);
            String rank = safeRank(player);
            int limit = Math.max(1, db.homeLimit(rank));
            item(inv, 4, "ENDER_CHEST", setMode ? "§a§lChọn slot để đặt home" : "§b§lHomes của bạn",
                    List.of("§7Rank: §f" + rank, "§7Đã dùng: §f" + names.size() + "§7/§f" + limit,
                            setMode ? "§eClick home cũ để cập nhật vị trí" : "§eClick trái: dịch chuyển", "§7Click phải: xóa home"));
            for (int slot = 0; slot < 45; slot++) {
                if (slot < names.size()) {
                    String home = names.get(slot);
                    item(inv, slot, setMode ? "WRITABLE_BOOK" : "RED_BED", "§a" + home,
                            setMode ? List.of("§7Slot đang sử dụng", "§eClick để cập nhật home tại vị trí hiện tại")
                                    : List.of("§7Home đã lưu", "§eClick trái: đến home", "§cClick phải: xóa home"));
                } else if (setMode && names.size() < limit && slot == names.size()) {
                    item(inv, slot, "LIME_BED", "§aTạo home mới", List.of("§7Tên tự động: §f" + nextHomeName(names), "§7Lưu tại vị trí hiện tại", "§eClick để tạo"));
                } else {
                    item(inv, slot, "GRAY_STAINED_GLASS_PANE", "§r", List.of());
                }
            }
            item(inv, 45, setMode ? "ARROW" : "LIME_BED", setMode ? "§eQuay lại Homes" : "§a§lĐặt home mới",
                    List.of(setMode ? "§7Quay lại danh sách home" : "§7Mở bảng chọn slot để đặt home"));
            item(inv, 49, "BOOK", "§eThông tin", List.of("§7Tên home hỗ trợ a-z, 0-9, _ và -", "§7Dùng /delhome <tên> để xóa bằng lệnh"));
            item(inv, 53, "BARRIER", "§cĐóng", List.of());
            open(player, inv);
        } catch (Exception e) {
            tell(player, "§cKhông tải được danh sách home: " + e.getMessage());
        }
    }

    private String nextHomeName(List<String> names) {
        if (!names.contains("home")) return "home";
        for (int i = 2; i <= 50; i++) if (!names.contains("home" + i)) return "home" + i;
        return "home";
    }

    private boolean handleHomeMenu(Object event, Object player, String title, int slot) {
        try {
            UUID id = uuid(player);
            boolean setMode = title.equals(HOME_SET);
            List<String> names = homeMenuNames.getOrDefault(id, List.of());
            if (slot >= 0 && slot < 45) {
                if (setMode) {
                    if (slot >= names.size() && slot != names.size()) return true;
                    String home = slot < names.size() ? names.get(slot) : nextHomeName(names);
                    int limit = Math.max(1, db.homeLimit(db.rank(id)));
                    if (slot >= names.size() && names.size() >= limit) {
                        tell(player, "§cRank của bạn đã đạt giới hạn " + limit + " home."); return true;
                    }
                    saveHomeAtCurrent(player, home);
                    openSetHome(player);
                } else if (slot < names.size()) {
                    String home = names.get(slot);
                    Object click = Reflect.call(event, "getClick");
                    boolean rightClick = click != null && String.valueOf(click).contains("RIGHT");
                    if (rightClick) {
                        boolean deleted = db.deleteHome(id, home);
                        tell(player, deleted ? "§aĐã xóa home §e" + home + "§a." : "§cKhông tìm thấy home.");
                        openHomeSelector(player);
                    } else {
                        String world = db.homeWorld(id, home); double[] pos = db.home(id, home);
                        if (world == null || pos == null) { tell(player, "§cHome không còn tồn tại."); openHomeSelector(player); return true; }
                        Object targetWorld = Reflect.callStatic("org.bukkit.Bukkit", "getWorld", world);
                        if (targetWorld == null) { tell(player, "§cWorld của home chưa được tải."); return true; }
                        Object location = Reflect.construct("org.bukkit.Location", targetWorld, pos[0], pos[1], pos[2], (float) pos[3], (float) pos[4]);
                        Reflect.call(player, "teleport", location);
                        tell(player, "§aĐã dịch chuyển tới home §e" + home + "§a.");
                        Reflect.call(player, "closeInventory");
                    }
                }
                return true;
            }
            if (slot == 45) {
                if (setMode) openHomeSelector(player); else openSetHome(player);
            } else if (slot == 49) {
                tell(player, "§e/sethome <tên> §7vẫn có thể dùng để lưu home với tên tùy chọn.");
            } else if (slot == 53) Reflect.call(player, "closeInventory");
            return true;
        } catch (Exception e) {
            tell(player, "§cKhông thể xử lý home: " + e.getMessage()); return true;
        }
    }

    private void saveHomeAtCurrent(Object player, String home) throws Exception {
        UUID id = uuid(player);
        int limit = Math.max(1, db.homeLimit(db.rank(id)));
        if (db.homeCount(id) >= limit && !db.homeNames(id).contains(home)) {
            tell(player, "§cRank của bạn chỉ được đặt tối đa " + limit + " home."); return;
        }
        Object loc = Reflect.call(player, "getLocation"); Object world = Reflect.call(loc, "getWorld");
        db.saveHome(id, home, String.valueOf(Reflect.call(world, "getName")),
                ((Number) Reflect.call(loc, "getX")).doubleValue(), ((Number) Reflect.call(loc, "getY")).doubleValue(),
                ((Number) Reflect.call(loc, "getZ")).doubleValue(), ((Number) Reflect.call(loc, "getYaw")).floatValue(),
                ((Number) Reflect.call(loc, "getPitch")).floatValue());
        tell(player, "§aĐã lưu home §e" + home + "§a.");
    }

    public void openSell(Object player) {
        Object inv = inventory(SELL, 54);
        // Slots 0-44 are deposit slots. The bottom row is a locked control bar.
        for (int slot = 45; slot < 54; slot++) item(inv, slot, "GRAY_STAINED_GLASS_PANE", "§r", List.of());
        item(inv, 45, "ARROW", "§eQuay lại Shop", List.of());
        item(inv, 47, "BOOK", "§bCách bán", List.of("§7Đặt hoặc shift-click vật phẩm vào 45 ô phía trên.", "§7Chỉ vật phẩm có giá trong sell.prices mới được bán.", "§7Vật phẩm không bán được sẽ được trả lại khi đóng GUI."));
        item(inv, 49, "EMERALD", "§a§lSELL ALL", List.of("§7Bán tất cả vật phẩm hợp lệ trong khung", "§eNhấn để xác nhận bán"));
        item(inv, 53, "BARRIER", "§cĐóng", List.of("§7Vật phẩm chưa bán sẽ được trả lại"));
        open(player, inv);
    }

    private boolean handleSellClick(Object event, Object player, int slot) {
        try {
            if (slot >= 0 && slot < 45) {
                // Let Bukkit handle placing, picking up and shift-moving deposited items.
                Reflect.call(event, "setCancelled", false);
                return true;
            }
            if (slot >= 54) {
                Reflect.call(event, "setCancelled", false);
                return true;
            }
            Reflect.call(event, "setCancelled", true);
            if (slot == 45) openShop(player);
            else if (slot == 47) tell(player, "§eĐặt các vật phẩm muốn bán vào 45 ô phía trên, sau đó bấm §aSELL ALL§e. Vật phẩm không có giá sẽ được trả lại khi đóng GUI.");
            else if (slot == 49) sellDepositedItems(player, Reflect.call(Reflect.call(event, "getView"), "getTopInventory"));
            else if (slot == 53) Reflect.call(player, "closeInventory");
            return true;
        } catch (Exception ex) { tell(player, "§cKhông thể xử lý Sell GUI: " + ex.getMessage()); return true; }
    }

    public boolean handleDrag(Object event) {
        try {
            String title = String.valueOf(Reflect.call(Reflect.call(event, "getView"), "getTitle"));
            if (!isOurTitle(title)) return false;
            if (title.startsWith(ENDER_CHEST_PREFIX)) {
                UUID viewerId = uuid(Reflect.call(event, "getWhoClicked"));
                UUID ownerId = enderChestViewers.get(viewerId);
                int unlocked = ownerId == null ? 0 : enderChestUnlockedSlots.getOrDefault(ownerId, 54);
                Object rawSlots = Reflect.call(event, "getRawSlots");
                if (rawSlots instanceof Iterable<?> slots) {
                    for (Object raw : slots) {
                        int slot = ((Number) raw).intValue();
                        if (slot >= unlocked && slot < 54) { Reflect.call(event, "setCancelled", true); return true; }
                    }
                    Reflect.call(event, "setCancelled", false);
                    return true;
                }
                Reflect.call(event, "setCancelled", true);
                return true;
            }
            if (title.equals(HOME_SELECT) || title.equals(HOME_SET)) { Reflect.call(event, "setCancelled", true); return true; }
            if (!title.equals(SELL)) { Reflect.call(event, "setCancelled", true); return true; }
            Object rawSlots = Reflect.call(event, "getRawSlots");
            if (rawSlots instanceof Iterable<?> slots) {
                for (Object raw : slots) {
                    int slot = ((Number) raw).intValue();
                    if (slot >= 45 && slot < 54) { Reflect.call(event, "setCancelled", true); return true; }
                }
                Reflect.call(event, "setCancelled", false);
                return true;
            }
            Reflect.call(event, "setCancelled", true);
            return true;
        } catch (Exception ignored) { return false; }
    }

    public void handleClose(Object event) {
        try {
            Object view = Reflect.call(event, "getView");
            String title = String.valueOf(Reflect.call(view, "getTitle"));
            Object player = Reflect.call(event, "getPlayer");
            if (title.startsWith(ENDER_CHEST_PREFIX)) {
                handleEnderChestClose(player, Reflect.call(view, "getTopInventory"));
                return;
            }
            if (!SELL.equals(title)) return;
            Object top = Reflect.call(view, "getTopInventory");
            returnSellContents(player, top);
        } catch (Exception ex) { tell(Reflect.call(event, "getPlayer"), "§cKhông thể xử lý khi đóng GUI; hãy kiểm tra túi đồ."); }
    }

    public void cleanupPlayer(UUID id) {
        if (id == null) return;
        homeMenuNames.remove(id);
        homeSetMode.remove(id);
        shopSelections.remove(id);
        teamMemberCache.remove(id);
        teamInviteCandidates.remove(id);
        UUID ownerId = enderChestViewers.remove(id);
        if (ownerId != null) {
            Object inv = activeEnderChests.get(ownerId);
            if (inv != null) {
                try { db.saveEnderChestData(ownerId, serializeEnderChest(inv)); } catch (Exception ignored) { }
            }
            if (!enderChestViewers.containsValue(ownerId)) {
                activeEnderChests.remove(ownerId);
                enderChestUnlockedSlots.remove(ownerId);
            }
        }
    }

    // -------------------- Expanded 54-Slot Ender Chest (DonutSMP Style) --------------------
    public void openEnderChest(Object viewer, UUID ownerId, String ownerName, Object onlineOwner) {
        try {
            int unlocked = enderChestUnlockedCount(ownerId);
            Object inv = activeEnderChests.get(ownerId);
            if (inv == null) {
                inv = inventory(ENDER_CHEST_PREFIX + ownerName, 54);
                String saved = db.enderChestData(ownerId);
                if (saved == null) {
                    if (onlineOwner != null) {
                        Object vanilla = Reflect.call(onlineOwner, "getEnderChest");
                        if (vanilla != null) {
                            for (int i = 0; i < 27; i++) {
                                Object stack = Reflect.call(vanilla, "getItem", i);
                                if (stack != null && !"AIR".equals(String.valueOf(Reflect.call(stack, "getType")))) {
                                    Reflect.call(inv, "setItem", i, Reflect.call(stack, "clone"));
                                    Reflect.call(vanilla, "setItem", i, airItem());
                                }
                            }
                        }
                    }
                    db.saveEnderChestData(ownerId, serializeEnderChest(inv));
                } else {
                    deserializeEnderChest(inv, saved);
                }
                applyEnderChestLocks(inv, unlocked);
                activeEnderChests.put(ownerId, inv);
            } else {
                applyEnderChestLocks(inv, unlocked);
            }
            enderChestUnlockedSlots.put(ownerId, unlocked);
            enderChestViewers.put(uuid(viewer), ownerId);
            open(viewer, inv);
        } catch (Exception e) {
            tell(viewer, "§cKhông thể mở Ender Chest mở rộng: " + e.getMessage());
        }
    }

    private int enderChestUnlockedCount(UUID ownerId) {
        try {
            String rank = db.rank(ownerId);
            String key = switch (rank) {
                case "+" -> "plus";
                case "++" -> "double-plus";
                case "+++" -> "triple-plus";
                default -> rank.toLowerCase(Locale.ROOT);
            };
            int rows = configInt("enderchest.rows-by-rank." + key, 6);
            return Math.max(27, Math.min(54, rows * 9));
        } catch (Exception e) {
            return 54;
        }
    }

    private void applyEnderChestLocks(Object inv, int unlocked) {
        for (int i = 0; i < 54; i++) {
            Object current = Reflect.call(inv, "getItem", i);
            boolean isLockedPane = isLockedEnderPane(current);
            if (i < unlocked) {
                if (isLockedPane) Reflect.call(inv, "setItem", i, airItem());
            } else {
                if (current == null || "AIR".equals(String.valueOf(Reflect.call(current, "getType")))) {
                    item(inv, i, "RED_STAINED_GLASS_PANE", "§cÔ bị khóa", List.of("§7Nâng cấp rank để mở rộng thêm ô", EC_LOCKED_MARKER));
                }
            }
        }
    }

    private boolean isLockedEnderPane(Object stack) {
        if (stack == null) return false;
        try {
            Object meta = Reflect.call(stack, "getItemMeta");
            if (meta == null) return false;
            Object lore = Reflect.call(meta, "getLore");
            if (lore instanceof List<?> lines) {
                for (Object line : lines) {
                    if (EC_LOCKED_MARKER.equals(String.valueOf(line))) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private boolean handleEnderChestClick(Object event, Object player, int slot) {
        try {
            UUID viewerId = uuid(player);
            UUID ownerId = enderChestViewers.get(viewerId);
            int unlocked = ownerId == null ? 0 : enderChestUnlockedSlots.getOrDefault(ownerId, 54);
            if (slot >= 0 && slot < 54) {
                Object current = Reflect.call(event, "getCurrentItem");
                if (slot >= unlocked && (isLockedEnderPane(current) || current == null || "AIR".equals(String.valueOf(Reflect.call(current, "getType"))))) {
                    Reflect.call(event, "setCancelled", true);
                    return true;
                }
                Reflect.call(event, "setCancelled", false);
                return true;
            }
            Reflect.call(event, "setCancelled", false);
            return true;
        } catch (Exception ex) {
            Reflect.call(event, "setCancelled", true);
            return true;
        }
    }

    private void handleEnderChestClose(Object player, Object top) {
        UUID viewerId = uuid(player);
        UUID ownerId = enderChestViewers.remove(viewerId);
        if (ownerId == null) return;
        try {
            db.saveEnderChestData(ownerId, serializeEnderChest(top));
        } catch (Exception ex) {
            tell(player, "§cLỗi khi lưu Ender Chest. Hãy báo Admin.");
        }
        if (!enderChestViewers.containsValue(ownerId)) {
            activeEnderChests.remove(ownerId);
            enderChestUnlockedSlots.remove(ownerId);
        }
    }

    /** Saves all open Expanded Ender Chests during plugin/server shutdown. */
    public void flushEnderChests() {
        try {
            for (Map.Entry<UUID, Object> entry : activeEnderChests.entrySet()) {
                try { db.saveEnderChestData(entry.getKey(), serializeEnderChest(entry.getValue())); } catch (Exception ignored) { }
            }
            Object online = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers");
            if (online instanceof Iterable<?> players) {
                for (Object player : players) {
                    Object view = Reflect.call(player, "getOpenInventory");
                    if (view != null && String.valueOf(Reflect.call(view, "getTitle")).startsWith(ENDER_CHEST_PREFIX)) {
                        Reflect.call(player, "closeInventory");
                    }
                }
            }
            activeEnderChests.clear();
            enderChestViewers.clear();
            enderChestUnlockedSlots.clear();
        } catch (Exception ignored) { }
    }

    private String serializeEnderChest(Object inv) {
        Object yaml = Reflect.construct("org.bukkit.configuration.file.YamlConfiguration");
        for (int i = 0; i < 54; i++) {
            Object stack = Reflect.call(inv, "getItem", i);
            if (stack == null || "AIR".equals(String.valueOf(Reflect.call(stack, "getType")))) continue;
            if (isLockedEnderPane(stack)) continue;
            Reflect.call(yaml, "set", "slot." + i, stack);
        }
        return String.valueOf(Reflect.call(yaml, "saveToString"));
    }

    private void deserializeEnderChest(Object inv, String data) {
        if (data == null || data.isBlank()) return;
        try {
            Object yaml = Reflect.construct("org.bukkit.configuration.file.YamlConfiguration");
            Reflect.call(yaml, "loadFromString", data);
            for (int i = 0; i < 54; i++) {
                Object stack = Reflect.call(yaml, "getItemStack", "slot." + i);
                if (stack != null && !"AIR".equals(String.valueOf(Reflect.call(stack, "getType")))) {
                    Reflect.call(inv, "setItem", i, stack);
                }
            }
        } catch (Exception ignored) { }
    }

    private Object airItem() {
        return Reflect.construct("org.bukkit.inventory.ItemStack", Reflect.enumValue("org.bukkit.Material", "AIR"));
    }

    private void returnSellContents(Object player, Object top) {
        for (int slot = 0; slot < 45; slot++) {
            Object stack = Reflect.call(top, "getItem", slot);
            if (stack == null || "AIR".equals(String.valueOf(Reflect.call(stack, "getType")))) continue;
            Object clone = Reflect.call(stack, "clone");
            Reflect.call(top, "setItem", slot, Reflect.construct("org.bukkit.inventory.ItemStack", Reflect.enumValue("org.bukkit.Material", "AIR")));
            giveObject(player, clone);
        }
    }

    /** Returns any items left in open Sell GUIs during a clean plugin/server shutdown. */
    public void flushSellInventories() {
        try {
            Object online = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers");
            if (online instanceof Iterable<?> players) for (Object player : players) {
                Object view = Reflect.call(player, "getOpenInventory");
                if (!SELL.equals(String.valueOf(Reflect.call(view, "getTitle")))) continue;
                returnSellContents(player, Reflect.call(view, "getTopInventory"));
                Reflect.call(player, "closeInventory");
            }
        } catch (Exception ignored) { }
    }

    private void sellDepositedItems(Object player, Object top) {
        Map<Integer, Object> snapshots = new LinkedHashMap<>();
        long total = 0; int soldItems = 0; boolean credited = false;
        try {
            for (int slot = 0; slot < 45; slot++) {
                Object stack = Reflect.call(top, "getItem", slot);
                if (stack == null || "AIR".equals(String.valueOf(Reflect.call(stack, "getType")))) continue;
                String material = String.valueOf(Reflect.call(stack, "getType"));
                long unit = sellPrice(material);
                if (unit <= 0 || hasCustomMeta(stack)) continue;
                int amount = ((Number) Reflect.call(stack, "getAmount")).intValue();
                total = Math.addExact(total, Math.multiplyExact(unit, amount));
                soldItems += amount;
                snapshots.put(slot, Reflect.call(stack, "clone"));
            }
            if (snapshots.isEmpty() || total <= 0) { tell(player, "§cKhông có vật phẩm hợp lệ để bán. Các vật phẩm chưa được lấy khỏi GUI."); return; }
            for (Integer slot : snapshots.keySet()) Reflect.call(top, "setItem", slot, Reflect.construct("org.bukkit.inventory.ItemStack", Reflect.enumValue("org.bukkit.Material", "AIR")));
            try {
                economy.deposit(uuid(player), total, "SELL_GUI_ALL");
                credited = true;
            } catch (Exception transactionFailure) {
                for (Map.Entry<Integer, Object> entry : snapshots.entrySet()) Reflect.call(top, "setItem", entry.getKey(), entry.getValue());
                throw transactionFailure;
            }
            tell(player, "§aĐã bán §f" + soldItems + " §avật phẩm và nhận §6$" + MoneyFormat.compact(total) + "§a. Đồ không có giá vẫn được giữ nguyên.");
        } catch (Exception ex) {
            if (!credited) {
                for (Map.Entry<Integer, Object> entry : snapshots.entrySet()) {
                    try { Object current = Reflect.call(top, "getItem", entry.getKey()); if (current == null || "AIR".equals(String.valueOf(Reflect.call(current, "getType")))) Reflect.call(top, "setItem", entry.getKey(), entry.getValue()); } catch (Exception ignored) { }
                }
            }
            tell(player, credited ? "§eĐã thanh toán; GUI có thể cần đóng/mở lại." : "§cGiao dịch bán thất bại. Vật phẩm được giữ trong GUI.");
        }
    }

    private boolean hasCustomMeta(Object stack) {
        try {
            Object meta = Reflect.call(stack, "getItemMeta");
            return Boolean.TRUE.equals(Reflect.call(meta, "hasDisplayName")) || Boolean.TRUE.equals(Reflect.call(meta, "hasLore"));
        } catch (Exception ignored) { return true; }
    }

    private void giveObject(Object player, Object stack) {
        Object leftovers = Reflect.call(Reflect.call(player, "getInventory"), "addItem", arrayOf("org.bukkit.inventory.ItemStack", stack));
        if (leftovers instanceof Map<?, ?> map && !map.isEmpty()) {
            Object loc = Reflect.call(player, "getLocation"); Object world = Reflect.call(loc, "getWorld");
            for (Object left : map.values()) Reflect.call(world, "dropItemNaturally", loc, left);
        }
    }

    public void openShopCategory(Object player, String category) {
        String cat = categoryKey(category);
        if (cat.equals("shards") || cat.equals("tools")) { openShardShop(player); return; }
        if (cat.equals("spawners") && expansion != null) { expansion.openSpawnerShopMenu(player); return; }
        String[][] products = products(cat);
        if (products.length == 0) { openShop(player); return; }
        Object inv = inventory(shopTitle(cat), 27);
        for (int i = 0; i < products.length && i < SHOP_ITEMS.size(); i++) {
            String[] p = products[i]; int slot = SHOP_ITEMS.get(i);
            String key = p[0].toLowerCase(Locale.ROOT);
            long unitPrice = shopPrice(key, Long.parseLong(p[2]));
            int stackSize = maxStackForKey(p[0]);
            List<String> lore = new ArrayList<>();
            lore.add("§7Giá: §a$" + MoneyFormat.compact(unitPrice));
            if (stackSize > 1) lore.add("§71 Stack (" + stackSize + "): §a$" + MoneyFormat.compact(unitPrice * stackSize));
            lore.add("§eClick trái: §fChọn số lượng");
            lore.add("§bClick phải: §fMua nhanh 1x");
            if (stackSize > 1) lore.add("§6Shift + Click: §fMua nhanh " + stackSize + "x");
            shopItem(inv, slot, p[0], 1, p[1], lore);
        }
        item(inv, 18, "ARROW", "§eQuay lại Shop", List.of());
        item(inv, 22, "GOLD_INGOT", "§6Số dư: §a$" + MoneyFormat.compact(safeBalance(player)), List.of());
        item(inv, 26, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    /** Classic DonutSMP Shard Shop (27-slot clean layout): Mob Spawners + Haven 3x3 Tools. */
    public void openShardShop(Object player) {
        Object inv = inventory(SHOP_SHARD, 27);
        long balance = shardBalance(player);
        long spawnerPrice = Math.max(1, configLong("spawners.price-shards-per-spawner", 800));
        long pickPrice = configLong("haven-tools.prices-shards.pickaxe3x3", configLong("amethyst-tools.prices-shards.pickaxe3x3", 2500));
        long shovelPrice = configLong("haven-tools.prices-shards.shovel3x3", configLong("amethyst-tools.prices-shards.shovel3x3", 2000));
        long axePrice = configLong("haven-tools.prices-shards.chopper", configLong("amethyst-tools.prices-shards.chopper", 3000));

        item(inv, 10, "SPAWNER", "§d§lMob Spawners", List.of(
                "§7Mở danh sách 12 loại Spawner",
                "§7Giá: §d" + MoneyFormat.compact(spawnerPrice) + " Shard / spawner",
                "§eNhấn để mở"));
        item(inv, 12, "DIAMOND_PICKAXE", "§dAmethyst Drill §f[3x3]", List.of(
                "§7Khai thác block theo mặt 3x3",
                "§7Phù phép: §bHiệu suất V §7• §bBền bỉ III §7• §aMending",
                "§7Giá: §d" + MoneyFormat.compact(pickPrice) + " Shard",
                "§7Ví: §d" + MoneyFormat.compact(balance) + " Shard",
                "§eNhấn để mua"));
        item(inv, 14, "DIAMOND_SHOVEL", "§dAmethyst Excavator §f[3x3]", List.of(
                "§7Đào đất, cát, vật liệu mềm 3x3",
                "§7Phù phép: §bHiệu suất V §7• §bBền bỉ III §7• §aMending",
                "§7Giá: §d" + MoneyFormat.compact(shovelPrice) + " Shard",
                "§7Ví: §d" + MoneyFormat.compact(balance) + " Shard",
                "§eNhấn để mua"));
        item(inv, 16, "DIAMOND_AXE", "§dAmethyst Chopper", List.of(
                "§7Đốn cả thân cây liền khối",
                "§7Phù phép: §bHiệu suất V §7• §bBền bỉ III §7• §aMending",
                "§7Giá: §d" + MoneyFormat.compact(axePrice) + " Shard",
                "§7Ví: §d" + MoneyFormat.compact(balance) + " Shard",
                "§eNhấn để mua"));
        item(inv, 18, "ARROW", "§eQuay lại Shop", List.of());
        item(inv, 22, "AMETHYST_SHARD", "§dShard: §f" + MoneyFormat.compact(balance), List.of("§7Rank + / ++ / +++ nhận 1 shard/phút"));
        item(inv, 26, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    private void openShopBuy(Object player, ShopSelection sel) {
        UUID id = uuid(player);
        int qty = Math.max(1, Math.min(64, sel.quantity()));
        ShopSelection clamped = new ShopSelection(sel.category(), sel.itemKey(), sel.displayName(), sel.unitPrice(), qty);
        shopSelections.put(id, clamped);
        Object inv = inventory(SHOP_BUY, 27);
        long total = clamped.unitPrice() * (long) qty;
        long bal = safeBalance(player);
        int stackSize = maxStackForKey(clamped.itemKey());
        int displayAmt = Math.max(1, Math.min(stackSize, qty));

        item(inv, 10, "RED_STAINED_GLASS_PANE", "§c§l-64", List.of("§7Đặt số lượng về 1"));
        item(inv, 11, "RED_STAINED_GLASS_PANE", "§c§l-16", List.of("§7Giảm 16 vật phẩm"));
        item(inv, 12, "RED_STAINED_GLASS_PANE", "§c§l-1", List.of("§7Giảm 1 vật phẩm"));
        shopItem(inv, 13, clamped.itemKey(), displayAmt, clamped.displayName() + " §7x" + qty, List.of(
                "§7Số lượng: §f" + qty,
                "§7Đơn giá: §a$" + MoneyFormat.compact(clamped.unitPrice()),
                "§7Tổng tiền: §6$" + MoneyFormat.compact(total),
                "§7Số dư: " + (bal >= total ? "§a$" : "§c$") + MoneyFormat.compact(bal)));
        item(inv, 14, "LIME_STAINED_GLASS_PANE", "§a§l+1", List.of("§7Tăng 1 vật phẩm"));
        item(inv, 15, "LIME_STAINED_GLASS_PANE", "§a§l+16", List.of("§7Tăng 16 vật phẩm"));
        item(inv, 16, "LIME_STAINED_GLASS_PANE", "§a§l+64", List.of("§7Đặt tối đa 64 vật phẩm"));
        item(inv, 18, "ARROW", "§eQuay lại " + shopTitle(clamped.category()).replace("§8SHOP • ", ""), List.of());
        item(inv, 22, bal >= total ? "LIME_CONCRETE" : "RED_CONCRETE",
                bal >= total ? "§a§lXÁC NHẬN MUA" : "§c§lKHÔNG ĐỦ TIỀN",
                List.of("§7Mua §f" + qty + "x " + clamped.displayName(), "§7Tổng giá: §6$" + MoneyFormat.compact(total), "§eNhấn để mua"));
        int quickStack = stackSize > 1 ? stackSize : 1;
        item(inv, 24, "CHEST", "§6Mua nhanh " + quickStack + "x", List.of(
                "§7Mua ngay 1 stack (" + quickStack + "x)",
                "§7Tổng giá: §6$" + MoneyFormat.compact(clamped.unitPrice() * (long) quickStack)));
        item(inv, 26, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    public void openCrates(Object player) {
        Object inv = inventory(CRATES, 27); fill(inv, 27);
        UUID id = uuid(player);
        item(inv, 11, "CHEST", "§eCommon Crate", List.of("§7Key: §f" + keys(id, "common"), "§7Gói quà đảm bảo: tiền, đồ, shard", "§eNhấn để mở"));
        item(inv, 13, "ENDER_CHEST", "§dRank Crate", List.of("§7Key: §f" + keys(id, "rank"), "§7Gói quà đảm bảo cao cấp", "§eNhấn để mở"));
        item(inv, 15, "BOOK", "§bXem phần thưởng", List.of("§7Xem chính xác gói quà được nhận", "§eNhấn để xem"));
        item(inv, 22, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    public void openCratePreview(Object player, String crate) {
        Object inv = inventory(CRATE_PREVIEW, 27); fill(inv, 27);
        String id = crate.equalsIgnoreCase("rank") ? "rank" : "common";
        String path = "crates." + id + ".rewards.";
        if (id.equals("rank")) {
            item(inv, 10, "GOLD_INGOT", "§6$" + MoneyFormat.compact(configLong(path + "money", 1500)), List.of("§7Được đảm bảo trong mỗi Rank Crate"));
            item(inv, 12, "DIAMOND", "§bDiamond x" + configInt(path + "diamonds", 5), List.of("§7Được đảm bảo trong mỗi Rank Crate"));
            item(inv, 14, "GOLDEN_APPLE", "§6Golden Apple x" + configInt(path + "apples", 2), List.of("§7Được đảm bảo trong mỗi Rank Crate"));
            item(inv, 16, "AMETHYST_SHARD", "§dShard x" + configInt(path + "shards", 32), List.of("§7Được đảm bảo trong mỗi Rank Crate"));
            item(inv, 18, "DIAMOND_PICKAXE", "§bHaven Drill 3x3", List.of("§7Công cụ được đảm bảo"));
        } else {
            item(inv, 10, "GOLD_INGOT", "§6$" + MoneyFormat.compact(configLong(path + "money", 500)), List.of("§7Được đảm bảo trong mỗi Common Crate"));
            item(inv, 12, "DIAMOND", "§bDiamond x" + configInt(path + "diamonds", 2), List.of("§7Được đảm bảo trong mỗi Common Crate"));
            item(inv, 14, "COOKED_BEEF", "§aCooked Beef x" + configInt(path + "food", 16), List.of("§7Được đảm bảo trong mỗi Common Crate"));
            item(inv, 16, "AMETHYST_SHARD", "§dShard x" + configInt(path + "shards", 8), List.of("§7Được đảm bảo trong mỗi Common Crate"));
            item(inv, 18, "DIAMOND_SHOVEL", "§bHaven Excavator 3x3", List.of("§7Công cụ được đảm bảo"));
        }
        item(inv, 22, "ARROW", "§eQuay lại", List.of()); open(player, inv);
    }

    public void openCrateEditor(Object player, String crate) {
        String id = crate.equalsIgnoreCase("rank") ? "rank" : "common";
        Object inv = inventory(CRATE_EDITOR_PREFIX + id, 27); fill(inv, 27);
        String path = "crates." + id + ".rewards.";
        item(inv, 10, "GOLD_INGOT", "§6Money: " + MoneyFormat.compact(configLong(path + "money", id.equals("rank") ? 1500 : 500)), List.of("§7Guaranteed reward bundle", "§7Chỉnh trong config.yml"));
        item(inv, 12, "DIAMOND", "§bDiamonds: " + configInt(path + "diamonds", id.equals("rank") ? 5 : 2), List.of("§7Guaranteed reward bundle"));
        item(inv, 14, "GOLDEN_APPLE", "§6Apples: " + configInt(path + "apples", id.equals("rank") ? 2 : 0), List.of("§7Guaranteed reward bundle"));
        item(inv, 16, "AMETHYST_SHARD", "§dShards: " + configInt(path + "shards", id.equals("rank") ? 32 : 8), List.of("§7Guaranteed reward bundle"));
        item(inv, 18, id.equals("rank") ? "DIAMOND_PICKAXE" : "DIAMOND_SHOVEL", "§bHaven Tool", List.of("§7Mỗi crate đều có công cụ này"));
        item(inv, 22, "ARROW", "§eQuay lại Admin", List.of()); open(player, inv);
    }

    public void openRanks(Object player) {
        Object inv = inventory(RANKS, 27); fill(inv, 27);
        String current = safeRank(player);
        item(inv, 4, "NAME_TAG", "§6Rank hiện tại: " + safePrefix(player), List.of("§7Tên rank: §f" + current, "§7Prefix xuất hiện trong chat và TAB"));
        item(inv, 11, "IRON_INGOT", "§aRank + §8- §e$" + MoneyFormat.compact(configLong("rank-shop.prices.plus", 50_000)), List.of("§7Thanh toán bằng tiền trong game", "§7Nhận §d1 shard/phút §7khi online", "§7Home tối đa: §f2", "§eNhấn để mua"));
        item(inv, 13, "GOLD_INGOT", "§bRank ++ §8- §e$" + MoneyFormat.compact(configLong("rank-shop.prices.double-plus", 100_000)), List.of("§7Thanh toán bằng tiền trong game", "§7Nhận §d1 shard/phút §7khi online", "§7Home tối đa: §f3", "§eNhấn để mua"));
        item(inv, 15, "DIAMOND", "§dRank +++ §8- §e$" + MoneyFormat.compact(configLong("rank-shop.prices.triple-plus", 500_000)), List.of("§7Thanh toán bằng tiền trong game", "§7Nhận §d1 shard/phút §7khi online", "§7Home tối đa: §f5", "§eNhấn để mua"));
        item(inv, 22, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    public void openHeartShop(Object player) {
        Object inv = inventory(HEART_SHOP, 27); fill(inv, 27);
        long cost = configLong("lifesteal.heart-shop-cost", 5000);
        item(inv, 13, "GOLDEN_APPLE", "§6Mua thêm 1 tim", List.of("§7Giá: §6$" + MoneyFormat.compact(cost), "§7Giới hạn: §c" + configInt("lifesteal.maximum-hearts", 20) + " tim", "§eNhấn để mua"));
        item(inv, 22, "ARROW", "§eQuay lại", List.of());
        open(player, inv);
    }

    public void openAdmin(Object player) {
        Object inv = inventory(ADMIN, 27); fill(inv, 27);
        item(inv, 10, "REDSTONE_TORCH", "§cLifeSteal: " + (life.enabled() ? "§aON" : "§cOFF"), List.of("§7Nhấn để bật/tắt event"));
        item(inv, 12, "GOLD_INGOT", "§6Pending rank payments", List.of("§7Dùng /rank pending ở chat"));
        item(inv, 14, "PAPER", "§eSpawn protection", List.of("§7Dùng /spawnregion pos1", "§7Dùng /spawnregion pos2", "§7Dùng /spawnregion save"));
        item(inv, 16, "BOOK", "§bCode & Crates", List.of("§7/code • /crate", "§7Quản lý trong chat"));
        item(inv, 22, "BARRIER", "§cĐóng", List.of());
        open(player, inv);
    }

    public boolean handleClick(Object event) {
        try {
            Object view = Reflect.call(event, "getView");
            String title = String.valueOf(Reflect.call(view, "getTitle"));
            if (!isOurTitle(title)) return false;
            Object who = Reflect.call(event, "getWhoClicked");
            int slot = ((Number) Reflect.call(event, "getRawSlot")).intValue();
            if (slot < 0) return true;
            if (title.equals(SELL)) return handleSellClick(event, who, slot);
            if (title.startsWith(ENDER_CHEST_PREFIX)) return handleEnderChestClick(event, who, slot);
            Reflect.call(event, "setCancelled", true);
            if (title.equals(TEAM_MEMBERS) || title.equals(TEAM_INVITE) || title.equals(TEAM_LIST)) {
                if (slot >= 54) return true;
            } else if (title.equals(TEAM_EMPTY) || title.startsWith(TEAM_MAIN_PREFIX) || title.equals(SHOP) || title.equals(SHOP_BUY) || title.equals(SHOP_SHARD) || title.startsWith("§8SHOP • ") || title.startsWith(STATS_PREFIX) || title.equals(LIFE) || title.equals(MAIN) || title.equals(UTILITY) || title.equals(CRATES) || title.equals(RANKS) || title.equals(ADMIN) || title.equals(HEART_SHOP) || title.equals(CRATE_PREVIEW) || title.equals(CRATE_ANIMATION) || title.equals(PROFILE) || title.equals(SHARD_TOP) || title.startsWith(CRATE_EDITOR_PREFIX)) {
                if (slot >= 27) return true;
            } else if (title.equals(SETTINGS)) {
                if (slot >= 45) return true;
            }
            switch (title) {
                case MAIN -> {
                    if (slot == 9) openProfile(who);
                    else if (slot == 10) openShop(who);
                    else if (slot == 11) openSell(who);
                    else if (slot == 12) openRanks(who);
                    else if (slot == 13) Reflect.call(who, "performCommand", "playtime");
                    else if (slot == 14) openProfile(who);
                    else if (slot == 15 && expansion != null) expansion.openOrdersMenu(who);
                    else if (slot == 16 && expansion != null) expansion.openAuctionMenu(who);
                    else if (slot == 17 && expansion != null) expansion.openSpawnerShopMenu(who);
                    else if (slot == 22) openUtility(who);
                }
                case PROFILE -> { if (slot == 22) openMain(who); else if (slot == 26) Reflect.call(who, "closeInventory"); }
                case SHARD_TOP -> { if (slot == 22) openMain(who); else if (slot == 26) Reflect.call(who, "closeInventory"); }
                case UTILITY -> {
                    if (slot == 10) openLife(who);
                    else if (slot == 11) Reflect.call(who, "performCommand", "ec");
                    else if (slot == 12) Reflect.call(who, "sendMessage", "§eNhập code bằng §f/redeem <mã>§e.");
                    else if (slot == 13) openSettings(who);
                    else if (slot == 14) Reflect.call(who, "sendMessage", "§eHomes: §f/sethome, /home, /homes, /delhome §7| §eWarps: §f/warp <tên>, /spawn");
                    else if (slot == 15) openTeamMenu(who);
                    else if (slot == 16) Reflect.call(who, "performCommand", "daily");
                    else if (slot == 18) Reflect.call(who, "performCommand", "playreward");
                    else if (slot == 20) openShopCategory(who, "spawners");
                    else if (slot == 22) openMain(who);
                    else if (slot == 26) Reflect.call(who, "closeInventory");
                }
                case LIFE -> {
                    if (slot == 22) openHeartShop(who);
                    else if (slot == 26 && hasAdmin(who)) {
                        if (life.enabled()) { life.stop(); if(eventEndCallback!=null)eventEndCallback.run(); broadcast("§cLifeSteal Event đã kết thúc."); }
                        else { life.start(); broadcast("§aLifeSteal Event đã bắt đầu!"); }
                        openLife(who);
                    }
                }
                case SHOP -> {
                    if (slot == 11) openShopCategory(who, "end");
                    else if (slot == 12) openShopCategory(who, "nether");
                    else if (slot == 13) openShopCategory(who, "gear");
                    else if (slot == 14) openShopCategory(who, "food");
                    else if (slot == 15) openShardShop(who);
                }
                case SHOP_SHARD -> {
                    if (slot == 10 && expansion != null) expansion.openSpawnerShopMenu(who);
                    else if (slot == 12 && expansion != null) { expansion.purchaseHavenTool(who, "pickaxe3x3"); openShardShop(who); }
                    else if (slot == 14 && expansion != null) { expansion.purchaseHavenTool(who, "shovel3x3"); openShardShop(who); }
                    else if (slot == 16 && expansion != null) { expansion.purchaseHavenTool(who, "chopper"); openShardShop(who); }
                    else if (slot == 18) openShop(who);
                    else if (slot == 26) Reflect.call(who, "closeInventory");
                }
                case SHOP_BUY -> handleShopBuyClick(who, slot);
                case SETTINGS -> handleSettingsClick(who, slot);
                case HOME_SELECT -> handleHomeMenu(event, who, title, slot);
                case HOME_SET -> handleHomeMenu(event, who, title, slot);
                case CRATES -> {
                    if (slot == 11) openCrate(who, "common");
                    else if (slot == 13) openCrate(who, "rank");
                    else if (slot == 15) openCratePreview(who, "common");
                    else if (slot == 17) openCratePreview(who, "rank");
                    else if (slot == 22) Reflect.call(who, "closeInventory");
                }
                case RANKS -> {
                    if (slot == 11) purchaseRankMoney(who, "+", configLong("rank-shop.prices.plus", 50_000));
                    else if (slot == 13) purchaseRankMoney(who, "++", configLong("rank-shop.prices.double-plus", 100_000));
                    else if (slot == 15) purchaseRankMoney(who, "+++", configLong("rank-shop.prices.triple-plus", 500_000));
                    else if (slot == 22) Reflect.call(who, "closeInventory");
                }
                case HEART_SHOP -> {
                    if (slot == 13) purchaseHeart(who);
                    else if (slot == 22) openLife(who);
                }
                case CRATE_PREVIEW -> { if (slot == 22) openCrates(who); }
                case ADMIN -> {
                    if (slot == 10 && hasAdmin(who)) {
                        if (life.enabled()) { life.stop(); if(eventEndCallback!=null)eventEndCallback.run(); } else life.start();
                        openAdmin(who);
                    } else if (slot == 12 && hasAdmin(who)) tell(who,"§e/rank pending §7- danh sách thanh toán chờ xác minh. Dùng /rank confirm <id> sau khi kiểm tra giao dịch.");
                    else if (slot == 14 && hasAdmin(who)) tell(who,"§eChọn hai góc đối diện: /spawnregion pos1, /spawnregion pos2, /spawnregion save. Dùng /spawnregion info để xem, /spawnregion clear để xóa.");
                    else if (slot == 16 && hasAdmin(who)) openCrateEditor(who, "common");
                    else if (slot == 22) Reflect.call(who, "closeInventory");
                }
                default -> {
                    if (title.startsWith(CRATE_EDITOR_PREFIX)) {
                        if (!hasAdmin(who)) { Reflect.call(who, "closeInventory"); return true; }
                        String crate = title.substring(CRATE_EDITOR_PREFIX.length()).toLowerCase(Locale.ROOT);
                        if (slot == 22) openAdmin(who);

                    } else if (title.equals(TEAM_EMPTY) || title.equals(TEAM_MEMBERS) || title.equals(TEAM_INVITE) || title.equals(TEAM_LIST) || title.startsWith(TEAM_MAIN_PREFIX)) {
                        handleTeamGuiClick(event, who, title, slot);
                    } else if (title.startsWith(STATS_PREFIX)) {
                        if (slot == 22) Reflect.call(who, "closeInventory");
                    } else if (title.startsWith("§8SHOP • ")) {
                        if (slot == 18) openShop(who);
                        else if (slot == 26) Reflect.call(who, "closeInventory");
                        else if (slot != 22) purchaseCategory(event, who, title.substring("§8SHOP • ".length()).toLowerCase(Locale.ROOT), slot);
                    }
                }
            }
            return true;
        } catch (Exception e) { return true; }
    }

    private boolean isOurTitle(String title) {
        return Set.of(MAIN, UTILITY, LIFE, SHOP, SHOP_BUY, SHOP_SHARD, SETTINGS, SELL, CRATES, RANKS, ADMIN, HEART_SHOP, CRATE_PREVIEW, CRATE_ANIMATION, PROFILE, HOME_SELECT, HOME_SET, SHARD_TOP).contains(title)
                || title.startsWith("§8SHOP • ")
                || title.startsWith(CRATE_EDITOR_PREFIX)
                || title.startsWith(ENDER_CHEST_PREFIX)
                || title.startsWith(STATS_PREFIX)
                || title.startsWith(TEAM_MAIN_PREFIX);
    }

    private void purchaseCategory(Object event, Object player, String category, int slot) {
        int index = SHOP_ITEMS.indexOf(slot);
        String cat = categoryKey(category);
        String[][] list = products(cat);
        if (index < 0 || index >= list.length) return;
        if (cat.equals("shards")) { purchaseShardReward(player, slot); return; }
        String[] p = list[index];
        String key = p[0].toLowerCase(Locale.ROOT);
        long unitPrice = shopPrice(key, Long.parseLong(p[2]));
        String clickType = String.valueOf(Reflect.call(event, "getClick"));
        if (clickType.contains("SHIFT")) {
            int stackSize = Math.max(1, maxStackForKey(p[0]));
            executeShopPurchase(player, p[0], p[1], unitPrice, stackSize);
            openShopCategory(player, cat);
        } else if (clickType.contains("RIGHT")) {
            executeShopPurchase(player, p[0], p[1], unitPrice, 1);
            openShopCategory(player, cat);
        } else {
            openShopBuy(player, new ShopSelection(cat, p[0], p[1], unitPrice, 1));
        }
    }

    private void handleShopBuyClick(Object player, int slot) {
        UUID id = uuid(player);
        ShopSelection sel = shopSelections.get(id);
        if (sel == null) { openShop(player); return; }
        int q = sel.quantity();
        if (slot == 10) openShopBuy(player, new ShopSelection(sel.category(), sel.itemKey(), sel.displayName(), sel.unitPrice(), 1));
        else if (slot == 11) openShopBuy(player, new ShopSelection(sel.category(), sel.itemKey(), sel.displayName(), sel.unitPrice(), Math.max(1, q - 16)));
        else if (slot == 12) openShopBuy(player, new ShopSelection(sel.category(), sel.itemKey(), sel.displayName(), sel.unitPrice(), Math.max(1, q - 1)));
        else if (slot == 14) openShopBuy(player, new ShopSelection(sel.category(), sel.itemKey(), sel.displayName(), sel.unitPrice(), Math.min(64, q + 1)));
        else if (slot == 15) openShopBuy(player, new ShopSelection(sel.category(), sel.itemKey(), sel.displayName(), sel.unitPrice(), Math.min(64, q + 16)));
        else if (slot == 16) openShopBuy(player, new ShopSelection(sel.category(), sel.itemKey(), sel.displayName(), sel.unitPrice(), 64));
        else if (slot == 18) openShopCategory(player, sel.category());
        else if (slot == 22) {
            executeShopPurchase(player, sel.itemKey(), sel.displayName(), sel.unitPrice(), q);
            openShopBuy(player, sel);
        } else if (slot == 24) {
            int quickStack = Math.max(1, maxStackForKey(sel.itemKey()));
            executeShopPurchase(player, sel.itemKey(), sel.displayName(), sel.unitPrice(), quickStack);
            openShopBuy(player, sel);
        } else if (slot == 26) {
            Reflect.call(player, "closeInventory");
        }
    }

    private void executeShopPurchase(Object player, String itemKey, String displayName, long unitPrice, int amount) {
        int safeAmount = Math.max(1, Math.min(64, amount));
        long total;
        try { total = Math.multiplyExact(unitPrice, safeAmount); }
        catch (ArithmeticException ex) { tell(player, "§cGiá trị giao dịch vượt giới hạn."); return; }
        UUID id = uuid(player);
        if (!economy.withdraw(id, total, "HAVEN_SHOP:" + itemKey.toLowerCase(Locale.ROOT))) {
            tell(player, "§cBạn không đủ tiền. Cần §6$" + MoneyFormat.compact(total) + "§c.");
            return;
        }
        boolean delivered = giveShopItem(player, itemKey, safeAmount);
        if (!delivered) {
            economy.deposit(id, total, "HAVEN_SHOP_REFUND");
            tell(player, "§cKhông thể giao vật phẩm, đã hoàn tiền.");
        } else {
            tell(player, "§aĐã mua §f" + safeAmount + "x " + displayName + " §avới giá §6$" + MoneyFormat.compact(total) + "§a.");
        }
    }

    private void purchaseShardReward(Object player, int slot) {
        int index = SHOP_ITEMS.indexOf(slot);
        String[][] list = products("shards");
        if (index < 0 || index >= list.length) return;
        String[] p = list[index];
        long cost = Long.parseLong(p[2]);
        UUID id = uuid(player);
        try {
            if (!db.spendShards(id, cost, "SHARD_SHOP:" + p[1])) { tell(player, "§cBạn không đủ shard."); return; }
            boolean ok;
            if (index == 0) { db.addKey(id, "common", Integer.parseInt(p[3])); ok = true; }
            else if (index == 1) { db.addKey(id, "rank", Integer.parseInt(p[3])); ok = true; }
            else ok = give(player, p[0], Integer.parseInt(p[3]));
            if (!ok) { db.addShards(id, cost, "SHARD_SHOP_REFUND"); tell(player, "§cTúi đồ đầy, shard đã được hoàn lại."); }
            else tell(player, "§aĐổi thành công! Còn §d" + MoneyFormat.compact(db.shardBalance(id)) + " shard§a.");
        } catch (Exception e) { tell(player, "§cKhông thể hoàn tất giao dịch shard."); }
    }

    public void purchaseRankMoney(Object player, String rank, long cost) {
        UUID id = uuid(player);
        try {
            String current = db.rank(id);
            if (db.rankPriority(current) >= db.rankPriority(rank)) { tell(player, "§cBạn đã có rank này hoặc rank cao hơn."); return; }
            if (!economy.withdraw(id, cost, "RANK_PURCHASE:" + rank)) { tell(player, "§cBạn cần §6$" + MoneyFormat.compact(cost) + "§c để mua rank " + rank + "."); return; }
            db.setRank(id, rank, 0);
            tell(player, "§aBạn đã nâng cấp lên rank §f" + rank + "§a. Từ giờ nhận 1 shard mỗi phút khi online!");
            Reflect.call(player, "closeInventory");
        } catch (Exception e) { tell(player, "§cKhông thể nâng cấp rank."); }
    }

    private void requestVndRank(Object player, String rank, int price) {
        UUID id = uuid(player);
        try {
            String current = db.rank(id);
            if (db.rankPriority(current) >= db.rankPriority(rank)) { tell(player, "§cBạn đã có rank này hoặc rank cao hơn."); return; }
            long requestId = db.createRankPurchase(id, String.valueOf(Reflect.call(player, "getName")), rank, price);
            String bank = configString("rank-payment.bank-name", "Chưa cấu hình ngân hàng");
            String account = configString("rank-payment.account-name", "Haven SMP");
            String number = configString("rank-payment.account-number", "CHUA-CAU-HINH");
            String prefix = configString("rank-payment.content-prefix", "HAVEN");
            tell(player, "§aĐã tạo yêu cầu mua rank §e" + rank + " §7(#" + requestId + ")");
            tell(player, "§eChuyển §6" + String.format(Locale.US, "%,d", price) + " VND §etới §f" + bank + " §7- §f" + account + "§e.");
            tell(player, "§eSTK: §f" + number + " §e| Nội dung: §f" + prefix + requestId);
            tell(player, "§7Sau khi chuyển khoản, Admin xác nhận. Rank không tự cấp khi chưa xác minh giao dịch.");
            Reflect.call(player, "closeInventory");
        } catch (Exception e) { tell(player, "§cKhông tạo được yêu cầu thanh toán."); }
    }

    private void purchaseHeart(Object player) {
        if (!life.enabled()) { tell(player, "§cHeart Shop chỉ hoạt động khi LifeSteal Event đang mở."); return; }
        UUID id = uuid(player); long cost = configLong("lifesteal.heart-shop-cost", 5000);
        int current = life.hearts(id); int max = configInt("lifesteal.maximum-hearts", 20);
        if (current >= max) { tell(player, "§cBạn đã đạt giới hạn tim."); return; }
        if (!economy.withdraw(id, cost, "LIFESTEAL_HEART_SHOP")) { tell(player, "§cBạn không đủ tiền. Cần §6$" + MoneyFormat.compact(cost)); return; }
        life.setHearts(id, current + 1);
        tell(player, "§aBạn đã mua thêm 1 tim!"); openHeartShop(player);
    }

    private void openCrate(Object player, String crate) {
        UUID id = uuid(player);
        try {
            if (crateSessions.containsKey(id)) { tell(player, "§cBạn đang mở một crate khác."); return; }
            if (db.keys(id, crate) <= 0) { tell(player, "§cBạn không có chìa khóa " + crate + " crate."); return; }
            if (!db.takeKey(id, crate)) { tell(player, "§cKhông thể sử dụng chìa khóa."); return; }
            crateSessions.put(id, crate);
            Object inv = inventory(CRATE_ANIMATION, 27); fill(inv, 27);
            item(inv, 4, "CHEST", "§6Haven Crate", List.of("§7Đang quay phần thưởng..."));
            item(inv, 13, "AMETHYST_SHARD", "§dĐang mở rương...", List.of("§7Vui lòng chờ một chút"));
            item(inv, 22, "BARRIER", "§cĐang mở...", List.of("§7Không thể thao tác trong lúc mở"));
            open(player, inv);
            animateCrate(id, crate, inv, 0);
        } catch (Exception e) { tell(player, "§cKhông mở được crate. Hãy báo admin."); }
    }

    private void animateCrate(UUID id, String crate, Object inventory, int frame) {
        String[] frames = {"TRIPWIRE_HOOK","GOLD_INGOT","DIAMOND","AMETHYST_SHARD","GOLDEN_APPLE","CHEST"};
        Reflect.scheduleLater(plugin, () -> {
            if (!Objects.equals(crateSessions.get(id), crate)) return;
            Object player = Reflect.callStatic("org.bukkit.Bukkit", "getPlayer", id);
            if (player == null) { cancelCrateOpen(id); return; }
            if (frame < frames.length) {
                item(inventory, 13, frames[frame], "§dĐang mở rương" + ".".repeat((frame % 3) + 1), List.of("§7Haven SMP Crate"));
                animateCrate(id, crate, inventory, frame + 1);
                return;
            }
            crateSessions.remove(id);
            try { rewardCrate(player, id, crate); tell(player, "§aĐã mở " + crate + " crate thành công!"); openCrates(player); }
            catch (Exception e) { try { db.addKey(id,crate,1); } catch(Exception ignored) {} tell(player, "§cCó lỗi khi trao thưởng crate; key đã được hoàn lại."); }
        }, Math.max(2, configInt("crates.animation-interval-ticks", 4)));
    }

    public void cancelCrateOpen(UUID id) {
        String crate = crateSessions.remove(id);
        if (crate == null) return;
        try { db.addKey(id, crate, 1); } catch (Exception ignored) { }
    }

    private void rewardCrate(Object player, UUID id, String crate) throws Exception {
        // Transparent, guaranteed bundles: no weighted/random paid loot-box roll.
        String path = "crates." + crate + ".rewards.";
        long money = Math.max(0, configLong(path + "money", crate.equals("rank") ? 1500 : 500));
        int diamonds = Math.max(0, configInt(path + "diamonds", crate.equals("rank") ? 5 : 2));
        int apples = Math.max(0, configInt(path + "apples", crate.equals("rank") ? 2 : 0));
        int food = Math.max(0, configInt(path + "food", crate.equals("rank") ? 0 : 16));
        int shards = Math.max(0, configInt(path + "shards", crate.equals("rank") ? 32 : 8));
        String tool = configString(path + "tool", crate.equals("rank") ? "pickaxe3x3" : "shovel3x3");
        if (money > 0) economy.deposit(id, money, crate.toUpperCase(Locale.ROOT) + "_CRATE");
        if (diamonds > 0) give(player, "DIAMOND", diamonds);
        if (apples > 0) give(player, "GOLDEN_APPLE", apples);
        if (food > 0) give(player, "COOKED_BEEF", food);
        if (shards > 0) db.addShards(id, shards, crate.toUpperCase(Locale.ROOT) + "_CRATE");
        if (!tool.isBlank() && expansion != null && !expansion.give3x3Tool(player, tool)) throw new IllegalStateException("Could not give crate tool");
        tell(player, "§6Phần thưởng bảo đảm: §e$" + MoneyFormat.compact(money) + " §b" + diamonds + " diamond §d" + shards + " shard§6.");
    }

    private long sellPrice(String material) { return configLong("sell.prices." + material.toLowerCase(Locale.ROOT), 0L); }

    private void tell(Object player, String msg) { Reflect.call(player, "sendMessage", msg); }
    private boolean give(Object player, String material, int amount) {
        try {
            Object item = Reflect.construct("org.bukkit.inventory.ItemStack", Reflect.enumValue("org.bukkit.Material", material), amount);
            Object leftovers = Reflect.call(Reflect.call(player, "getInventory"), "addItem", arrayOf("org.bukkit.inventory.ItemStack", item));
            if (leftovers instanceof Map<?, ?> map && !map.isEmpty()) {
                Object loc = Reflect.call(player, "getLocation");
                Object world = Reflect.call(loc, "getWorld");
                for (Object leftover : map.values()) Reflect.call(world, "dropItemNaturally", loc, leftover);
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    private Object createShopItemStack(String itemKey, int amount) {
        int safeAmt = Math.max(1, Math.min(64, amount));
        if ("ARROW_OF_SLOW_FALLING".equalsIgnoreCase(itemKey)) {
            Object stack = Reflect.construct("org.bukkit.inventory.ItemStack", Reflect.enumValue("org.bukkit.Material", "TIPPED_ARROW"), safeAmt);
            Object meta = Reflect.call(stack, "getItemMeta");
            try {
                Object potionType = Reflect.enumValue("org.bukkit.potion.PotionType", "SLOW_FALLING");
                Reflect.call(meta, "setBasePotionType", potionType);
            } catch (Exception legacy) {
                try {
                    Object potionType = Reflect.enumValue("org.bukkit.potion.PotionType", "SLOW_FALLING");
                    Object potionData = Reflect.construct("org.bukkit.potion.PotionData", potionType);
                    Reflect.call(meta, "setBasePotionData", potionData);
                } catch (Exception ignored) { }
            }
            Reflect.call(stack, "setItemMeta", meta);
            return stack;
        }
        return Reflect.construct("org.bukkit.inventory.ItemStack", Reflect.enumValue("org.bukkit.Material", itemKey), safeAmt);
    }

    private int maxStackForKey(String itemKey) {
        try {
            String matName = "ARROW_OF_SLOW_FALLING".equalsIgnoreCase(itemKey) ? "TIPPED_ARROW" : itemKey;
            Object mat = Reflect.enumValue("org.bukkit.Material", matName);
            int max = ((Number) Reflect.call(mat, "getMaxStackSize")).intValue();
            return Math.max(1, Math.min(64, max));
        } catch (Exception e) {
            return 64;
        }
    }

    private boolean giveShopItem(Object player, String itemKey, int totalAmount) {
        try {
            int maxStack = maxStackForKey(itemKey);
            int remaining = totalAmount;
            while (remaining > 0) {
                int part = Math.min(maxStack, remaining);
                Object stack = createShopItemStack(itemKey, part);
                giveObject(player, stack);
                remaining -= part;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void shopItem(Object inv, int slot, String itemKey, int amount, String name, List<String> lore) {
        try {
            Object stack = createShopItemStack(itemKey, amount);
            Object meta = Reflect.call(stack, "getItemMeta");
            Reflect.call(meta, "setDisplayName", name);
            Reflect.call(meta, "setLore", lore);
            Reflect.call(stack, "setItemMeta", meta);
            Reflect.call(inv, "setItem", slot, stack);
        } catch (Exception ignored) { }
    }

    private String[][] products(String category) {
        return switch (categoryKey(category)) {
            case "end" -> new String[][]{
                    {"ENDER_CHEST", "§5Ender Chest", "2500", "1"},
                    {"ENDER_PEARL", "§3Ender Pearl", "75", "1"},
                    {"END_STONE", "§eEnd Stone", "16", "1"},
                    {"DRAGON_BREATH", "§dDragon's Breath", "1000", "1"},
                    {"END_ROD", "§fEnd Rod", "100", "1"},
                    {"CHORUS_FRUIT", "§dChorus Fruit", "108", "1"},
                    {"POPPED_CHORUS_FRUIT", "§dPopped Chorus Fruit", "24", "1"},
                    {"SHULKER_SHELL", "§dShulker Shell", "350", "1"},
                    {"SHULKER_BOX", "§dShulker Box", "800", "1"}};
            case "nether" -> new String[][]{
                    {"BLAZE_ROD", "§6Blaze Rod", "150", "1"},
                    {"NETHER_WART", "§cNether Wart", "96", "1"},
                    {"GLOWSTONE_DUST", "§eGlowstone Dust", "15", "1"},
                    {"MAGMA_CREAM", "§6Magma Cream", "96", "1"},
                    {"GHAST_TEAR", "§fGhast Tear", "350", "1"},
                    {"QUARTZ", "§fNether Quartz", "30", "1"},
                    {"SOUL_SAND", "§8Soul Sand", "50", "1"},
                    {"MAGMA_BLOCK", "§cMagma Block", "35", "1"},
                    {"CRYING_OBSIDIAN", "§5Crying Obsidian", "150", "1"}};
            case "gear", "crystal" -> new String[][]{
                    {"OBSIDIAN", "§5Obsidian", "100", "1"},
                    {"END_CRYSTAL", "§dEnd Crystal", "250", "1"},
                    {"RESPAWN_ANCHOR", "§5Respawn Anchor", "500", "1"},
                    {"GLOWSTONE", "§eGlowstone", "100", "1"},
                    {"TOTEM_OF_UNDYING", "§6Totem of Undying", "1500", "1"},
                    {"ENDER_PEARL", "§3Ender Pearl", "75", "1"},
                    {"GOLDEN_APPLE", "§6Golden Apple", "250", "1"},
                    {"EXPERIENCE_BOTTLE", "§aBottle o' Enchanting", "100", "1"},
                    {"ARROW_OF_SLOW_FALLING", "§fArrow of Slow Falling", "500", "1"}};
            case "food" -> new String[][]{
                    {"POTATO", "§ePotato", "96", "1"},
                    {"SWEET_BERRIES", "§cSweet Berries", "50", "1"},
                    {"MELON_SLICE", "§cMelon Slice", "36", "1"},
                    {"CARROT", "§6Carrot", "96", "1"},
                    {"APPLE", "§cApple", "25", "1"},
                    {"COOKED_CHICKEN", "§eCooked Chicken", "48", "1"},
                    {"COOKED_BEEF", "§aSteak", "35", "1"},
                    {"GOLDEN_CARROT", "§6Golden Carrot", "120", "1"},
                    {"GOLDEN_APPLE", "§6Golden Apple", "250", "1"}};
            case "shards" -> new String[0][];
            default -> new String[0][];
        };
    }
    private String currentPlaytime(Object player) {
        try { long seconds = db.playtime(uuid(player)); return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m"; }
        catch (Exception e) { return "0h 0m"; }
    }

    private String categoryKey(String category) {
        String c = category.toLowerCase(Locale.ROOT).replace("§8shop • ", "").replace("§8haven shop: ", "").trim();
        if (c.contains("nether")) return "nether";
        if (c.equals("gear") || c.contains("crystal") || c.contains("pvp") || c.contains("totem")) return "gear";
        if (c.contains("food") || c.contains("farm")) return "food";
        if (c.contains("end")) return "end";
        if (c.contains("haven tools") || c.contains("tools") || c.equals("amethysttools")) return "tools";
        if (c.equals("spawners") || c.contains("spawner")) return "spawners";
        if (c.contains("shard")) return "shards";
        return c;
    }
    private String shopTitle(String category) {
        return "§8SHOP • " + switch (categoryKey(category)) {
            case "nether" -> "Nether"; case "gear", "crystal" -> "Gear"; case "food" -> "Food";
            case "end" -> "End"; case "shards" -> "Shards"; default -> "Market";
        };
    }
    private long shopPrice(String material, long fallback) { return configLong("shop.prices." + material.toLowerCase(Locale.ROOT), fallback); }
    private int shopAmount(String material, int fallback) { return Math.max(1, Math.min(64, configInt("shop.amounts." + material.toLowerCase(Locale.ROOT), fallback))); }
    private long shardBalance(Object player) { try { return db.shardBalance(uuid(player)); } catch (Exception e) { return 0; } }
    private int keys(UUID id, String crate) { try { return db.keys(id, crate); } catch (Exception e) { return 0; } }
    private String safeRank(Object player) { try { return db.rank(uuid(player)); } catch (Exception e) { return "Member"; } }
    private String safePrefix(Object player) { try { return db.rankPrefix(safeRank(player)); } catch (Exception e) { return "§7[Member]"; } }
    private UUID uuid(Object player) { return (UUID) Reflect.call(player, "getUniqueId"); }
    private int configInt(String path, int fallback) { try { return ((Number) Reflect.call(Reflect.call(plugin, "getConfig"), "getInt", path, fallback)).intValue(); } catch (Exception e) { return fallback; } }
    private long configLong(String path, long fallback) { try { return ((Number) Reflect.call(Reflect.call(plugin, "getConfig"), "getLong", path, fallback)).longValue(); } catch (Exception e) { return fallback; } }
    private String configString(String path, String fallback) { try { Object x = Reflect.call(Reflect.call(plugin, "getConfig"), "getString", path, fallback); return x == null ? fallback : String.valueOf(x); } catch (Exception e) { return fallback; } }
    private void fill(Object inv, int size) { for (int i = 0; i < size; i++) item(inv, i, "GRAY_STAINED_GLASS_PANE", "§r", List.of()); }
    private Object inventory(String title, int size) { return Reflect.callStatic("org.bukkit.Bukkit", "createInventory", null, size, title); }
    private void item(Object inv, int slot, String material, String name, List<String> lore) {
        try {
            Object stack = Reflect.construct("org.bukkit.inventory.ItemStack", Reflect.enumValue("org.bukkit.Material", material));
            Object meta = Reflect.call(stack, "getItemMeta");
            Reflect.call(meta, "setDisplayName", name); Reflect.call(meta, "setLore", lore);
            Reflect.call(stack, "setItemMeta", meta); Reflect.call(inv, "setItem", slot, stack);
        } catch (Exception ignored) { }
    }
    private Object arrayOf(String type, Object item) { Object a = Array.newInstance(Reflect.type(type), 1); Array.set(a, 0, item); return a; }
    private void open(Object player, Object inv) { Reflect.call(player, "openInventory", inv); }
    private int onlineCount() { Object players = Reflect.callStatic("org.bukkit.Bukkit", "getOnlinePlayers"); return players instanceof Collection<?> c ? c.size() : 0; }
    private boolean hasAdmin(Object sender) { try { return db.staffLevel(db.rank(uuid(sender))) >= 2; } catch (Exception e) { return false; } }
    private void broadcast(String message) { Reflect.callStatic("org.bukkit.Bukkit", "broadcastMessage", message); }
    private List<String> topHeartLore() {
        List<String> lore = new ArrayList<>();
        try { int i = 1; for (String[] row : life.top(5)) lore.add("§e" + i++ + ". §f" + row[2] + " §c" + row[1] + "❤"); }
        catch (Exception ignored) { }
        if (lore.isEmpty()) lore.add("§7Chưa có dữ liệu"); return lore;
    }
}
