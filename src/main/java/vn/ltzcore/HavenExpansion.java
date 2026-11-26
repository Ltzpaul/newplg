package vn.ltzcore;

import vn.ltzcore.core.Reflect;
import vn.ltzcore.economy.EconomyManager;
import vn.ltzcore.storage.Database;
import vn.ltzcore.util.MoneyFormat;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.ThreadLocalRandom;

/** Optional Haven SMP gameplay systems: stacked spawners, AH, orders and 3x3 mining tools. */
public final class HavenExpansion {
    private static final String SPAWNER_GUI = "§8Haven Spawner";
    private static final String STORAGE_GUI = "§8Spawner Storage";
    private static final String SPAWNER_SHOP = "§8Spawner Market";
    private static final String AH_GUI = "§8Haven Auction House";
    private static final String ORDERS_GUI = "§8Haven Orders";
    private static final String ORDER_SELECT_GUI = "§8Create Buy Order";
    private static final List<String> ORDER_MATERIALS = List.of(
            "DIAMOND", "EMERALD", "IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT", "NETHERITE_SCRAP", "AMETHYST_SHARD", "QUARTZ",
            "COAL", "REDSTONE", "LAPIS_LAZULI", "GLOWSTONE_DUST", "BLAZE_ROD", "ENDER_PEARL", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS",
            "GUNPOWDER", "STRING", "BONE", "ROTTEN_FLESH", "ARROW", "LEATHER", "SLIME_BALL", "MAGMA_CREAM",
            "WHEAT", "CARROT", "POTATO", "SUGAR_CANE", "BAMBOO", "PUMPKIN", "MELON", "HONEY_BOTTLE",
            "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "DARK_OAK_LOG", "CHERRY_LOG", "COBBLESTONE", "STONE", "OBSIDIAN",
            "GLASS", "SAND", "GRAVEL", "CLAY_BALL", "SHULKER_SHELL", "TOTEM_OF_UNDYING", "EXPERIENCE_BOTTLE", "GOLDEN_APPLE",
            "ENDER_CHEST", "BOOK", "ENCHANTED_BOOK", "FIREWORK_ROCKET", "ELYTRA", "HEART_OF_THE_SEA", "NAUTILUS_SHELL", "TRIDENT"
    );
    private static final String SPAWNER_MARKER = "§0HAVEN_SPAWNER:";
    private static final String TOOL_MARKER = "§0HAVEN_TOOL:";
    private static final int MAX_SPAWNER_STACK = 258;
    private int maxSpawnerStack() { return clampInt(String.valueOf(configInt("spawners.max-stack", MAX_SPAWNER_STACK)), 1, MAX_SPAWNER_STACK, MAX_SPAWNER_STACK); }
    private final Object plugin;
    private final Database db;
    private final EconomyManager economy;
    private final Predicate<Object> isAdmin;
    private final Predicate<Object> isProtected;
    private final Map<UUID, String> openSpawner = new HashMap<>();
    private final Map<UUID, Integer> storagePages = new HashMap<>();
    private final Map<UUID, List<String[]>> menuListings = new HashMap<>();
    private final Map<UUID, Integer> menuPage = new HashMap<>();
    private final Set<UUID> auctionMineView = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<String>> orderMaterialPages = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Integer> orderMaterialPage = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, OrderDraft> orderDrafts = new java.util.concurrent.ConcurrentHashMap<>();
    private record OrderDraft(String material, String stage, int amount) {}

    public HavenExpansion(Object plugin, Database db, EconomyManager economy,
                          Predicate<Object> isAdmin, Predicate<Object> isProtected) {
        this.plugin = plugin; this.db = db; this.economy = economy;
        this.isAdmin = isAdmin; this.isProtected = isProtected;
    }

    public void registerListeners() {
        listen("org.bukkit.event.block.BlockPlaceEvent", this::onSpawnerPlace);
        listen("org.bukkit.event.block.BlockBreakEvent", this::onBlockBreak);
        listen("org.bukkit.event.player.PlayerInteractEvent", this::onSpawnerInteract);
        try { Reflect.registerEvent(plugin, "org.bukkit.event.entity.SpawnerSpawnEvent", this::onSpawnerSpawn, "HIGHEST", false); }
        catch (Exception ex) { log("Không đăng ký được SpawnerSpawnEvent: " + ex.getMessage()); }
        if (configBoolean("spawners.enabled", true)) {
            long interval = Math.max(200L, Math.min(12_000L, configInt("spawners.production.interval-ticks", 400)));
            Reflect.scheduleRepeating(plugin, this::produceSpawnerCycle, interval, interval);
        }
        Reflect.scheduleRepeating(plugin, () -> {
            try { db.expireOrders(); }
            catch (Exception ex) { log("Order expiry/refund: " + ex.getMessage()); }
        }, 1200L, 1200L);
        listen("org.bukkit.event.inventory.InventoryClickEvent", this::onInventoryClick);
        listen("org.bukkit.event.inventory.InventoryDragEvent", this::onInventoryDrag);
        try { Reflect.registerEvent(plugin, "io.papermc.paper.event.player.AsyncChatEvent", this::onOrderChat, "HIGHEST", false); }
        catch (Exception ex) { log("Không đăng ký được AsyncChatEvent cho Orders: " + ex.getMessage()); }
        listen("org.bukkit.event.player.PlayerQuitEvent", event -> { try { UUID id=uuid(Reflect.call(event,"getPlayer")); orderDrafts.remove(id); orderMaterialPages.remove(id); orderMaterialPage.remove(id); auctionMineView.remove(id); menuListings.remove(id); menuPage.remove(id); } catch(Exception ignored) {} });
    }

    private void listen(String event, java.util.function.Consumer<Object> consumer) {
        try { Reflect.registerEvent(plugin, event, consumer); }
        catch (Exception ex) { log("Không đăng ký được " + event + ": " + ex.getMessage()); }
    }

    public boolean command(Object sender, String root, String[] args) {
        try {
            return switch (root.toLowerCase(Locale.ROOT)) {
                case "spawner" -> spawnerCommand(sender, args);
                case "ah" -> auctionCommand(sender, args);
                case "orders" -> { requirePlayer(sender); openOrders(sender, 0); yield true; }
                case "order" -> orderCommand(sender, args);
                case "tool" -> toolCommand(sender, args);
                default -> false;
            };
        } catch (Exception ex) {
            log("Lệnh /" + root + " lỗi: " + ex.getMessage());
            tell(sender, "§cKhông thể thực hiện lệnh. Hãy kiểm tra console.");
            return true;
        }
    }

    // -------------------- Stacked mob spawners --------------------
    private boolean spawnerCommand(Object sender, String[] args) throws Exception {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            tell(sender, "§e/spawner §7- thông tin spawner | §e/spawner shop §7- cửa hàng spawner");
            tell(sender, "§e/spawner give <player> <mob> [amount] §7- cấp spawner (staff)");
            tell(sender, "§e/spawner info §7- xem thông tin spawner đang nhìn");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "shop" -> { requirePlayer(sender); openSpawnerShop(sender); return true; }
            case "give" -> {
                if (!isAdmin.test(sender)) { tell(sender, "§cUnknown command."); return true; }
                if (args.length < 3) { tell(sender, "§e/spawner give <player> <mob> [amount]"); return true; }
                Object target = Reflect.callStatic("org.bukkit.Bukkit", "getPlayerExact", args[1]);
                if (target == null) { tell(sender, "§cNgười chơi không online."); return true; }
                String mob = normalizeMob(args[2]); int amount = args.length > 3 ? clampInt(args[3], 1, MAX_SPAWNER_STACK, 1) : 1;
                giveSpawner(target, mob, amount); tell(sender, "§aĐã cấp " + amount + " spawner " + mob + " cho " + name(target) + "."); return true;
            }
            case "info" -> { requirePlayer(sender); inspectTargetSpawner(sender); return true; }
            case "reload" -> { if (!isAdmin.test(sender)) tell(sender, "§cUnknown command."); else tell(sender, "§aCấu hình spawner được đọc trực tiếp từ config; dùng /ltzcore reload để nạp lại."); return true; }
            default -> { tell(sender, "§e/spawner [shop|info] §7hoặc /spawner give <player> <mob> [amount]"); return true; }
        }
    }

    private void onSpawnerPlace(Object event) {
        try {
            if (isCancelled(event)) return;
            Object item = Reflect.call(event, "getItemInHand");
            String mob = spawnerMob(item);
            if (mob == null) return;
            Object block = Reflect.call(event, "getBlockPlaced");
            Object player = Reflect.call(event, "getPlayer");
            Key key = key(block);
            int stack = 1;
            // Vanilla placement consumes one item. Remaining stack stays in the player's hand.
            Reflect.scheduleLater(plugin, () -> {
                try {
                    setSpawnerMob(block, mob);
                    db.saveSpawner(key.world, key.x, key.y, key.z, mob, stack, uuid(player).toString());
                    updateSpawnerSettings(block, stack);
                } catch (Exception ex) { log("Không lưu được spawner đặt mới: " + ex.getMessage()); }
            }, 1L);
        } catch (Exception ex) { log("Spawner place handler: " + ex.getMessage()); }
    }

    private void onSpawnerInteract(Object event) {
        try {
            if (!String.valueOf(Reflect.call(event, "getAction")).contains("RIGHT_CLICK_BLOCK")) return;
            Object block = Reflect.call(event, "getClickedBlock");
            if (block == null || !"SPAWNER".equals(String.valueOf(Reflect.call(block, "getType")))) return;
            Object player = Reflect.call(event, "getPlayer");
            if (!isAuthenticated(player)) return;
            Object hand = Reflect.call(player, "getInventory");
            Object held = Reflect.call(hand, "getItemInMainHand");
            String heldMob = spawnerMob(held);
            Key k = key(block);
            String[] row = db.spawner(k.world, k.x, k.y, k.z);
            if (heldMob != null) {
                Reflect.call(event, "setCancelled", true);
                if (row == null) {
                    Object state = Reflect.call(block, "getState");
                    String current = String.valueOf(Reflect.call(Reflect.call(state, "getSpawnedType"), "name"));
                    db.saveSpawner(k.world, k.x, k.y, k.z, current, 1, uuid(player).toString());
                    row = db.spawner(k.world, k.x, k.y, k.z);
                }
                if (!heldMob.equalsIgnoreCase(row[0])) { tell(player, "§cLoại spawner không khớp. Spawner này là " + row[0] + "."); return; }
                                int itemAmount = Math.max(1, ((Number) Reflect.call(held, "getAmount")).intValue());
                int current = Integer.parseInt(row[1]);
                int stackLimit = maxSpawnerStack();
                boolean shiftStack = Boolean.TRUE.equals(Reflect.call(player, "isSneaking"));
                int requested = shiftStack ? itemAmount : 1;
                int accepted = Math.min(requested, stackLimit - current);
                if (accepted <= 0) { tell(player, "§cSpawner đã đạt giới hạn stack " + stackLimit + "."); return; }
                db.changeSpawnerStack(k.world, k.x, k.y, k.z, accepted);
                Reflect.call(held, "setAmount", itemAmount - accepted);
                if (itemAmount == accepted) Reflect.call(hand, "setItemInMainHand", airItem());
                updateSpawnerSettings(block, current + accepted);
                tell(player, "§aĐã gộp " + accepted + " spawner. Stack hiện tại: §e" + (current + accepted) + "x§a." + (shiftStack ? "" : " §7Giữ Shift + chuột phải để gộp cả stack trên tay."));
                return;
            }
            Reflect.call(event, "setCancelled", true);
            if (row == null) {
                Object state = Reflect.call(block, "getState");
                String mob = String.valueOf(Reflect.call(Reflect.call(state, "getSpawnedType"), "name"));
                db.saveSpawner(k.world, k.x, k.y, k.z, mob, 1, uuid(player).toString());
                row = db.spawner(k.world, k.x, k.y, k.z);
            }
                        openSpawner.put(uuid(player), k.encode());
            openSpawnerGui(player, row);
        } catch (Exception ex) { log("Spawner interact: " + ex.getMessage()); }
    }

    /** Prevent real entities from spawning from Haven-managed spawners. Production is virtualized below. */
    private void onSpawnerSpawn(Object event) {
        try {
            if (!configBoolean("spawners.enabled", true)) return;
            Object state = Reflect.call(event, "getSpawner");
            if (state == null) return; // e.g. minecart spawner; leave it vanilla.
            Key k = keyFromLocation(Reflect.call(state, "getLocation"));
            if (db.spawner(k.world, k.x, k.y, k.z) != null) Reflect.call(event, "setCancelled", true);
        } catch (Exception ex) { log("Spawner spawn interception: " + ex.getMessage()); }
    }

    /** One shared timer simulates spawner output; no live mobs are created. */
    private void produceSpawnerCycle() {
        try {
            List<String[]> rows = db.allSpawners(clampInt(String.valueOf(configInt("spawners.production.max-per-cycle", 100)), 1, 500, 100));
            int activationRange = clampInt(String.valueOf(configInt("spawners.production.activation-range", 32)), 8, 64, 32);
            double activationSquared = (double) activationRange * activationRange;
            int xpPerMob = clampInt(String.valueOf(configInt("spawners.production.xp-per-mob", 5)), 0, 100, 5);
            int maxLoot = clampInt(String.valueOf(configInt("spawners.max-storage-per-material", 100_000)), 1, 2_000_000, 100_000);
            int maxXp = clampInt(String.valueOf(configInt("spawners.max-stored-xp", 1_000_000)), 1, 10_000_000, 1_000_000);
            for (String[] row : rows) {
                String worldName=row[0]; int x=Integer.parseInt(row[1]), y=Integer.parseInt(row[2]), z=Integer.parseInt(row[3]);
                Object world=Reflect.callStatic("org.bukkit.Bukkit","getWorld",worldName); if(world==null)continue;
                if(!Boolean.TRUE.equals(Reflect.call(world,"isChunkLoaded",Math.floorDiv(x,16),Math.floorDiv(z,16))))continue;
                Object block=Reflect.call(world,"getBlockAt",x,y,z);
                if(!"SPAWNER".equals(String.valueOf(Reflect.call(block,"getType"))))continue;
                Object location=Reflect.call(block,"getLocation"); boolean nearby=false;
                Object players=Reflect.call(world,"getPlayers");
                if(players instanceof Iterable<?> iterable) for(Object player:iterable) {
                    Object playerLocation=Reflect.call(player,"getLocation");
                    double distance=((Number)Reflect.call(playerLocation,"distanceSquared",location)).doubleValue();
                    if(distance<=activationSquared){nearby=true;break;}
                }
                if(!nearby)continue;
                String mob=row[4].toUpperCase(Locale.ROOT); int stack=clampInt(row[5],1,maxSpawnerStack(),1);
                Map<String,Integer> drops=simulateDrops(mob,stack);
                for(Map.Entry<String,Integer> entry:drops.entrySet())db.addSpawnerLoot(worldName,x,y,z,entry.getKey(),entry.getValue(),maxLoot);
                if(xpPerMob>0)db.addSpawnerXp(worldName,x,y,z,(int)Math.min((long)maxXp,(long)xpPerMob*stack),maxXp);
            }
        } catch (Exception ex) { log("Spawner production cycle: " + ex.getMessage()); }
    }

    private Map<String,Integer> simulateDrops(String mob,int stack) {
        Map<String,Integer> drops=new LinkedHashMap<>();
        switch(mob) {
            case "ZOMBIE" -> { addRandomDrop(drops,"ROTTEN_FLESH",stack,1,2); addChanceDrop(drops,"CARROT",stack,1,0.025); addChanceDrop(drops,"POTATO",stack,1,0.025); }
            case "SKELETON" -> { addRandomDrop(drops,"BONE",stack,1,2); addRandomDrop(drops,"ARROW",stack,1,2); }
            case "SPIDER" -> { addRandomDrop(drops,"STRING",stack,1,2); addChanceDrop(drops,"SPIDER_EYE",stack,1,0.30); }
            case "CAVE_SPIDER" -> { addRandomDrop(drops,"STRING",stack,1,2); addChanceDrop(drops,"SPIDER_EYE",stack,1,0.35); }
            case "CREEPER" -> addRandomDrop(drops,"GUNPOWDER",stack,1,2);
            case "BLAZE" -> addRandomDrop(drops,"BLAZE_ROD",stack,0,1);
            case "COW" -> { addRandomDrop(drops,"BEEF",stack,1,3); addRandomDrop(drops,"LEATHER",stack,0,2); }
            case "PIG" -> addRandomDrop(drops,"PORKCHOP",stack,1,3);
            case "IRON_GOLEM" -> { addRandomDrop(drops,"IRON_INGOT",stack,2,4); addRandomDrop(drops,"POPPY",stack,0,2); }
            case "ENDERMAN" -> addRandomDrop(drops,"ENDER_PEARL",stack,0,1);
            case "WITCH" -> { addRandomDrop(drops,"REDSTONE",stack,0,2); addChanceDrop(drops,"GLOWSTONE_DUST",stack,1,0.25); addChanceDrop(drops,"SUGAR",stack,1,0.25); }
            case "SLIME" -> addRandomDrop(drops,"SLIME_BALL",stack,1,3);
            default -> { /* Unsupported mob types intentionally produce no virtual loot. */ }
        }
        return drops;
    }

    private void addRandomDrop(Map<String,Integer> drops,String material,int stack,int min,int max) {
        if(max<=0||stack<=0)return;int total=0;ThreadLocalRandom random=ThreadLocalRandom.current();
        for(int i=0;i<stack;i++)total+=random.nextInt(min,max+1);
        if(total>0)drops.merge(material,total,Integer::sum);
    }
    private void addChanceDrop(Map<String,Integer> drops,String material,int stack,int amount,double chance) {
        int total=0;ThreadLocalRandom random=ThreadLocalRandom.current();
        for(int i=0;i<stack;i++)if(random.nextDouble()<chance)total+=amount;
        if(total>0)drops.merge(material,total,Integer::sum);
    }

    private void onSpawnerBlockBreak(Object event) {
        try {
            if (isCancelled(event)) return;
            Object block = Reflect.call(event, "getBlock");
            if (!"SPAWNER".equals(String.valueOf(Reflect.call(block, "getType")))) return;
            Object player = Reflect.call(event, "getPlayer");
            Key k = key(block);
            String[] row = db.spawner(k.world, k.x, k.y, k.z);
            if (row == null) return;

            Object tool = Reflect.call(Reflect.call(player, "getInventory"), "getItemInMainHand");
            boolean silk = String.valueOf(Reflect.call(tool, "getEnchantments")).toUpperCase(Locale.ROOT).contains("SILK_TOUCH");
            if (configBoolean("spawners.require-silk-touch", true) && !silk && !isAdmin.test(player)) {
                Reflect.call(event, "setCancelled", true);
                tell(player, "§cCần Silk Touch để thu spawner stack.");
                return;
            }

            Reflect.call(event, "setCancelled", true);
            int stack = Math.max(1, Integer.parseInt(row[1]));
            boolean takeAll = Boolean.TRUE.equals(Reflect.call(player, "isSneaking"));
            int extracted = takeAll ? stack : 1;
            boolean removingBlock = extracted >= stack;

            // The stored virtual contents are safely paid out when the final spawner is removed.
            if (removingBlock) deliverSpawnerContents(player, k);

            if (removingBlock) {
                db.deleteSpawner(k.world, k.x, k.y, k.z);
                Reflect.call(block, "setType", Reflect.enumValue("org.bukkit.Material", "AIR"));
            } else {
                db.changeSpawnerStack(k.world, k.x, k.y, k.z, -extracted);
            }
            giveSpawner(player, row[0], extracted);
            if (removingBlock) {
                tell(player, "§aĐã thu toàn bộ §e" + extracted + "x " + pretty(row[0]) + " Spawner§a. Loot và EXP đã được trả về.");
            } else {
                tell(player, "§aĐã thu 1 spawner. Còn lại §e" + (stack - 1) + "x§a. §7Giữ Shift để thu toàn bộ stack.");
            }
        } catch (Exception ex) { log("Spawner break: " + ex.getMessage()); }
    }

    private void deliverSpawnerContents(Object player, Key key) throws Exception {
        for (String[] entry : db.spawnerLoot(key.world, key.x, key.y, key.z)) {
            int amount = db.takeSpawnerLoot(key.world, key.x, key.y, key.z, entry[0]);
            if (amount > 0) giveMaterial(player, entry[0], amount);
        }
        int xp = db.takeSpawnerXp(key.world, key.x, key.y, key.z);
        if (xp > 0) Reflect.call(player, "giveExp", xp);
    }

    private void openSpawnerGui(Object player, String[] row) {
        Object inv = inventory(SPAWNER_GUI, 27); fill(inv, 27);
        item(inv, 11, "CHEST", "§6Spawner Storage", List.of("§7Mở kho đồ thu được", "§eNhấn để xem"));
        item(inv, 13, "SPAWNER", "§e" + pretty(row[0]) + " Spawner §fx" + row[1], List.of("§7Quyền: §aCông khai (ai cũng mở & đập được)", "§7Stack: §e" + row[1] + "§7/§e" + maxSpawnerStack(), "§7Sản lượng ảo khi có người gần spawner", "§aKhông spawn mob thật", "§eĐập thường: lấy 1 spawner", "§eGiữ Shift + đập: lấy toàn bộ stack", "§7Khi lấy spawner cuối, loot + EXP được trả về."));
        item(inv, 15, "EXPERIENCE_BOTTLE", "§aCollect EXP", List.of("§7Đang lưu: §e" + row[3] + " XP", "§eNhấn để nhận XP"));
        item(inv, 22, "GOLD_INGOT", "§6Sell All", List.of("§7Bán toàn bộ loot trong kho", "§eNhấn để bán"));
        item(inv, 26, "BARRIER", "§cĐóng", List.of());
        Reflect.call(player, "openInventory", inv);
    }

    private void openStorage(Object player, int page) throws Exception {
        String encoded = openSpawner.get(uuid(player)); if (encoded == null) return;
        Key k = Key.decode(encoded); List<String[]> items = db.spawnerLoot(k.world, k.x, k.y, k.z);
        int maxPage = Math.max(1, (items.size() + 44) / 45); int p = Math.max(0, Math.min(maxPage - 1, page));
        storagePages.put(uuid(player), p);
        Object inv = inventory(STORAGE_GUI, 54); fill(inv, 54);
        int start = p * 45;
        for (int i = start; i < Math.min(items.size(), start + 45); i++) {
            String[] entry = items.get(i); int slot = i - start;
            item(inv, slot, entry[0], "§e" + pretty(entry[0]) + " x" + entry[1], List.of("§7Nhấn để nhận vật phẩm này", "§8MATERIAL=" + entry[0]));
        }
        item(inv, 45, "ARROW", "§eQuay lại", List.of());
        item(inv, 47, "ARROW", "§eTrang trước", List.of("§7" + (p + 1) + "/" + maxPage));
        item(inv, 49, "HOPPER", "§aCollect All", List.of("§7Nhận toàn bộ vật phẩm"));
        item(inv, 51, "GOLD_INGOT", "§6Sell All", List.of("§7Bán toàn bộ vật phẩm"));
        item(inv, 53, "ARROW", "§eTrang tiếp", List.of("§7" + (p + 1) + "/" + maxPage));
        Reflect.call(player, "openInventory", inv);
    }

    private void collectSpawnerXp(Object player) throws Exception {
        Key k = Key.decode(openSpawner.get(uuid(player))); int xp = db.takeSpawnerXp(k.world, k.x, k.y, k.z);
        if (xp <= 0) { tell(player, "§cChưa có EXP trong spawner."); return; }
        Reflect.call(player, "giveExp", xp); tell(player, "§aBạn đã nhận §e" + xp + " XP§a.");
        String[] row = db.spawner(k.world, k.x, k.y, k.z); if (row != null) openSpawnerGui(player, row);
    }

    private void collectStorageItem(Object player, int slot) throws Exception {
        Key k = Key.decode(openSpawner.get(uuid(player))); int page = storagePages.getOrDefault(uuid(player), 0);
        List<String[]> list = db.spawnerLoot(k.world, k.x, k.y, k.z); int index = page * 45 + slot;
        if (index < 0 || index >= list.size()) return;
        String material = list.get(index)[0]; int amount = db.takeSpawnerLoot(k.world, k.x, k.y, k.z, material);
        if (amount <= 0) return;
        int remaining = addMaterialToInventory(player, material, amount);
        if (remaining > 0) {
            db.addSpawnerLoot(k.world, k.x, k.y, k.z, material, remaining, configInt("spawners.max-storage-per-material", 4096));
            tell(player, "§eTúi đồ gần đầy. Còn " + remaining + "x " + pretty(material) + " được giữ lại trong kho spawner.");
        }
        openStorage(player, page);
    }

    private void collectAll(Object player, boolean sell) throws Exception {
        Key k = Key.decode(openSpawner.get(uuid(player))); List<String[]> list = db.spawnerLoot(k.world, k.x, k.y, k.z);
        long total = 0; int items = 0;
        for (String[] row : list) {
            String material = row[0]; int amount = db.takeSpawnerLoot(k.world, k.x, k.y, k.z, material);
            if (amount <= 0) continue;
            if (sell) {
                total = Math.addExact(total, Math.multiplyExact((long) amount, spawnerPrice(material)));
                items += amount;
            } else {
                int remaining = addMaterialToInventory(player, material, amount);
                items += amount - remaining;
                if (remaining > 0) db.addSpawnerLoot(k.world, k.x, k.y, k.z, material, remaining, configInt("spawners.max-storage-per-material", 4096));
            }
        }
        if (sell && total > 0) economy.deposit(uuid(player), total, "SPAWNER_SELL");
        tell(player, sell ? "§aĐã bán " + items + " vật phẩm nhận §6$" + MoneyFormat.compact(total) : "§aĐã thu " + items + " vật phẩm từ spawner.");
        openStorage(player, 0);
    }

    private static final String[] SHOP_SPAWNER_MOBS = {
            "ZOMBIE", "SKELETON", "SPIDER", "CAVE_SPIDER", "CREEPER", "BLAZE",
            "COW", "PIG", "IRON_GOLEM", "ENDERMAN", "WITCH", "SLIME"
    };
    private static final int[] SHOP_SPAWNER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 17, 19, 20, 21, 22};

    private void openSpawnerShop(Object player) {
        Object inv = inventory(SPAWNER_SHOP, 54); fill(inv, 54);
        long price = Math.max(1, configLong("spawners.price-shards-per-spawner", 800));
        long balance = 0;
        try { balance = db.shardBalance(uuid(player)); } catch (Exception ignored) { }
        for (int i = 0; i < SHOP_SPAWNER_MOBS.length; i++) {
            String mob = SHOP_SPAWNER_MOBS[i];
            item(inv, SHOP_SPAWNER_SLOTS[i], "SPAWNER", "§d" + pretty(mob) + " Spawner",
                    List.of("§7Giá: §d" + MoneyFormat.compact(price) + " Shard", "§7Mỗi loại spawner cùng giá", "§7Bạn có: §d" + MoneyFormat.compact(balance) + " shard", "§eNhấn trái để mua 1"));
        }
        item(inv, 45, "ARROW", "§eQuay lại Shop", List.of());
        item(inv, 49, "AMETHYST_SHARD", "§dVí Shard", List.of("§7Số dư: §d" + MoneyFormat.compact(balance), "§7Dùng /shard để xem số dư"));
        item(inv, 53, "BARRIER", "§cĐóng", List.of());
        Reflect.call(player, "openInventory", inv);
    }

    private void buySpawner(Object player, int slot) {
        int index = -1;
        for (int i = 0; i < SHOP_SPAWNER_SLOTS.length; i++) if (SHOP_SPAWNER_SLOTS[i] == slot) index = i;
        if (index < 0) return;
        String mob = SHOP_SPAWNER_MOBS[index];
        long price = Math.max(1, configLong("spawners.price-shards-per-spawner", 800));
        UUID buyer = uuid(player);
        boolean spent = false;
        try {
            if (!db.spendShards(buyer, price, "SPAWNER_SHOP:" + mob)) {
                tell(player, "§cMỗi spawner có giá §d" + price + " shard§c. Bạn không đủ shard.");
                return;
            }
            spent = true;
            if (!giveSpawner(player, mob, 1)) {
                db.addShards(buyer, price, "SPAWNER_SHOP_REFUND");
                tell(player, "§cKhông giao được spawner; shard đã được hoàn lại.");
                return;
            }
            tell(player, "§aĐã mua 1 " + pretty(mob) + " Spawner với §d" + price + " shard§a.");
            openSpawnerShop(player);
        } catch (Exception ex) {
            if (spent) try { db.addShards(buyer, price, "SPAWNER_SHOP_ERROR_REFUND"); } catch (Exception ignored) { }
            tell(player, "§cGiao dịch spawner thất bại. Hãy kiểm tra số dư hoặc báo Admin.");
            log("Spawner purchase: " + ex.getMessage());
        }
    }

    private void inspectTargetSpawner(Object player) {
        try {
            Object block = Reflect.call(player,"getTargetBlockExact",6);
            if(block==null||!"SPAWNER".equals(String.valueOf(Reflect.call(block,"getType")))){tell(player,"§cHãy nhìn vào một spawner trong phạm vi 6 block.");return;}
            Key k=key(block);String[] row=db.spawner(k.world,k.x,k.y,k.z);if(row==null){tell(player,"§7Spawner vanilla chưa được Haven quản lý; hãy nhấp chuột phải để mở GUI.");return;}
            tell(player,"§eSpawner: §f"+pretty(row[0])+" §ex"+row[1]+"§7/§e"+maxSpawnerStack()+" §7| Công khai §7| XP: §e"+row[3]);
        }catch(Exception ex){tell(player,"§cKhông đọc được spawner mục tiêu.");}
    }

    private void setSpawnerMob(Object block,String mob) {
        try { Object state=Reflect.call(block,"getState");Object type=Reflect.enumValue("org.bukkit.entity.EntityType",mob);Reflect.call(state,"setSpawnedType",type);Reflect.call(state,"update",true,false); } catch(Exception ex){log("Không set được mob spawner "+mob+": "+ex.getMessage());}
    }
    private void updateSpawnerSettings(Object block,int stack) {
        try { Object state=Reflect.call(block,"getState");int base=configInt("spawners.base-spawn-count",4);int count=Math.min(16,Math.max(1,base));Reflect.call(state,"setSpawnCount",count);Reflect.call(state,"update",true,false); } catch(Exception ignored){}
    }
    private boolean giveSpawner(Object player,String mob,int amount) {
        try {
            int remaining=Math.max(1,Math.min(MAX_SPAWNER_STACK,amount));
            Object inv=Reflect.call(player,"getInventory");
            Object loc=Reflect.call(player,"getLocation");
            Object world=Reflect.call(loc,"getWorld");
            while(remaining>0) {
                int part=Math.min(64,remaining);
                Object stack=Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material","SPAWNER"),part);
                Object meta=Reflect.call(stack,"getItemMeta");Reflect.call(meta,"setDisplayName","§dHaven " + pretty(mob) + " Spawner");
                Reflect.call(meta,"setLore",List.of("§7Stackable Mob Spawner (Tối đa " + maxSpawnerStack() + "x)","§7Đặt xuống rồi nhấp phải để quản lý",SPAWNER_MARKER+mob));Reflect.call(stack,"setItemMeta",meta);
                Object leftovers=Reflect.call(inv,"addItem",arrayOf("org.bukkit.inventory.ItemStack",stack));
                if(leftovers instanceof Map<?,?> map&&!map.isEmpty()){for(Object left:map.values())Reflect.call(world,"dropItemNaturally",loc,left);}
                remaining-=part;
            }
            return true;
        }catch(Exception ex){return false;}
    }
    private String spawnerMob(Object item) {
        try { if(item==null||!"SPAWNER".equals(String.valueOf(Reflect.call(item,"getType"))))return null;Object meta=Reflect.call(item,"getItemMeta");Object lore=Reflect.call(meta,"getLore");if(lore instanceof List<?> list)for(Object line:list){String s=String.valueOf(line);if(s.startsWith(SPAWNER_MARKER))return normalizeMob(s.substring(SPAWNER_MARKER.length()));} }catch(Exception ignored){}return null;
    }
    private boolean canAccess(Object player,String owner) { return true; }
    private long defaultSpawnerPrice(String mob) { return switch(mob){case "ZOMBIE"->15000;case "SKELETON"->18000;case "SPIDER"->12000;case "CREEPER"->30000;case "BLAZE"->35000;case "IRON_GOLEM"->100000;default->10000;}; }
    private long spawnerPrice(String material) { return configLong("spawners.sell-prices."+material.toLowerCase(Locale.ROOT),1L); }

    // -------------------- 3x3 mining tools --------------------
    private boolean toolCommand(Object sender,String[] args) {
        if(!isAdmin.test(sender)){tell(sender,"§cUnknown command.");return true;}
        if(args.length<3||!args[0].equalsIgnoreCase("give")){tell(sender,"§e/tool give <player> <pickaxe3x3|shovel3x3|chopper>");return true;}
        Object target=Reflect.callStatic("org.bukkit.Bukkit","getPlayerExact",args[1]);if(target==null){tell(sender,"§cNgười chơi không online.");return true;}
        String kind=args[2].toLowerCase(Locale.ROOT);
        if(kind.equals("axe")||kind.equals("axe3x3"))kind="chopper";
        if(!Set.of("pickaxe3x3","shovel3x3","chopper").contains(kind)){tell(sender,"§cLoại tool không hợp lệ.");return true;}
        give3x3Tool(target,kind);tell(sender,"§aĐã cấp Haven Tool cho "+name(target)+".");return true;
    }

    public void purchaseHavenTool(Object player, String kind) {
        String key = kind.equalsIgnoreCase("pickaxe3x3") ? "pickaxe3x3" : kind.equalsIgnoreCase("shovel3x3") ? "shovel3x3" : "chopper";
        long fallback = key.equals("pickaxe3x3") ? 2500 : key.equals("shovel3x3") ? 2000 : 3000;
        long legacyPrice = configLong("amethyst-tools.prices-shards." + key, fallback);
        long price = Math.max(1, configLong("haven-tools.prices-shards." + key, legacyPrice));
        UUID id = uuid(player);
        boolean spent = false;
        try {
            if (!db.spendShards(id, price, "HAVEN_TOOL:" + key)) {
                tell(player, "§cBạn cần §d" + price + " shard§c để mua công cụ này.");
                return;
            }
            spent = true;
            if (!give3x3Tool(player, key)) {
                db.addShards(id, price, "HAVEN_TOOL_REFUND:" + key);
                tell(player, "§cKhông giao được công cụ; shard đã được hoàn lại.");
                return;
            }
            tell(player, "§aĐã mua Haven Tool §b" + key + " §avới giá §d" + price + " shard§a.");
        } catch (Exception ex) {
            if (spent) try { db.addShards(id, price, "HAVEN_TOOL_ERROR_REFUND:" + key); } catch (Exception ignored) { }
            tell(player, "§cGiao dịch công cụ thất bại; báo Admin nếu shard chưa được hoàn.");
            log("Haven tool purchase: " + ex.getMessage());
        }
    }


    /** Backwards-compatible alias retained for older internal callers. */
    public void purchaseAmethystTool(Object player, String kind) { purchaseHavenTool(player, kind); }

    public boolean give3x3Tool(Object player,String kind) {
        try {
            String normalized=kind.toLowerCase(Locale.ROOT);
            String material, display, marker, description;
            switch (normalized) {
                case "pickaxe3x3" -> { material="DIAMOND_PICKAXE"; display="§dAmethyst Drill §f[3x3]"; marker="PICKAXE_3X3"; description="Khai thác mọi block thông thường theo mặt 3x3"; }
                case "shovel3x3" -> { material="DIAMOND_SHOVEL"; display="§dAmethyst Excavator §f[3x3]"; marker="SHOVEL_3X3"; description="Đào đất, cát và vật liệu mềm theo mặt 3x3"; }
                case "chopper", "axe", "axe3x3" -> { material="DIAMOND_AXE"; display="§dAmethyst Chopper"; marker="AXE_CHOPPER"; description="Đốn thân cây liền khối, giới hạn cấu hình"; }
                default -> { return false; }
            }
            Object tool=Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material",material));Object meta=Reflect.call(tool,"getItemMeta");
            Reflect.call(meta,"setDisplayName",display);
            Reflect.call(meta,"setLore",List.of(
                    "§dAmethyst Tool §8• §7Công cụ đặc biệt",
                    "§7"+description,
                    "§8──────────────",
                    "§7• §bHiệu suất V §8(Efficiency V)",
                    "§7• §bBền bỉ III §8(Unbreaking III)",
                    "§7• §aSửa chữa §8(Mending)",
                    TOOL_MARKER+marker));
            Reflect.call(tool,"setItemMeta",meta);
            addEnchant(tool,"efficiency","DIG_SPEED",5);
            addEnchant(tool,"unbreaking","DURABILITY",3);
            addEnchant(tool,"mending","MENDING",1);
            Object leftovers=Reflect.call(Reflect.call(player,"getInventory"),"addItem",arrayOf("org.bukkit.inventory.ItemStack",tool));
            if(leftovers instanceof Map<?,?> map&&!map.isEmpty()){Object loc=Reflect.call(player,"getLocation");Object world=Reflect.call(loc,"getWorld");for(Object left:map.values())Reflect.call(world,"dropItemNaturally",loc,left);}return true;
        }catch(Exception ex){log("Không tạo được Amethyst Tool: "+ex.getMessage());return false;}
    }

    private void addEnchant(Object item, String modernKey, String legacyName, int level) {
        Object ench = resolveEnchant(modernKey, legacyName);
        if (ench != null) {
            try { Reflect.call(item, "addUnsafeEnchantment", ench, level); } catch (Exception ignored) { }
        }
    }

    private Object resolveEnchant(String modernKey, String legacyName) {
        try {
            Object key = Reflect.callStatic("org.bukkit.NamespacedKey", "minecraft", modernKey);
            Object reg = Reflect.fieldValue("org.bukkit.Registry", "ENCHANTMENT");
            Object found = Reflect.call(reg, "get", key);
            if (found != null) return found;
        } catch (Exception ignored) { }
        try {
            Object key = Reflect.callStatic("org.bukkit.NamespacedKey", "minecraft", modernKey);
            Object found = Reflect.callStatic("org.bukkit.enchantments.Enchantment", "getByKey", key);
            if (found != null) return found;
        } catch (Exception ignored) { }
        try {
            return Reflect.callStatic("org.bukkit.enchantments.Enchantment", "getByName", legacyName);
        } catch (Exception ignored) { return null; }
    }

    private void onBlockBreak(Object event) {
        try {
            if (isCancelled(event)) return;
            Object block=Reflect.call(event,"getBlock");
            if("SPAWNER".equals(String.valueOf(Reflect.call(block,"getType")))) { onSpawnerBlockBreak(event); return; }
            Object player=Reflect.call(event,"getPlayer");Object hand=Reflect.call(Reflect.call(player,"getInventory"),"getItemInMainHand");String toolTag=toolTag(hand);
            if(toolTag==null)return;
            String material=String.valueOf(Reflect.call(block,"getType"));
            if(toolTag.equals("AXE_CHOPPER")) {
                if(!isLogBlock(material))return;
                Reflect.call(event,"setCancelled",true);
                breakTree(player,block,hand);
                return;
            }
            if(!validForTool(material,toolTag))return;
            Reflect.call(event,"setCancelled",true);
            breakArea(player,block,hand,toolTag);
        }catch(Exception ex){log("3x3 block break: "+ex.getMessage());}
    }
    private String toolTag(Object item) {
        try {Object meta=Reflect.call(item,"getItemMeta");Object lore=Reflect.call(meta,"getLore");if(lore instanceof List<?> list)for(Object line:list){String s=String.valueOf(line);if(s.startsWith(TOOL_MARKER))return s.substring(TOOL_MARKER.length());}}catch(Exception ignored){}return null;
    }
    private boolean isLogBlock(String material) {
        return material.endsWith("_LOG") || material.endsWith("_WOOD") || material.endsWith("_STEM") ||
                material.endsWith("_HYPHAE") || material.equals("CRIMSON_STEM") || material.equals("WARPED_STEM") ||
                material.equals("MANGROVE_ROOTS") || material.equals("MUDDY_MANGROVE_ROOTS");
    }

    private void breakTree(Object player,Object center,Object tool) throws Exception {
        Object world=Reflect.call(center,"getWorld");
        int sx=((Number)Reflect.call(center,"getX")).intValue(), sy=((Number)Reflect.call(center,"getY")).intValue(), sz=((Number)Reflect.call(center,"getZ")).intValue();
        int maximum=clampInt(String.valueOf(configInt("haven-tools.chopper.max-logs", configInt("amethyst-tools.chopper.max-logs",96))),1,256,96);
        ArrayDeque<int[]> queue=new ArrayDeque<>(); Set<String> visited=new HashSet<>(); queue.add(new int[]{sx,sy,sz});
        int broken=0;
        final int[][] directions={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while(!queue.isEmpty() && broken<maximum) {
            int[] pos=queue.removeFirst(); String key=pos[0]+":"+pos[1]+":"+pos[2]; if(!visited.add(key))continue;
            if(!Boolean.TRUE.equals(Reflect.call(world,"isChunkLoaded",Math.floorDiv(pos[0],16),Math.floorDiv(pos[2],16))))continue;
            Object target=Reflect.call(world,"getBlockAt",pos[0],pos[1],pos[2]); String mat=String.valueOf(Reflect.call(target,"getType"));
            if(!isLogBlock(mat))continue;
            Object targetLoc=Reflect.call(target,"getLocation"); if(isProtected.test(targetLoc))continue;
            Object state=Reflect.call(target,"getState"); String stateName=state.getClass().getName();
            if(stateName.contains("Container")||stateName.contains("CreatureSpawner")||stateName.contains("Sign"))continue;
            Object drops=null; try{drops=Reflect.call(target,"getDrops",tool,player);}catch(Exception ignored){try{drops=Reflect.call(target,"getDrops",tool);}catch(Exception ignored2){}}
            if(drops instanceof Collection<?> collection) for(Object drop:collection) Reflect.call(world,"dropItemNaturally",targetLoc,drop);
            Reflect.call(target,"setType",Reflect.enumValue("org.bukkit.Material","AIR")); broken++;
            for(int[] d:directions) queue.add(new int[]{pos[0]+d[0],pos[1]+d[1],pos[2]+d[2]});
        }
        if(broken>0)damageSpecialTool(player,tool,broken);
        tell(player,"§bHaven Chopper §7đã đốn §f"+broken+" §7khúc gỗ.");
    }

    private boolean validForTool(String block,String tag) {
        if (tag.equals("PICKAXE_3X3")) {
            // Mine any normal block in the 3x3 plane (including dirt, sand, gravel and plants),
            // while never touching unbreakable/technical blocks or fluids/portals.
            if (Set.of("AIR","CAVE_AIR","VOID_AIR","BEDROCK","BARRIER","STRUCTURE_VOID","STRUCTURE_BLOCK","JIGSAW",
                    "COMMAND_BLOCK","CHAIN_COMMAND_BLOCK","REPEATING_COMMAND_BLOCK","END_PORTAL_FRAME","END_PORTAL",
                    "NETHER_PORTAL","END_GATEWAY","MOVING_PISTON","PISTON_HEAD","WATER","LAVA","FIRE","SOUL_FIRE").contains(block)) return false;
            try {
                Object material = Reflect.enumValue("org.bukkit.Material", block);
                return Boolean.TRUE.equals(Reflect.call(material, "isBlock")) && !Boolean.TRUE.equals(Reflect.call(material, "isAir"));
            } catch (Exception ignored) { return false; }
        }
        if(tag.equals("SHOVEL_3X3"))return Set.of("DIRT","GRASS_BLOCK","SAND","RED_SAND","GRAVEL","CLAY","SOUL_SAND","SOUL_SOIL","SNOW_BLOCK","MUD","PODZOL","MYCELIUM","ROOTED_DIRT","MOSS_BLOCK","MUDDY_MANGROVE_ROOTS").contains(block);
        return false;
    }
    private void breakArea(Object player,Object center,Object tool,String tag) throws Exception {
        Object loc=Reflect.call(center,"getLocation"),world=Reflect.call(loc,"getWorld");Object direction=Reflect.call(Reflect.call(player,"getLocation"),"getDirection");
        double dx=num(Reflect.call(direction,"getX")),dy=num(Reflect.call(direction,"getY")),dz=num(Reflect.call(direction,"getZ"));
        int cx=((Number)Reflect.call(center,"getX")).intValue(),cy=((Number)Reflect.call(center,"getY")).intValue(),cz=((Number)Reflect.call(center,"getZ")).intValue();
        List<int[]> points=new ArrayList<>();
        if(Math.abs(dy)>0.72){for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)points.add(new int[]{cx+a,cy,cz+b});}
        else if(Math.abs(dx)>Math.abs(dz)){for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)points.add(new int[]{cx,cy+a,cz+b});}
        else {for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)points.add(new int[]{cx+a,cy+b,cz});}
        int broken=0;
        for(int[] p:points){Object target=Reflect.call(world,"getBlockAt",p[0],p[1],p[2]);String mat=String.valueOf(Reflect.call(target,"getType"));if(!validForTool(mat,tag))continue;
            Object targetLoc=Reflect.call(target,"getLocation");if(isProtected.test(targetLoc))continue;
            Object state=Reflect.call(target,"getState");String stateName=state.getClass().getName();if(stateName.contains("Container")||stateName.contains("CreatureSpawner")||stateName.contains("Sign")||stateName.contains("Skull"))continue;
            Object drops=null;try{drops=Reflect.call(target,"getDrops",tool,player);}catch(Exception ignored){try{drops=Reflect.call(target,"getDrops",tool);}catch(Exception ignored2){}}
            if(drops instanceof Collection<?> collection)for(Object drop:collection)Reflect.call(world,"dropItemNaturally",Reflect.call(target,"getLocation"),drop);
            Reflect.call(target,"setType",Reflect.enumValue("org.bukkit.Material","AIR"));broken++;
        }
        if (broken > 0) damageSpecialTool(player, tool, broken);
        tell(player,"§bHaven Tool §7đã khai thác §f"+broken+" §7block.");
    }

    private void damageSpecialTool(Object player,Object tool,int damage) {
        try {
            Object meta=Reflect.call(tool,"getItemMeta");
            int current=((Number)Reflect.call(meta,"getDamage")).intValue();
            int maximum=((Number)Reflect.call(Reflect.call(tool,"getType"),"getMaxDurability")).intValue();
            if(maximum<=0)return;
            int unbreaking = 0;
            try {
                Object ench = resolveEnchant("unbreaking", "DURABILITY");
                if (ench != null) unbreaking = ((Number) Reflect.call(tool, "getEnchantmentLevel", ench)).intValue();
            } catch (Exception ignored) { }
            int effectiveDamage = 0;
            if (unbreaking > 0) {
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < damage; i++) {
                    if (rng.nextInt(unbreaking + 1) == 0) effectiveDamage++;
                }
            } else {
                effectiveDamage = damage;
            }
            if (effectiveDamage <= 0) return;
            int next=current+effectiveDamage;
            Object inv=Reflect.call(player,"getInventory");
            if(next>=maximum){Reflect.call(inv,"setItemInMainHand",airItem());tell(player,"§cAmethyst Tool đã bị hỏng.");}
            else {Reflect.call(meta,"setDamage",next);Reflect.call(tool,"setItemMeta",meta);Reflect.call(inv,"setItemInMainHand",tool);}
        } catch(Exception ex) { log("Không áp dụng được độ bền 3x3 tool: "+ex.getMessage()); }
    }

    // -------------------- Auction House --------------------
    public void openAuctionMenu(Object player) { try { openAuction(player,0,false); } catch(Exception ex) { tell(player,"§cKhông mở được Auction House."); } }
    public void openOrdersMenu(Object player) { try { openOrders(player,0); } catch(Exception ex) { tell(player,"§cKhông mở được Orders."); } }
    public void openSpawnerShopMenu(Object player) { openSpawnerShop(player); }

    private boolean auctionCommand(Object sender,String[] args) throws Exception {
        requirePlayer(sender);
        if(args.length==0){openAuction(sender,0,false);return true;}
        if(args[0].equalsIgnoreCase("mine") || args[0].equalsIgnoreCase("my")){openAuction(sender,0,true);return true;}
        if(args[0].equalsIgnoreCase("sell")){
            if(args.length<2){tell(sender,"§e/ah sell <price> §7(ví dụ 5k, 2.5m, 1b)");return true;}long price=MoneyFormat.parse(args[1]);if(price<=0||price>1_000_000_000_000L){tell(sender,"§cGiá không hợp lệ.");return true;}
            Object inv=Reflect.call(sender,"getInventory"),held=Reflect.call(inv,"getItemInMainHand");String material=String.valueOf(Reflect.call(held,"getType"));int amount=((Number)Reflect.call(held,"getAmount")).intValue();if(amount<=0||material.equals("AIR")){tell(sender,"§cBạn cần cầm vật phẩm muốn bán.");return true;}
            String data=serializeItem(held);String seller=name(sender);long id=db.createAuction(uuid(sender).toString(),seller,data,material,amount,price,System.currentTimeMillis()+configLong("market.auction-duration-hours",168)*3_600_000L);
            if(id<=0){tell(sender,"§cKhông tạo được tin đăng; item vẫn được giữ.");return true;}
            Reflect.call(inv,"setItemInMainHand",airItem());tell(sender,"§aĐã đăng bán §f#"+id+" §7- "+amount+"x "+pretty(material)+" §6$"+MoneyFormat.compact(price));return true;
        }
        if(args[0].equalsIgnoreCase("cancel")&&args.length>=2){long id=parseLong(args[1]);String[] row=db.cancelAuction(id,uuid(sender).toString());if(row==null){tell(sender,"§cKhông tìm thấy tin hoặc bạn không phải người đăng.");return true;}giveSerialized(sender,row[0]);tell(sender,"§aĐã thu hồi tin bán #"+id+" và trả vật phẩm.");openAuction(sender,0,true);return true;}
        tell(sender,"§e/ah §7- mở chợ | §e/ah mine §7- tin đang bán của bạn | §e/ah sell <price> §7(ví dụ 5k, 2.5m, 1b) §7- đăng món đang cầm | §e/ah cancel <id> §7- thu hồi tin");return true;
    }
    private void openAuction(Object player,int page,boolean mine) throws Exception {
        UUID id=uuid(player);
        int safePage=Math.max(0,page);
        List<String[]> rows=mine?db.myAuctionListings(id.toString(),45,safePage*45):db.auctionListings(45,safePage*45);
        menuListings.put(id,rows);menuPage.put(id,safePage);if(mine)auctionMineView.add(id);else auctionMineView.remove(id);
        Object inv=inventory(AH_GUI,54);
        fillBottomBar(inv);
        for(int i=0;i<rows.size();i++){
            String[] r=rows.get(i);Object stack=deserializeItem(r[3]);if(stack==null)continue;Object meta=Reflect.call(stack,"getItemMeta");
            List<String> lore=new ArrayList<>();
            Object existingLore=Reflect.call(meta,"getLore");
            if(existingLore instanceof List<?> orig && !orig.isEmpty()){
                for(Object line:orig) lore.add(String.valueOf(line));
            }
            lore.add("§8──────────────");
            lore.add("§7Giá: §a$"+MoneyFormat.compact(Long.parseLong(r[6])));
            lore.add("§7Người bán: §f"+r[2]);
            lore.add("§7Còn lại: §e"+formatTimeLeft(Long.parseLong(r[8])));
            lore.add("§8ID: #"+r[0]);
            boolean own=r[1].equals(id.toString());
            lore.add(own?"§c▸ Click để thu hồi vật phẩm":"§a▸ Click để mua ngay");
            Reflect.call(meta,"setLore",lore);Reflect.call(stack,"setItemMeta",meta);Reflect.call(inv,"setItem",i,stack);
        }
        if(safePage>0) item(inv,45,"ARROW","§e◀ Trang trước",List.of("§7Về trang "+safePage));
        item(inv,46,mine?"COMPASS":"CHEST",mine?"§bChợ Chung":"§eTin Của Tôi",
                mine?List.of("§7Đang xem: §aTin của bạn","§eClick để về chợ chung")
                    :List.of("§7Xem và thu hồi các món bạn đang bán","§eClick để chuyển"));
        item(inv,47,"EMERALD","§aĐăng Bán Vật Phẩm",List.of("§7Cầm vật phẩm trên tay rồi nhập:","§f/ah sell <giá> §8(VD: 5k, 2.5m)"));
        item(inv,49,"PAPER","§6§lAUCTION HOUSE §7• Trang "+(safePage+1),List.of(
                "§7Chế độ: "+(mine?"§eTin của bạn":"§bChợ chung"),
                "§7Đang hiển thị: §f"+rows.size()+" món",
                "§7Số dư: §a$"+MoneyFormat.compact(economy.balance(id))));
        item(inv,51,"SUNFLOWER","§eLàm Mới",List.of("§7Tải lại danh sách mới nhất"));
        if(rows.size()>=45) item(inv,52,"ARROW","§eTrang sau ▶",List.of("§7Sang trang "+(safePage+2)));
        item(inv,53,"BARRIER","§cĐóng",List.of());
        Reflect.call(player,"openInventory",inv);
    }
    private void withdrawAuction(Object player,int slot) throws Exception {
        UUID owner=uuid(player);List<String[]> rows=menuListings.getOrDefault(owner,List.of());if(slot<0||slot>=rows.size())return;String[] row=rows.get(slot);long id=Long.parseLong(row[0]);
        String[] withdrawn=db.cancelAuction(id,owner.toString());
        if(withdrawn==null){tell(player,"§cTin không còn hoạt động hoặc không thuộc về bạn.");openAuction(player,menuPage.getOrDefault(owner,0),true);return;}
        giveSerialized(player,withdrawn[0]);tell(player,"§aĐã thu hồi tin #"+id+". Vật phẩm đã được trả lại.");openAuction(player,menuPage.getOrDefault(owner,0),true);
    }
    private void buyAuction(Object player,int slot) throws Exception {
        List<String[]> rows=menuListings.getOrDefault(uuid(player),List.of());if(slot<0||slot>=rows.size())return;String[] r=rows.get(slot);UUID buyer=uuid(player);
        if(r[1].equals(buyer.toString())){withdrawAuction(player,slot);return;}
        long id=Long.parseLong(r[0]),price=Long.parseLong(r[6]);if(economy.balance(buyer)<price){tell(player,"§cBạn không đủ tiền.");return;}
        String[] claimed=db.buyAuction(id,buyer.toString());if(claimed==null){tell(player,"§cTin này vừa được người khác mua hoặc đã hết hạn.");openAuction(player,menuPage.getOrDefault(buyer,0),auctionMineView.contains(buyer));return;}
        Object item=deserializeItem(claimed[2]);if(item==null){db.reverseAuctionPurchase(id,buyer.toString(),claimed[0],price);tell(player,"§cDữ liệu vật phẩm lỗi, giao dịch đã được hoàn tác. Báo Admin.");return;}
        giveObject(player,item);tell(player,"§aĐã mua thành công tin #"+id+".");openAuction(player,menuPage.getOrDefault(buyer,0),auctionMineView.contains(buyer));
    }

    // -------------------- Buy Orders --------------------
    private boolean orderCommand(Object sender,String[] args) throws Exception {
        requirePlayer(sender);
        if(args.length==0){openOrders(sender,0);return true;}
        if(args[0].equalsIgnoreCase("claim")){
            List<String[]> deliveries=db.orderDeliveries(uuid(sender));
            if(deliveries.isEmpty()){tell(sender,"§7Bạn không có vật phẩm chờ nhận từ Orders.");return true;}
            int claimed=0;
            int kept=0;
            for(String[] delivery:deliveries){
                int amount=Integer.parseInt(delivery[2]);
                int remaining=addMaterialToInventory(sender,delivery[1],amount);
                int delivered=amount-remaining; claimed+=delivered;
                if(remaining<=0) db.deleteOrderDelivery(Long.parseLong(delivery[0]),uuid(sender));
                else { db.updateOrderDeliveryAmount(Long.parseLong(delivery[0]),uuid(sender),remaining); kept+=remaining; }
            }
            tell(sender,"§aĐã nhận " + claimed + " vật phẩm từ Orders." + (kept>0?" §eCòn "+kept+" món trong hộp thư vì túi đồ đã đầy.":""));
            return true;
        }
        if(args[0].equalsIgnoreCase("create")){
            if(args.length==1){openOrderMaterialSelector(sender,0);return true;}
            if(args.length<4){tell(sender,"§e/order create <MATERIAL> <amount> <priceEach> §7(ví dụ 5k, 2.5m, 1b) hoặc /order create để mở GUI");return true;}
            String mat=args[1].toUpperCase(Locale.ROOT);if(!isMaterial(mat)){tell(sender,"§cMaterial không hợp lệ.");return true;}
            int amount=clampInt(args[2],1,100000,1);long unit=MoneyFormat.parse(args[3]);if(unit<=0||unit>100_000_000_000L){tell(sender,"§cGiá mỗi món không hợp lệ.");return true;}
            long total=Math.multiplyExact((long)amount,unit);UUID id=uuid(sender);
            long orderId=db.createOrder(id.toString(),name(sender),mat,amount,unit,System.currentTimeMillis()+configLong("market.order-duration-hours",72)*3_600_000L);
            if(orderId<=0){tell(sender,"§cKhông đủ tiền hoặc không tạo được đơn. Cần $"+total+" để ký quỹ.");return true;}
            tell(sender,"§aĐã tạo đơn mua #"+orderId+" · "+amount+"x "+mat+" · ký quỹ $"+total);openOrders(sender,0);return true;
        }
        if(args[0].equalsIgnoreCase("cancel")&&args.length>=2){long id=parseLong(args[1]);long escrow=db.cancelOrderAndRefund(id,uuid(sender).toString());if(escrow<0){tell(sender,"§cKhông tìm thấy đơn hoặc đây không phải đơn của bạn.");return true;}tell(sender,"§aĐã hủy đơn #"+id+" và hoàn $"+MoneyFormat.compact(escrow)+".");return true;}
        if(args[0].equalsIgnoreCase("gui")){openOrderMaterialSelector(sender,0);return true;}
        tell(sender,"§e/orders §7- xem đơn mua | §e/order claim §7- nhận vật phẩm đã mua | §e/order create §7- mở GUI chọn món | §e/order create <MATERIAL> <amount> <priceEach> | §e/order cancel <id>");return true;
    }
    private void openOrders(Object player,int page) throws Exception {
        db.expireOrders();
        UUID id=uuid(player);
        int safePage=Math.max(0,page);
        List<String[]> rows=db.activeOrders(45,safePage*45);menuListings.put(id,rows);menuPage.put(id,safePage);
        Object inv=inventory(ORDERS_GUI,54);
        fillBottomBar(inv);
        for(int i=0;i<rows.size();i++){
            String[] r=rows.get(i);String material=r[3];
            boolean own=r[1].equals(id.toString());
            item(inv,i,material,"§e"+pretty(material)+" §7x"+r[4],List.of(
                    "§8──────────────",
                    "§7Giá / món: §a$"+MoneyFormat.compact(Long.parseLong(r[5])),
                    "§7Tổng ký quỹ: §6$"+MoneyFormat.compact(Long.parseLong(r[6])),
                    "§7Người mua: §f"+r[2],
                    "§7Còn lại: §e"+formatTimeLeft(Long.parseLong(r[8])),
                    "§8Order #"+r[0],
                    own?"§c▸ Click phải để hủy & hoàn tiền":"§a▸ Click để giao vật phẩm từ túi"));
        }
        int pendingDeliveries=db.orderDeliveries(id).size();
        if(safePage>0) item(inv,45,"ARROW","§e◀ Trang trước",List.of("§7Về trang "+safePage));
        item(inv,46,"CHEST","§aNhận Hàng Đã Mua"+(pendingDeliveries>0?" §e("+pendingDeliveries+")":""),
                List.of("§7Lô hàng đang chờ: §f"+pendingDeliveries,"§eClick để nhận • /order claim"));
        item(inv,47,"WRITABLE_BOOK","§bTạo Đơn Mua Mới",
                List.of("§7Chọn vật phẩm muốn mua","§7rồi nhập số lượng & giá trong chat","§eClick để tạo đơn"));
        item(inv,49,"PAPER","§b§lORDERS §7• Trang "+(safePage+1),List.of(
                "§7Đang hiển thị: §f"+rows.size()+" đơn",
                "§7Ký quỹ = số lượng × giá mỗi món",
                "§7Số dư: §a$"+MoneyFormat.compact(economy.balance(id))));
        item(inv,51,"SUNFLOWER","§eLàm Mới",List.of("§7Tải lại danh sách đơn mua"));
        if(rows.size()>=45) item(inv,52,"ARROW","§eTrang sau ▶",List.of("§7Sang trang "+(safePage+2)));
        item(inv,53,"BARRIER","§cĐóng",List.of());
        Reflect.call(player,"openInventory",inv);
    }

    private void openOrderMaterialSelector(Object player,int page) throws Exception {
        int maxPage=Math.max(0,(ORDER_MATERIALS.size()-1)/45);int actual=Math.max(0,Math.min(page,maxPage));
        int from=actual*45,to=Math.min(ORDER_MATERIALS.size(),from+45);List<String> shown=new ArrayList<>(ORDER_MATERIALS.subList(from,to));orderMaterialPages.put(uuid(player),shown);orderMaterialPage.put(uuid(player),actual);
        Object inv=inventory(ORDER_SELECT_GUI,54);
        fillBottomBar(inv);
        for(int i=0;i<shown.size();i++){String material=shown.get(i);item(inv,i,material,"§e"+pretty(material),List.of("§7Chọn vật phẩm muốn đặt mua","§a▸ Click để nhập số lượng & giá"));}
        if(actual>0) item(inv,45,"ARROW","§e◀ Trang trước",List.of());
        item(inv,46,"ARROW","§eQuay lại Orders",List.of());
        item(inv,49,"PAPER","§fTrang "+(actual+1)+"/"+(maxPage+1),List.of("§7Chọn một vật phẩm ở phía trên"));
        if(actual<maxPage) item(inv,52,"ARROW","§eTrang sau ▶",List.of());
        item(inv,53,"BARRIER","§cĐóng",List.of());Reflect.call(player,"openInventory",inv);
    }

    private void beginOrderDraft(Object player,String material) throws Exception {
        orderDrafts.put(uuid(player),new OrderDraft(material,"AMOUNT",0));Reflect.call(player,"closeInventory");
        tell(player,"§aĐã chọn §f"+pretty(material)+"§a. Nhập số lượng muốn mua trong chat (1–100000). Gõ §cHỦY §ađể dừng.");
    }

    private void onOrderChat(Object event) {
        try {
            Object player=Reflect.call(event,"getPlayer");UUID id=uuid(player);if(!orderDrafts.containsKey(id))return;
            Object component=Reflect.call(event,"message");
            Object serializer=Reflect.callStatic("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer","plainText");
            String message=String.valueOf(Reflect.call(serializer,"serialize",component)).trim();
            Reflect.call(event,"setCancelled",true);
            Reflect.scheduleLater(plugin,()->processOrderChat(player,message),1L);
        } catch(Exception ex) { log("Order chat prompt: "+ex.getMessage()); }
    }

    private void processOrderChat(Object player,String message) {
        try {
            UUID id=uuid(player);OrderDraft draft=orderDrafts.get(id);if(draft==null)return;
            if(!Boolean.TRUE.equals(Reflect.call(player,"isOnline"))){orderDrafts.remove(id);return;}
            if(message.equalsIgnoreCase("huy")||message.equalsIgnoreCase("cancel")||message.equalsIgnoreCase("exit")){
                orderDrafts.remove(id);tell(player,"§cĐã hủy tạo đơn mua.");return;
            }
            if(draft.stage().equals("AMOUNT")){
                int amount;
                try{amount=Integer.parseInt(message.replace(",",""));}catch(Exception ex){tell(player,"§cSố lượng không hợp lệ. Nhập số nguyên từ 1 đến 100000 hoặc HỦY.");return;}
                if(amount<1||amount>100000){tell(player,"§cSố lượng phải từ 1 đến 100000.");return;}
                orderDrafts.put(id,new OrderDraft(draft.material(),"PRICE",amount));
                tell(player,"§aSố lượng: §f"+amount+"x "+pretty(draft.material())+"§a. Nhập §6giá cho MỖI món§a (ví dụ 5000, 5k, 2.5m hoặc 1b). Gõ HỦY để dừng.");return;
            }
            long unit=MoneyFormat.parse(message);if(unit<=0||unit>1_000_000_000_000L){tell(player,"§cGiá không hợp lệ. Nhập số dương tối đa 1.000.000.000.000 hoặc HỦY.");return;}
            long total;
            try{total=Math.multiplyExact((long)draft.amount(),unit);}catch(ArithmeticException ex){tell(player,"§cTổng ký quỹ vượt giới hạn.");orderDrafts.remove(id);return;}
            long orderId=db.createOrder(id.toString(),name(player),draft.material(),draft.amount(),unit,System.currentTimeMillis()+configLong("market.order-duration-hours",72)*3_600_000L);
            orderDrafts.remove(id);
            if(orderId<=0){tell(player,"§cKhông đủ tiền để ký quỹ §6$"+MoneyFormat.compact(total)+"§c hoặc không tạo được đơn. Không có tiền nào bị trừ.");openOrders(player,0);return;}
            tell(player,"§aĐã tạo đơn §f#"+orderId+"§a: §f"+draft.amount()+"x "+pretty(draft.material())+"§a · giá/món §6$"+MoneyFormat.compact(unit)+"§a · ký quỹ §6$"+MoneyFormat.compact(total));
            openOrders(player,0);
        } catch(Exception ex){orderDrafts.remove(uuid(player));tell(player,"§cKhông tạo được đơn. Kiểm tra console.");log("Order draft processing: "+ex.getMessage());}
    }

    private long parseMoney(String input) { return MoneyFormat.parse(input); }
    private void fulfillOrder(Object player,int slot) throws Exception {
        List<String[]> rows=menuListings.getOrDefault(uuid(player),List.of());if(slot<0||slot>=rows.size())return;String[] row=rows.get(slot);long orderId=Long.parseLong(row[0]);if(row[1].equals(uuid(player).toString())){tell(player,"§cKhông thể tự giao đơn của mình.");return;}
        String material=row[3];int wanted=Integer.parseInt(row[4]);int available=countMaterial(player,material);int amount=Math.min(wanted,available);if(amount<=0){tell(player,"§cBạn không có vật phẩm cần giao.");return;}
        removeMaterial(player,material,amount);String[] fill;
        try { fill=db.fillOrder(orderId,amount,uuid(player).toString()); }
        catch(Exception ex){giveMaterial(player,material,amount);throw ex;}
        if(fill==null){giveMaterial(player,material,amount);tell(player,"§cĐơn vừa được người khác xử lý; vật phẩm đã trả lại.");openOrders(player,menuPage.getOrDefault(uuid(player),0));return;}
        long payout=Long.parseLong(fill[2]);tell(player,"§aĐã giao "+amount+"x "+material+" và nhận §6$"+MoneyFormat.compact(payout)+"§a.");openOrders(player,menuPage.getOrDefault(uuid(player),0));
    }

    // -------------------- GUI routing --------------------
    private void onInventoryClick(Object event) {
        try {
            String title=String.valueOf(Reflect.call(Reflect.call(event,"getView"),"getTitle"));
            if(!Set.of(SPAWNER_GUI,STORAGE_GUI,SPAWNER_SHOP,AH_GUI,ORDERS_GUI,ORDER_SELECT_GUI).contains(title))return;
            Reflect.call(event,"setCancelled",true);Object player=Reflect.call(event,"getWhoClicked");int slot=((Number)Reflect.call(event,"getRawSlot")).intValue();
            Object view=Reflect.call(event,"getView");Object top=Reflect.call(view,"getTopInventory");int topSize=((Number)Reflect.call(top,"getSize")).intValue();if(slot<0||slot>=topSize)return;
            switch(title){
                case SPAWNER_GUI -> {if(slot==11)openStorage(player,0);else if(slot==15)collectSpawnerXp(player);else if(slot==22)collectAll(player,true);else if(slot==26)Reflect.call(player,"closeInventory");}
                case STORAGE_GUI -> {if(slot<45)collectStorageItem(player,slot);else if(slot==45){Key k=Key.decode(openSpawner.get(uuid(player)));String[] row=db.spawner(k.world,k.x,k.y,k.z);if(row!=null)openSpawnerGui(player,row);}else if(slot==47)openStorage(player,Math.max(0,storagePages.getOrDefault(uuid(player),0)-1));else if(slot==49)collectAll(player,false);else if(slot==51)collectAll(player,true);else if(slot==53)openStorage(player,storagePages.getOrDefault(uuid(player),0)+1);}
                case SPAWNER_SHOP -> {if(slot==45)Reflect.call(player,"performCommand","shop");else if(slot==53)Reflect.call(player,"closeInventory");else if(slot!=49)buySpawner(player,slot);}
                case AH_GUI -> {
                    UUID id=uuid(player);boolean mine=auctionMineView.contains(id);
                    int p=menuPage.getOrDefault(id,0);
                    if(slot<45){if(mine)withdrawAuction(player,slot);else buyAuction(player,slot);}
                    else if(slot==45 && p>0)openAuction(player,p-1,mine);
                    else if(slot==46)openAuction(player,0,!mine);
                    else if(slot==47){Reflect.call(player,"closeInventory");tell(player,"§eCầm vật phẩm muốn bán ở tay chính rồi nhập §f/ah sell <giá>§e. Ví dụ: §f/ah sell 5k§e hoặc §f/ah sell 2.5m§e.");}
                    else if(slot==51)openAuction(player,p,mine);
                    else if(slot==52 && menuListings.getOrDefault(id,List.of()).size()>=45)openAuction(player,p+1,mine);
                    else if(slot==53)Reflect.call(player,"closeInventory");
                }
                case ORDERS_GUI -> {
                    UUID id=uuid(player);int p=menuPage.getOrDefault(id,0);
                    if(slot<45){
                        boolean right=String.valueOf(Reflect.call(event,"getClick")).contains("RIGHT");
                        handleOrderSlotClick(player,slot,right);
                    }
                    else if(slot==45 && p>0)openOrders(player,p-1);
                    else if(slot==46)Reflect.call(player,"performCommand","order claim");
                    else if(slot==47)openOrderMaterialSelector(player,0);
                    else if(slot==51)openOrders(player,p);
                    else if(slot==52 && menuListings.getOrDefault(id,List.of()).size()>=45)openOrders(player,p+1);
                    else if(slot==53)Reflect.call(player,"closeInventory");
                }
                case ORDER_SELECT_GUI -> {
                    int p=orderMaterialPage.getOrDefault(uuid(player),0);
                    if(slot<45){List<String> shown=orderMaterialPages.getOrDefault(uuid(player),List.of());if(slot<shown.size())beginOrderDraft(player,shown.get(slot));}
                    else if(slot==45 && p>0)openOrderMaterialSelector(player,p-1);
                    else if(slot==46)openOrders(player,0);
                    else if(slot==52)openOrderMaterialSelector(player,p+1);
                    else if(slot==53)Reflect.call(player,"closeInventory");
                }
            }
        }catch(Exception ex){log("GUI expansion click: "+ex.getMessage());}
    }
    private void onInventoryDrag(Object event) {
        try {String title=String.valueOf(Reflect.call(Reflect.call(event,"getView"),"getTitle"));if(Set.of(SPAWNER_GUI,STORAGE_GUI,SPAWNER_SHOP,AH_GUI,ORDERS_GUI,ORDER_SELECT_GUI).contains(title))Reflect.call(event,"setCancelled",true);}catch(Exception ignored){}
    }

    // -------------------- Utility --------------------
    private record Key(String world,int x,int y,int z){String encode(){return world+"|"+x+"|"+y+"|"+z;}static Key decode(String value){String[] p=value.split("\\|");return new Key(p[0],Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));}}
    private Key key(Object block){return keyFromLocation(Reflect.call(block,"getLocation"));}
    private Key keyFromLocation(Object loc){return new Key(String.valueOf(Reflect.call(Reflect.call(loc,"getWorld"),"getName")),((Number)Reflect.call(loc,"getBlockX")).intValue(),((Number)Reflect.call(loc,"getBlockY")).intValue(),((Number)Reflect.call(loc,"getBlockZ")).intValue());}
    private Object inventory(String title,int size){return Reflect.callStatic("org.bukkit.Bukkit","createInventory",null,size,title);}
    private void handleOrderSlotClick(Object player, int slot, boolean rightClick) throws Exception {
        UUID id = uuid(player);
        List<String[]> rows = menuListings.getOrDefault(id, List.of());
        if (slot < 0 || slot >= rows.size()) return;
        String[] row = rows.get(slot);
        if (row[1].equals(id.toString())) {
            if (!rightClick) { tell(player, "§eĐây là đơn của bạn. §cClick phải §eđể hủy đơn và nhận lại tiền ký quỹ."); return; }
            long orderId = Long.parseLong(row[0]);
            long refund = db.cancelOrderAndRefund(orderId, id.toString());
            if (refund < 0) tell(player, "§cKhông thể hủy đơn này.");
            else tell(player, "§aĐã hủy đơn §f#" + orderId + " §avà hoàn §6$" + MoneyFormat.compact(refund) + "§a.");
            openOrders(player, menuPage.getOrDefault(id, 0));
            return;
        }
        fulfillOrder(player, slot);
    }
    private void fill(Object inv,int size){for(int i=0;i<size;i++)item(inv,i,"GRAY_STAINED_GLASS_PANE","§r",List.of());}
    private void fillBottomBar(Object inv){for(int i=45;i<54;i++)item(inv,i,"BLACK_STAINED_GLASS_PANE","§r",List.of());}
    private String formatTimeLeft(long expiresAt){
        long rem=Math.max(0L,(expiresAt-System.currentTimeMillis())/1000L);
        if(rem<=0L)return "Sắp hết hạn";
        long days=rem/86400L,hours=(rem%86400L)/3600L,mins=(rem%3600L)/60L;
        if(days>0)return days+"d "+hours+"h";
        if(hours>0)return hours+"h "+mins+"m";
        return Math.max(1L,mins)+"m";
    }
    private void item(Object inv,int slot,String material,String name,List<String> lore){try{Object stack=Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material",material));Object meta=Reflect.call(stack,"getItemMeta");Reflect.call(meta,"setDisplayName",name);Reflect.call(meta,"setLore",lore);Reflect.call(stack,"setItemMeta",meta);Reflect.call(inv,"setItem",slot,stack);}catch(Exception ignored){}}
    private Object airItem(){return Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material","AIR"));}
    private Object arrayOf(String type,Object item){Object a=Array.newInstance(Reflect.type(type),1);Array.set(a,0,item);return a;}
    private String serializeItem(Object stack) {Object yaml=Reflect.construct("org.bukkit.configuration.file.YamlConfiguration");Reflect.call(yaml,"set","item",stack);return String.valueOf(Reflect.call(yaml,"saveToString"));}
    private Object deserializeItem(String value){try{Object yaml=Reflect.construct("org.bukkit.configuration.file.YamlConfiguration");Reflect.call(yaml,"loadFromString",value);return Reflect.call(yaml,"getItemStack","item");}catch(Exception ex){return null;}}
    private void giveSerialized(Object player,String value){Object stack=deserializeItem(value);if(stack!=null)giveObject(player,stack);}
    private void giveObject(Object player,Object stack){try{Object leftovers=Reflect.call(Reflect.call(player,"getInventory"),"addItem",arrayOf("org.bukkit.inventory.ItemStack",stack));if(leftovers instanceof Map<?,?> map&&!map.isEmpty()){Object loc=Reflect.call(player,"getLocation"),world=Reflect.call(loc,"getWorld");for(Object left:map.values())Reflect.call(world,"dropItemNaturally",loc,left);}}catch(Exception ex){tell(player,"§cKhông thể giao item; báo Admin.");}}
    /** Adds as much as possible to storage inventory and returns the amount still pending. */
    private int addMaterialToInventory(Object player,String material,int amount) {
        int remaining=Math.max(0,amount);
        try {
            Object inventory=Reflect.call(player,"getInventory");
            while(remaining>0) {
                int part=Math.min(64,remaining);
                Object stack=Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material",material),part);
                Object leftovers=Reflect.call(inventory,"addItem",arrayOf("org.bukkit.inventory.ItemStack",stack));
                int left=0;
                if(leftovers instanceof Map<?,?> map) for(Object value:map.values()) left+=((Number)Reflect.call(value,"getAmount")).intValue();
                remaining-=part-left;
                if(left>0) break;
            }
        } catch(Exception ex) { log("Không thể thêm vật phẩm vào inventory: "+ex.getMessage()); }
        return Math.max(0,remaining);
    }

    private void giveMaterial(Object player,String material,int amount){int remain=amount;while(remain>0){int part=Math.min(64,remain);Object stack=Reflect.construct("org.bukkit.inventory.ItemStack",Reflect.enumValue("org.bukkit.Material",material),part);giveObject(player,stack);remain-=part;}}
    private int countMaterial(Object player,String material){try{Object contents=Reflect.call(Reflect.call(player,"getInventory"),"getStorageContents");int count=0;for(int i=0;i<Array.getLength(contents);i++){Object s=Array.get(contents,i);if(s!=null&&String.valueOf(Reflect.call(s,"getType")).equals(material))count+=((Number)Reflect.call(s,"getAmount")).intValue();}return count;}catch(Exception e){return 0;}}
    private void removeMaterial(Object player,String material,int amount){try{Object inv=Reflect.call(player,"getInventory"),contents=Reflect.call(inv,"getStorageContents");int left=amount;for(int i=0;i<Array.getLength(contents)&&left>0;i++){Object s=Array.get(contents,i);if(s==null||!String.valueOf(Reflect.call(s,"getType")).equals(material))continue;int count=((Number)Reflect.call(s,"getAmount")).intValue(),take=Math.min(count,left);Reflect.call(s,"setAmount",count-take);if(count==take)Reflect.call(inv,"setItem",i,airItem());left-=take;}}catch(Exception ignored){}}
    private boolean isMaterial(String name){try{Reflect.enumValue("org.bukkit.Material",name);return !name.equals("AIR");}catch(Exception e){return false;}}
    private String normalizeMob(String input){String s=input.toUpperCase(Locale.ROOT).replace(" ","_");if(!Set.of("ZOMBIE","SKELETON","SPIDER","CREEPER","BLAZE","COW","PIG","IRON_GOLEM","ENDERMAN","WITCH","SLIME","CAVE_SPIDER").contains(s))throw new IllegalArgumentException("Mob type không hỗ trợ");return s;}
    private String pretty(String value){String s=value.toLowerCase(Locale.ROOT).replace('_',' ');StringBuilder b=new StringBuilder();for(String p:s.split(" ")){if(b.length()>0)b.append(' ');if(!p.isEmpty())b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));}return b.toString();}
    private long parseLong(String value){try{return Long.parseLong(value);}catch(Exception ex){return -1;}}
    private int clampInt(String value,int min,int max,int fallback){try{return Math.max(min,Math.min(max,Integer.parseInt(value)));}catch(Exception ex){return fallback;}}
    private double num(Object value){return value instanceof Number n?n.doubleValue():0;}
    private boolean isCancelled(Object event){try{return Boolean.TRUE.equals(Reflect.call(event,"isCancelled"));}catch(Exception ex){return false;}}
    private boolean isAuthenticated(Object player){try{return Boolean.TRUE.equals(Reflect.call(plugin,"isPlayerAuthenticated",Reflect.call(player,"getUniqueId")));}catch(Exception ex){log("Không xác minh được trạng thái đăng nhập: "+ex.getMessage());return false;}}
    private void requirePlayer(Object sender){if(!Reflect.type("org.bukkit.entity.Player").isInstance(sender))throw new IllegalArgumentException("Chỉ người chơi mới dùng được lệnh này.");}
    private UUID uuid(Object player){return (UUID)Reflect.call(player,"getUniqueId");}
    private String name(Object sender){return String.valueOf(Reflect.call(sender,"getName"));}
    private void tell(Object sender,String text){try{Reflect.call(sender,"sendMessage",text);}catch(Exception ignored){}}
    private long configLong(String path,long fallback){try{return ((Number)Reflect.call(Reflect.call(plugin,"getConfig"),"getLong",path,fallback)).longValue();}catch(Exception ex){return fallback;}}
    private int configInt(String path,int fallback){try{return ((Number)Reflect.call(Reflect.call(plugin,"getConfig"),"getInt",path,fallback)).intValue();}catch(Exception ex){return fallback;}}
    private String configString(String path,String fallback){try{return String.valueOf(Reflect.call(Reflect.call(plugin,"getConfig"),"getString",path,fallback));}catch(Exception ex){return fallback;}}
    private boolean configBoolean(String path,boolean fallback){try{return Boolean.TRUE.equals(Reflect.call(Reflect.call(plugin,"getConfig"),"getBoolean",path,fallback));}catch(Exception ex){return fallback;}}
    private void log(String msg){try{Reflect.call(Reflect.call(plugin,"getLogger"),"warning",msg);}catch(Exception ignored){}}
}
