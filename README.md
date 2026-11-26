# LTZCore 0.5.14-Haven — Haven SMP

Target: **Paper 1.21.11**, **Java 21+**, **SQLite**. This is a development build for a small SMP (around 10–30 players) on a 4GB host. It is Haven-branded and uses public, familiar survival-market flows; it does not copy another server's exact proprietary assets or private implementation.

## Changes in 0.5.4 (Auction House and Orders fix)

- Auction House now has a **My Listings** view (`/ah mine`). Clicking one of your own listings withdraws it and returns the serialized item. `/ah cancel <id>` remains available, including for expired-but-still-active listings.
- Clicking your own item in the public AH also withdraws it instead of attempting a purchase.
- Orders now has a paginated material picker GUI. Use `/orders` then **Tạo đơn mua**, or `/order create` / `/order gui`. Select an item, enter the requested amount in chat, then enter the unit price (supports integer, `k`, and `m` values). The escrow remains `amount × unit price`. Type `HỦY` to abandon the prompt.
- The old direct syntax `/order create <MATERIAL> <amount> <priceEach>` is retained.
- The prompt uses Paper `AsyncChatEvent` and Adventure plain-text serialization.

## Previous changes in 0.5.3

- Rebuilt the main menu with all nine middle-row slots occupied, then a centred Travel & More button. The shop selector also fills the whole middle row with nine categories, removing the previous one-slot visual drift.
- `/sell` is now a 54-slot deposit GUI: put items into the upper 45 slots or shift-click them from your inventory, then press **SELL ALL**. Only materials with a positive entry in `sell.prices` are sold. Items without a configured price and custom-named/lore items are not sold; closing the GUI returns unsold items to the player (overflow drops safely at their location). `/sell hand` sells the held item using the same configured price table.
- Spawner marketplace price is **800 shards per spawner**, all mob types use the same price. `/shard give <player> <amount>` and staff shortcut `/giveshard <player> <amount>` grant shards.
- Spawner stack controls: right-click a managed spawner with a matching spawner item to add one; **Shift + right-click** adds the full held stack. Normal break removes one spawner; **Shift + break** removes the full stack. Breaking the final spawner returns all stored virtual loot and XP even if the storage is not empty; any item overflow is dropped at the player's location.
- Three Haven tools: Haven Drill pickaxe (3x3 for ordinary block materials, including dirt and sand), Haven Excavator shovel (3x3 soft materials), and Haven Chopper (connected logs, bounded by config). They are purchased with shards in `/shop` → Haven 3x3 Tools or issued by staff with `/tool give`.
- Crates keep their short animation and preview, but now give transparent, guaranteed bundles rather than weighted/random rewards. The exact contents are shown in `/crates` preview and configured in `crates.<type>.rewards`.

## Main features

- Offline-mode registration/login with PBKDF2 password hashing, timeout and failed-login lockout. Commands: `/register`, `/login`, `/logout`, `/changepassword`, `/havenregister`, `/havenlogin`, `/dn`.
- Economy: `/bal`, `/pay`, `/baltop`, Haven shop, deposit-style Sell GUI and shard wallet.
- Ranks `Member`, `+`, `++`, `+++`, `Admin`, `Owner`; chat/TAB prefixes. Rank `+` costs `$50,000`, rank `++` costs `$100,000`, and rank `+++` costs `$500,000` in-game. All three are purchased directly with Haven economy balance. Eligible `+`, `++`, `+++` players earn 1 shard per full authenticated online minute.
- LifeSteal as a toggleable event, separate from normal SMP; hearts, kill/loss rules, revive, timer, leaderboard, Heart Shop and bounty.
- Redeem codes with one-use-per-player, global limits, expiration and supported rewards.
- Common and Rank Crates, keys, preview and short animation. Rewards are fixed bundles and visible before opening; keys should be earned or staff-issued, not sold for real money.
- Stacked virtual mob spawners with item storage and XP storage. Managed spawners do not create a swarm of real mobs; one shared timer simulates output only while a player is nearby and the chunk is already loaded. Processing is capped per cycle for a lightweight 4GB-host setup.
- Auction House (`/ah`) and player Orders (`/orders`), escrow, delivery mailbox and claim flow.
- Homes, TPA, spawn/warp, async RTP, daily rewards/streak, playtime rewards, basic moderation, admin logs and SQLite backup.
- Cuboid spawn protection selected using `/spawnregion pos1`, `/spawnregion pos2`, `/spawnregion save`; normal chest/door/button use remains allowed.

## Shop layout

`/shop` opens a 3-row Haven selector with nine centred categories:

2. Nether Materials
3. Crystal PvP (Totem, End Crystal, Obsidian, Respawn Anchor and related Minecraft items)
4. Food & Farming
5. End Items (Ender Pearl, End Stone, Dragon Breath, End Rod, Chorus and Shulker items)
6. Haven 3x3 Tools
7. Mob Spawners (800 shards each)
8. Shard Exchange
9. Sell GUI

Each category has a product list, with price/amount overrides in `plugins/LTZCore/config.yml`. This is a Haven implementation inspired by public survival server market patterns, not a pixel-perfect clone of DonutSMP or KingMC.

## Selling items

- `/sell` opens the deposit GUI.
- Deposit items into the upper 45 slots, or shift-click items from the player inventory.
- Press `SELL ALL` to sell every supported item in the deposit area.
- Unsupported items remain in the GUI and are returned when it closes.
- `/sell hand` sells the held item if that material has a configured price.
- Configure unit prices under `sell.prices.<material>`; a price of `0` or a missing entry disables selling that material.

## Spawners

- `/spawner shop` opens the spawner market.
- `/spawner info` inspects the managed spawner you are looking at.
- Staff: `/spawner give <player> <mob> [amount]`.
- Price: fixed **800 shards for every spawner type**, configured by `spawners.price-shards-per-spawner`.
- Place it, then right-click with a matching Haven spawner item to add one. Hold Shift while right-clicking to add the full held stack.
- Normal break returns one spawner. Hold Shift and break to retrieve the entire stack.
- When the final spawner is removed, all stored loot and XP are delivered regardless of whether the storage was empty. Inventory overflow is dropped next to the player.
- Requires Silk Touch by default and respects spawner ownership/access settings.

## Haven 3x3 tools

- `/shop` → Haven 3x3 Tools purchases using shard.
- Staff: `/tool give <player> pickaxe3x3|shovel3x3|chopper`.
- Pickaxe mines supported stone/ore blocks in a 3x3 plane; shovel mines supported soil/sand/gravel in a 3x3 plane; chopper breaks connected logs up to the configured limit.
- Tools respect LTZCore's own spawn cuboid. They do not automatically query arbitrary third-party claim plugins.
- Prices are under `amethyst-tools.prices-shards`; tree-chopper limit is `amethyst-tools.chopper.max-logs`.

## Crates

- `/crates` opens Common/Rank crate menu.
- `/crate give <player> <common|rank> <amount>` is staff-only.
- `/crate preview <common|rank>` displays the exact guaranteed bundle.
- `/crate editor <common|rank>` displays admin reference values; edit bundle counts in `config.yml` under `crates.<type>.rewards`.
- Each key grants its full configured bundle, with a short animation. No random weights are used in 0.5.3.

## Shard commands

- `/shard` displays your balance.
- `/shard give <player> <amount>` grants shards (staff-only).
- `/giveshard <player> <amount>` is a staff shortcut.
- `/shardtop` shows the top shard balances.
- `/shardshop` opens the exchange.

## Useful commands

| Command | Function |
|---|---|
| `/menu`, `/dn` | Main menu |
| `/shop` | Haven Market selector |
| `/sell` | Deposit Sell GUI |
| `/sell hand` | Sell held item |
| `/bal`, `/pay`, `/baltop` | Economy |
| `/rank` | Rank menu / staff rank administration |
| `/lifesteal` | LifeSteal event menu |
| `/crates`, `/crate preview <common|rank>` | Crates and preview |
| `/spawner shop`, `/spawner info` | Spawner shop and inspection |
| `/shard`, `/shard give`, `/giveshard` | Shard wallet / staff grant |
| `/ah`, `/ah sell <price>` | Auction House |
| `/orders`, `/order create ...`, `/order claim` | Player buy orders and delivery mailbox |
| `/tool give ...` | Staff-issued Haven tools |
| `/spawnregion ...` | Staff spawn selection/protection |

## Owner setup on offline-mode

Offline-mode UUIDs do not prove identity. The configured name `Ltzpaul_` does **not** automatically grant Owner. On first start, run this in the server console (without `/`):

```text
ltzcore ownerpassword
```

Console generates a one-time password and bootstraps the Owner account. Login using `/login <one-time-password>` or `/havenlogin <one-time-password>`, then change it immediately using `/changepassword <old> <new>`. Secure the backend with a trusted proxy/firewall/whitelist; never grant staff solely by username.

## Upgrade / installation

1. Stop the server and back up the world plus the full `plugins/LTZCore/` folder.
2. Replace the old LTZCore JAR with `LTZCore-0.5.14-Haven.jar`.
3. Keep `plugins/LTZCore/database.db`; do not delete it.
4. Review `config.yml` after the upgrade. New default keys include `spawners.price-shards-per-spawner`, `amethyst-tools.prices-shards`, `crates.<type>.rewards`, and the extended `sell.prices` list.
5. Test on a staging server first and inspect `logs/latest.log`.

## Build and verification

`tools/build-offline.sh` compiles source against minimal compile-time Bukkit/Paper stubs and packages a syntax/package-check JAR. It is **not** an official Paper API compile and is **not** a live Paper integration test. GitHub Actions runs Gradle with Java 21 and the Paper 1.21.11 API to obtain the official compile artifact. This environment has no Gradle executable and cannot resolve Paper Maven; test on a real Paper 1.21.11 staging server before production.

## Performance and known limits

- One shared virtual-spawner task processes only loaded chunks and nearby players; outputs/XP are capped in config.
- 3x3/chopper tools only check LTZCore's own spawn region. They are not yet integrated with arbitrary third-party claim plugins.
- Some SQLite reads/writes still occur synchronously on the main thread. This is a small-SMP development build, not a guarantee of a fixed RAM/TPS ceiling.
- Crate rewards in this version are fixed, guaranteed bundles. Editing the counts requires editing `config.yml` and restarting/reloading as supported.
- No live Paper integration suite has been run for this build.


## Auction House and Orders GUI (0.5.4)

- `/ah` opens the public market. Click a listing to buy it; click your own listing to withdraw it and return the item.
- `/ah mine` opens your active listings, including expired active rows that can still be withdrawn.
- `/ah sell <price>` lists the item in your main hand. `/ah cancel <id>` remains available as a command fallback.
- `/orders` opens active buy orders. Choose **Tạo đơn mua**, select a material from the paginated GUI, enter the desired amount in chat, then enter the unit price (supports e.g. `5000`, `5k`, or `1m`). The total amount is escrowed at creation. Type `HỦY` to cancel the prompt.
- Existing `/order create <MATERIAL> <amount> <priceEach>` remains supported.


## LTZCore 0.5.5-Haven changes
- Reworked `/shop` into seven categories: Nether, Crystal PvP, Food, End Items, Haven 3x3 Tools, Spawners, and Shards. Blocks & Building and Ores & Minerals categories were removed entirely. Sell GUI is not listed inside `/shop`; use `/sell` separately.
- Removed the Amethyst Materials shop category. 3x3 tools keep their shard price/config path for existing installs but use Haven names.
- `/sethome` opens a 54-slot home-slot GUI. Existing slot clicks overwrite that named home at the current location; an empty slot creates `home`, `home2`, etc. `/home` and `/homes` open the selector; left-click teleports and right-click deletes. Named `/sethome <name>` and `/home <name>` remain supported.
- Haven Drill 3x3 Pickaxe mines any normal block material including dirt and sand, while refusing technical/unbreakable blocks, fluids, and protected positions. Containers, signs, skulls, and spawners remain excluded from area mining.
- Non-admin command tree and command preprocessing are allowlisted to authentication and survival/player-market commands. Authenticated staff (`staff_level >= 1`) see the full command tree; the configured owner username is promoted to Owner after successful authentication.
- No crystal-PvP combat supply category is included. The former Totem icon now opens Crystal PvP materials.

Build: `gradle clean build` with Java 21 and Paper API 1.21.11. The offline script compiles against minimal signature stubs only and does not replace an official Gradle/Paper API build.


## LTZCore 0.5.6-Haven changes

- Replaced the old Amethyst Materials/Utility shop listing with an End Items category and a Crystal PvP materials category (Totem, End Crystal, Obsidian, Respawn Anchor, Glowstone, Ender Pearl, XP Bottles).
- Removed Sell GUI from the shop categories; `/sell` remains a separate selling feature.
- Added centered `/sethome` slot GUI: click an existing home to update it, click the first empty bed slot to create the next home; `/home` and `/homes` open the saved-home selector (left-click teleport, right-click delete). Named commands such as `/sethome base` and `/home base` remain available.
- Haven Drill (3x3 pickaxe) now mines ordinary block materials in a 3x3 plane, including dirt, sand, gravel and stone, while excluding technical/unbreakable blocks, fluids, portals, containers and protected spawn blocks.
- Normal authenticated players see only survival/player commands; authenticated staff ranks see the full command tree. The configured Owner account name defaults to `Ltzpaul_` and must be initialized using the console owner-password flow before login.
- Shop/tool displays use Haven branding. Legacy `amethyst-tools` config values are accepted as fallback for existing installations.

Note: the Crystal PvP category is a Minecraft in-game materials category, not a real-world equipment guide. The build in this environment uses compile-time API stubs; run the repository Gradle/GitHub Actions build against the official Paper API and test on a staging server before production.


## LTZCore 0.5.7-Haven changes
- Removed crate menu/commands from the player-facing build.
- Updated End, Nether, Food and Crystal PvP shop catalogues; Blocks & Building and Ores & Minerals categories were removed. Nether catalogue includes Blaze Rod, Nether Wart, Glowstone Dust, Magma Cream and Ghast Tear.
- Migrates the in-game shop prices once to the archived DonutSMP-style shop values where a historical shop list is published; Haven-specific remaining catalogue prices are configured per item.
- `/rtp` searches only already-generated chunks using Paper asynchronous chunk loading (`gen=false`) while a fixed five-second countdown runs. If no safe location is ready by the deadline, it cancels instead of synchronously generating terrain or freezing the server. It avoids the protected spawn region, unsafe ground, and the configured minimum distance from world spawn. Pre-generate the RTP radius for reliable results.
- Added a 20-second PvP combat tag; quitting while tagged drops the player's inventory and armor at their logout location.
- Scoreboard now shows team (from the Bukkit main scoreboard) and live playtime. If no external scoreboard team is assigned, it displays None.
- Team management itself is not included in this release.

Build note: the included offline script compiles against minimal local API stubs. Use the GitHub Actions workflow/Gradle with Paper API 1.21.11 for the official server-API build and test on a staging server first.


## LTZCore 0.5.10-Haven changes (previous release)

- Added shared compact-money parser for command inputs: `5k` = 5,000; `2.5m` = 2,500,000; `1b`/`1B` = 1,000,000,000.
- Applied shorthand parsing to `/pay`, `/bounty`, `/ah sell`, `/order create`, order chat-price prompts, `/shard give` and money reward codes.
- Compact notation is used in balance, baltop, player info, bounty, transaction log, shop/sell/order messages and scoreboard money/shard lines.
- Scoreboard team lookup now checks the player's current scoreboard first, then the main scoreboard, and renders the team's prefix/name/suffix.
- Display examples: `$950`, `$1k`, `$2.5m`, `$1.2b`.
- Scoreboard team label reads the player's assigned Bukkit scoreboard team, including its configured prefix and suffix; if no team is assigned it displays `None`.

Amount input accepts whole currency values only. Decimal suffix input such as `2.5m` is supported when it resolves to an integer amount.


## Spawn cuboid protection (0.5.10)

Create the protected spawn cuboid in-game as an authenticated Admin/Owner:

```text
/region pos1
/region pos2
/region create
/region info
/region clear
```

`pos1` and `pos2` record the two opposite block corners at the player's current block position. `create` saves the selection in SQLite. A coordinate form is also available while standing in the target world:

```text
/region create <x1> <y1> <z1> <x2> <y2> <z2>
```

`/spawnregion` remains available for compatibility (`save` is an alias of `create`). Protection is enforced at HIGHEST priority: members cannot break/place/interact inside the cuboid; PvP and all player damage are cancelled there; creature spawns are cancelled there. Protected actions send a chat warning with a per-player 1.2-second cooldown. Staff can manage the cuboid using `/region`; region data is stored in the existing SQLite `settings` table under `spawnregion`.

## Spawn cuboid safety update (0.5.10)

The spawn protection region is a persisted, axis-aligned cuboid in the same world. Run these commands in-game as an authenticated Admin/Owner:

```text
/region pos1
/region pos2
/region create
/region info
/region clear
```

- `pos1` and `pos2` save the player's current block coordinates as opposite corners.
- `create` saves the cuboid to the existing SQLite settings row (`spawnregion`).
- `/spawnregion pos1|pos2|save|clear|info` remains compatible with previous installations.
- A direct coordinate form is available while standing in the desired world: `/region create <x1> <y1> <z1> <x2> <y2> <z2>`.

Inside the saved cuboid, non-staff players cannot break/place blocks, use items or interact with blocks/entities. Attempts to break, place, interact, ignite or attack trigger a chat warning (rate-limited per player). PvP damage and all damage to players inside the cuboid are cancelled. Creature spawns inside the cuboid are cancelled. Existing entities are not automatically removed. Authenticated staff can build/interact for maintenance; player damage remains disabled inside the region for everyone.


## LTZCore 0.5.14-Haven changes

- Rank prices are now paid with in-game economy: `+` = `$50,000`, `++` = `$100,000`, `+++` = `$500,000`. Both the rank GUI and `/rank buy <+|++|+++>` use the same configurable prices under `rank-shop.prices`. Existing staff ranks and rank ownership data are preserved.
- RTP now starts a five-second countdown immediately. Safe-position search runs in parallel and must finish before the deadline. The teleport is performed at the five-second mark if a destination is ready; otherwise the request ends with a clear timeout message.
- RTP only selects previously generated terrain. It checks `World.isChunkGenerated(x,z)` and loads existing chunks with `World.getChunkAtAsync(x,z,false,false,callback)`, so `/rtp` does not trigger chunk generation. All world/block inspection happens after the Paper callback on the main thread.
- Fixed the surface-height calculation: `getHighestBlockYAt` identifies the top surface block; the destination is now placed one block above it, with passable feet/head space.
- RTP avoids the configured minimum distance from world spawn and the protected Haven spawn cuboid. It validates solid, non-hazardous ground and retries a bounded number of candidates. Player movement during the countdown cancels the request.
- Default RTP settings: `radius: 1500`, `min-distance: 300`, `max-attempts: 80`, `cooldown-seconds: 30`, `search-window-seconds: 5`.

### RTP setup note

A strict five-second deadline cannot be guaranteed if the server has to generate new terrain or is overloaded. This release does not generate chunks during `/rtp`; pre-generate the desired radius (for example, with a chunk pregenerator while the server is quiet) so existing chunks can be loaded quickly. If the search does not find a safe location in five seconds, it fails cleanly instead of hanging or generating terrain on demand.

### Rank price configuration

```yaml
rank-shop:
  prices:
    plus: 50000
    double-plus: 100000
    triple-plus: 500000
```

These amounts are Haven in-game dollars, not VND. The legacy pending bank-transfer commands remain for old pending records but are no longer used by the `++` and `+++` rank shop buttons.


## LTZCore 0.5.14-Haven changes

- Adds an Action Bar above the hotbar showing the player's current Shard balance.
- Players with ranks `+`, `++`, or `+++` also see a live 60-second countdown to their next online Shard reward.
- The shared ticker runs once per second; it does not create one scheduler task per player.
- Configure with `shards.actionbar.enabled` and `shards.reward-per-interval`.
- Reward progress begins after successful login and resets when the player logs out or loses an eligible rank.


## LTZCore 0.5.14-Haven changes

- Shows the current Shard wallet immediately after successful login/registration, in the Action Bar, and on the sidebar scoreboard.
- Sends a chat notification with the shard amount earned and resulting wallet balance when each online-rank minute reward is paid.
- Shard code redemption and admin shard grants show the new wallet balance. `/shard` also refreshes Action Bar and sidebar.
- Cancels player item drops inside the protected spawn cuboid before an item entity is created. The item stays in the player's inventory and a throttled chat warning explains this. Staff drops are also blocked by default; set `spawn-region.allow-staff-drop: true` to allow staff drops.
- Spawn protection covers the full vertical column inside the selected X/Z area by default (`spawn-region.protect-full-height: true`), avoiding Y-coordinate gaps. Set it to `false` to use the saved minY/maxY bounds.
- No SQLite schema migration is required; existing `database.db` and player balances are preserved.



## 0.5.14 — Action Bar only shard + combat timer

- Shard balance and shard accrual notifications are no longer sent to chat. The shard wallet is displayed above the hotbar (Action Bar) only.
- The sidebar scoreboard no longer includes a shard line.
- Combat tagging is displayed only in the Action Bar as `Combat: 20s`, ticking down once per second.
- Every valid player-vs-player hit refreshes both players to a fresh 20-second timer.
- Chat notices for entering combat and shard earnings have been removed.
- The shared one-second ticker combines shard wallet/progress and combat countdown so the two displays do not continually overwrite one another.


### Shard display policy

Shard wallet balance and periodic shard earnings are only displayed in the Action Bar (above the hotbar). They are not printed to chat or the sidebar. `/shardtop` opens an inventory GUI instead of printing balances into chat.
