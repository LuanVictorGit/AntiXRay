[CENTER]
[SIZE=6][B]AntiXray[/B][/SIZE]
[SIZE=4][B]Advanced Packet-Level Anti-Xray & Adaptive Entity Occlusion[/B][/SIZE]
[SIZE=3]Compatible with Spigot, Paper & Folia (1.16.5 - 26.3+)[/SIZE]
[SIZE=2]Author: [B]lHawk_[/B] | Support: [URL='https://minebuilders.com.br/discord']https://minebuilders.com.br/discord[/URL][/SIZE]
[/CENTER]

[SIZE=5][B]Overview[/B][/SIZE]
AntiXray stops X-ray texture packs, freecam and ESP cheats without placing fake ores into your world files. Outgoing chunk data is processed on asynchronous network threads via PacketEvents before packets reach the client. Valuable ores and dungeon blocks turn into natural surrounding stone, deepslate, netherrack, or end stone, and are only revealed when the player can actually see them.

Exposed ores in caves are validated using real line of sight raycasting. If solid terrain stands between the player and an ore block, the block stays masked as stone. Once the player walks away, it is securely re-masked. In addition, players, mobs, and minecarts behind walls are hidden dynamically using an adaptive, motion-cached occlusion engine, neutralizing wallhacks and radar cheats.

[SIZE=5][B]Key Features[/B][/SIZE]
[B]Non-Destructive Packet Obfuscation:[/B] The world files on disk are never modified. Only outbound network packets are dynamically rewritten.
[B]Dynamic Line of Sight:[/B] Exposed ores in caves stay hidden until a player has a direct, unblocked line of sight within the proximity threshold.
[B]Anti-ESP Entity Occlusion:[/B] Players, mobs, and minecarts behind walls are not sent to clients (Paper and Folia 1.21+).
[B]Adaptive Motion Caching:[/B] High-performance motion-based raycast engine reduces CPU raycasting overhead by over 80% for stationary entities.
[B]Lock-Free O(1) Chunk Storage:[/B] Advanced concurrent data architecture guarantees thread-safe, race-free operations across Netty packet pipelines and Folia region threads.
[B]Comprehensive Masking:[/B] Hides ores, raw metal blocks, refined mineral blocks, mob spawners, trial chambers, vaults, chests, rails, cobwebs, and silverfish infested blocks.
[B]Instant Interactive Reveal:[/B] Mining, explosions, and piston movements automatically re-evaluate line of sight and reveal exposed blocks immediately.
[B]Full Folia Support:[/B] Region-aware scheduling and concurrent chunk boundaries prevent server tick lag and cross-region deadlocks.

[SIZE=5][B]Hidden Blocks by Default[/B][/SIZE]
[B]Ores:[/B] Coal, Iron, Copper, Gold, Redstone, Lapis, Diamond, Emerald, Deepslate variants, Nether Gold, Nether Quartz, and Ancient Debris.
[B]Storage Blocks:[/B] Raw Iron, Raw Copper, Raw Gold blocks, plus Iron, Gold, Diamond, Emerald, Lapis, Redstone, and Netherite blocks.
[B]Structures & Dungeons:[/B] Mob Spawners, Trial Spawners, Vaults, and Mossy Cobblestone.
[B]Loot & Mineshafts:[/B] Regular Chests, Trapped Chests, Minecart Rails, and Cobwebs.
[B]Infested Blocks:[/B] Masked as regular stone or deepslate to preserve surprise traps.
[B]Entities:[/B] Players, living mobs, and minecarts concealed behind solid walls.

[SIZE=5][B]Commands & Permissions[/B][/SIZE]
[B]/antixray[/B] - Displays plugin information and version.
[B]/antixray status[/B] - Displays real-time statistics on processed chunks, hidden blocks, and occlusions.
[B]/antixray reload[/B] - Reloads config.yml and refreshes state tables.
All commands require permission: [B]antixray.admin[/B] (default: OP)

[SIZE=5][B]Configuration (config.yml)[/B][/SIZE]
[CODE]
worlds:
  - world
  - world_nether
  - world_the_end

hidden-blocks:
  - DIAMOND_ORE
  - DEEPSLATE_DIAMOND_ORE
  - ANCIENT_DEBRIS
  - CHEST=AIR
  - SPAWNER=AIR
  - INFESTED_STONE=STONE

proximity-distance: 16
[/CODE]

[SIZE=5][B]Installation[/B][/SIZE]
1. Install PacketEvents (2.14.0+) in the plugins folder.
2. Place AntiXray.jar into the plugins folder.
3. Ensure the server runs on Java 21 LTS or newer.
4. Start the server and configure protected worlds in config.yml.

[SIZE=4][B]Enjoying the plugin?[/B][/SIZE]

If you enjoy using this plugin, please consider leaving a five-star rating and sharing your experience in the reviews section. Your honest feedback helps support development and helps other server owners discover the plugin. Thank you for your support!
