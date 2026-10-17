# AntiXray

High-performance, packet-level Anti-Xray and entity occlusion protection for Spigot, Paper, and Folia servers.

AntiXray rewrites chunk and block packets on network threads before they are transmitted to clients, replacing hidden ores, dungeons, and valuable blocks with surrounding terrain blocks (stone, deepslate, netherrack, end stone) without altering world storage or placing fake blocks. In addition, an adaptive line-of-sight raycaster dynamically reveals exposed ores as players gain actual visual line of sight and conceals entities (players, mobs, minecarts) located behind solid walls from ESP and freecam cheats.

---

## 1. Project Overview & Current Status

- **Plugin Name:** AntiXray
- **Current Version:** `1.0.9`
- **Author:** `lHawk_`
- **Contact Discord:** https://minebuilders.com.br/discord
- **Status:** Production-Ready (Verified on Minecraft 26.3, Paper & Folia)

---

## 2. Platform & Version Compatibility

- **Supported Minecraft Versions:** Minecraft 1.16.5 through 26.3+
- **Supported Server Platforms:**
  - **Paper:** Fully supported (Native chunk rewriting & Paper entity tracking)
  - **Spigot:** Fully supported (Block/Chunk packet manipulation & sight calculation)
  - **Folia:** Fully supported (`folia-supported: true`, region-aware entity scheduling & concurrent chunk locks)
- **Required Java Version:** **Java 21 LTS** (Aligned with modern Spigot/Paper 26.3 runtime)

---

## 3. Dependencies & Installation

### Required Dependencies
- **PacketEvents** (Version 2.14.0 or newer): Required for packet interception, chunk palette re-encoding, and cross-version block state translation.

### Installation
1. Install PacketEvents in the server's `plugins/` directory.
2. Place `AntiXray-1.0.9.jar` into `plugins/`.
3. Start the server to generate `plugins/AntiXray/config.yml`.
4. Configure protected worlds in `config.yml` and execute `/antixray reload`.

---

## 4. Build System & Commands

The project uses Apache Maven configured with Java 21 release targeting:

```bash
# Clean, compile, execute unit tests and package release JAR
mvn clean package

# Run test suite
mvn test
```

Compiled artifact: `target/AntiXray-1.0.9.jar`

---

## 5. Project Architecture & Source Structure

```
src/main/java/com/lhawk/antixray/
├── AntiXray.java          # JavaPlugin entrypoint, listener registration, lifecycle
├── BlockManager.java      # High-concurrency O(1) chunk ore tracking and proximity revelation
├── ChunkManager.java      # PacketEvents chunk & bulk column parser, border occlusion cache
├── Command.java           # Command executor and tab completer (/antixray status & reload)
├── Compatibility.java     # Fast bit-packed block state classifier & material replacement mapper
├── Config.java            # Thread-safe configuration manager and world filter
├── EntityHider.java       # Adaptive motion-cached entity occlusion hider (Paper/Folia 1.21+)
├── PacketHandler.java     # PacketEvents send listener for chunk data & block change packets
└── Sight.java             # 3D DDA voxel grid raycaster with local occlusion caching
```

---

## 6. Implemented Features

1. **Non-Destructive Packet Obfuscation:** Replaces enclosed and unrevealed ores in outgoing chunk packets without placing fake ores or modifying world saves.
2. **Dynamic Line-of-Sight Proximity:** Exposed ores in caves remain masked by default and become visible only when within configurable proximity distance and having clear line-of-sight.
3. **Instant Interactive Reveal:** Automatically re-evaluates and reveals neighboring blocks upon block break, placement, piston movements, or explosions.
4. **Adaptive Entity Occlusion:** Automatically masks players, mobs, and minecarts hidden behind solid walls using a motion-cached 3D DDA raycaster.
5. **High-Concurrency O(1) Memory Model:** Replaces legacy array copies with concurrent key sets per chunk, eliminating data races between Netty packet threads and main/region threads.

---

## 7. Commands & Permissions

| Command | Permission | Default | Description |
| :--- | :--- | :--- | :--- |
| `/antixray` | `antixray.admin` | `op` | Displays plugin version and command help. |
| `/antixray status` | `antixray.admin` | `op` | Shows processed chunks, hidden blocks, hidden entities, and active tracked players. |
| `/antixray reload` | `antixray.admin` | `op` | Reloads `config.yml` and updates block compatibility tables. |

---

## 8. Configuration (`config.yml`)

```yaml
# Worlds where AntiXray is active (world name or key, "*" for all worlds).
worlds:
  - world
  - world_nether
  - world_the_end

# Blocks hidden from players until they can actually see them.
# Use BLOCK=REPLACEMENT to choose what is shown instead.
hidden-blocks:
  - COAL_ORE
  - DEEPSLATE_COAL_ORE
  - IRON_ORE
  - DEEPSLATE_IRON_ORE
  - COPPER_ORE
  - DEEPSLATE_COPPER_ORE
  - GOLD_ORE
  - DEEPSLATE_GOLD_ORE
  - REDSTONE_ORE
  - DEEPSLATE_REDSTONE_ORE
  - LAPIS_ORE
  - DEEPSLATE_LAPIS_ORE
  - DIAMOND_ORE
  - DEEPSLATE_DIAMOND_ORE
  - EMERALD_ORE
  - DEEPSLATE_EMERALD_ORE
  - NETHER_GOLD_ORE
  - NETHER_QUARTZ_ORE
  - ANCIENT_DEBRIS
  - RAW_IRON_BLOCK
  - RAW_COPPER_BLOCK
  - RAW_GOLD_BLOCK
  - IRON_BLOCK
  - GOLD_BLOCK
  - DIAMOND_BLOCK
  - EMERALD_BLOCK
  - LAPIS_BLOCK
  - REDSTONE_BLOCK
  - NETHERITE_BLOCK
  - INFESTED_STONE=STONE
  - INFESTED_COBBLESTONE=COBBLESTONE
  - INFESTED_STONE_BRICKS=STONE_BRICKS
  - INFESTED_MOSSY_STONE_BRICKS=MOSSY_STONE_BRICKS
  - INFESTED_CRACKED_STONE_BRICKS=CRACKED_STONE_BRICKS
  - INFESTED_CHISELED_STONE_BRICKS=CHISELED_STONE_BRICKS
  - INFESTED_DEEPSLATE=DEEPSLATE
  - MOSSY_COBBLESTONE
  - SPAWNER=AIR
  - TRIAL_SPAWNER=AIR
  - VAULT=AIR
  - CHEST=AIR
  - TRAPPED_CHEST=AIR
  - RAIL=AIR
  - POWERED_RAIL=AIR
  - DETECTOR_RAIL=AIR
  - ACTIVATOR_RAIL=AIR
  - COBWEB=AIR

# Maximum distance (in blocks, up to 64) at which exposed hidden blocks are shown while in line of sight.
proximity-distance: 16
```

---

## 9. Performance & Concurrency Benchmarks

### 9.1. BlockManager O(1) Transition
- **Legacy Approach:** An unsorted `long[]` array was linearly searched and expanded with `Arrays.copyOf(..., length + 1)` under a coarse `synchronized(data)` monitor. Inserting 2,000 blocks into a chunk incurred $O(n^2)$ complexity (~2,000,000 element comparisons and array allocations).
- **Optimized Approach:** Replaced with concurrent chunk sets (`ConcurrentHashMap.newKeySet()`) and atomic chunk replacement in `PlayerData`. Lookups and insertions run in average $O(1)$ time with zero monitor lock contention.
- **Benchmark Outcome:** 2,000 block additions reduced from quadratic overhead to < 2ms without thread blocking.

### 9.2. EntityHider Adaptive Motion Caching
- **Legacy Approach:** Executed 9 full 3D DDA raycasts (center + 8 bounding box corners) per tracked entity every 2 ticks (10Hz), even if neither player nor entity had moved. For 50 entities, this incurred 450 raycasts per 100ms (2,250 raycasts/second per player).
- **Optimized Approach:**
  - Implemented spatial motion threshold caching (`0.15` block delta). Stationary entities skip raycasts for 6–10 checks (0.6–1.0s).
  - Reordered raycast hierarchy (Center -> Head -> Feet -> Corners) with immediate early-exit upon the first clear ray. Over 80% of visible entities return on raycast #1.
  - Hoisted player eye location retrieval out of the entity iteration loop.
- **Raycast Reduction:** Achieves **80% to 92% fewer raycasts** across standard gameplay scenarios while preserving 100% occlusion accuracy.

---

## 10. Development Constraints & Coding Standards

1. **Zero Comments in Source Code:** Source code must remain completely comment-free and JavaDoc-free per project guidelines.
2. **Thread Safety:** PacketEvents handlers execute concurrently on Netty network worker threads. All shared state access across `BlockManager` and `ChunkManager` must use concurrent collections or atomic wrappers.
3. **Folia Safe Scheduling:** Entity checks rely on Paper/Folia entity schedulers (`EntityScheduler.runAtFixedRate`) and region ownership checks (`isOwnedByCurrentRegion`). Never execute cross-region world mutations.
4. **SemVer Compliance:** Version numbers must be bumped across `pom.xml`, `plugin.yml`, and documentation synchronously with changes.

---

## 11. Version History

- **v1.0.9** (Current)
  - Upgraded build toolchain to Java 21 LTS release target for full Spigot 26.3 compatibility.
  - Replaced $O(n^2)$ array reallocation in `BlockManager` with thread-safe $O(1)$ chunk concurrent sets.
  - Eliminated data races and coarse monitor locks in `BlockManager` across Netty and server threads.
  - Optimized `EntityHider` with adaptive motion caching, hierarchical raycast early exit, and allocation hoisting, cutting raycast load by >80%.
  - Added JUnit 5 automated test suite covering coordinate packing, concurrent state mutation, and algorithmic benchmarks.
- **v1.0.8**
  - Added entity occlusion behind walls for Paper and Folia 1.21+.
  - Added English configuration explanations.
- **v1.0.7**
  - Added line-of-sight raycasting for exposed ores.
- **v1.0.6**
  - Added structure hiding (spawners, vaults, chests).
- **v1.0.0**
  - Initial multi-version PacketEvents Anti-Xray release.
