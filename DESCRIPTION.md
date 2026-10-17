<center>
<h2>AntiXray</h2>
<p><strong>Advanced packet-level Anti-Xray and adaptive entity occlusion for Spigot, Paper and Folia</strong></p>
<p>Author: <strong>lHawk_</strong> | Contact: <a href="https://minebuilders.com.br/discord">https://minebuilders.com.br/discord</a></p>
</center>

<hr>

<h2>⛏ What is AntiXray?</h2>

<p>AntiXray stops X-ray texture packs, freecam and ESP cheats <strong>without placing fake ores into your world save</strong>. Outgoing chunk data is processed on asynchronous network threads via PacketEvents before packets reach the client. Valuable ores and dungeon blocks turn into natural surrounding stone, deepslate, netherrack, or end stone, and are only revealed when the player <strong>can actually see them</strong>.</p>

<p>Exposed ores in caves are validated using real <strong>line of sight</strong> raycasting. If solid terrain stands between the player and an ore block, the block stays masked as stone. Once the player walks away, it is securely re-masked. In addition, <strong>players, mobs, and minecarts</strong> behind walls are hidden dynamically using an adaptive, motion-cached occlusion engine, neutralizing wallhacks and radar cheats.</p>

<hr>

<h2>⚡ Core Features</h2>

<ul>
<li><strong>Non-Destructive Packet Obfuscation:</strong> The world files on disk are never modified. Only outbound network packets are dynamically rewritten.</li>
<li><strong>Dynamic Line of Sight:</strong> Exposed ores in caves stay hidden until a player has a direct, unblocked line of sight within the proximity threshold.</li>
<li><strong>Anti-ESP Entity Occlusion:</strong> Players, mobs, and minecarts behind walls are not sent to clients (Paper and Folia 1.21+).</li>
<li><strong>Adaptive Motion Caching:</strong> High-performance motion-based raycast engine reduces CPU raycasting overhead by over 80% for stationary entities.</li>
<li><strong>Lock-Free O(1) Chunk Storage:</strong> Advanced concurrent data architecture guarantees thread-safe, race-free operations across Netty packet pipelines and Folia region threads.</li>
<li><strong>Comprehensive Masking:</strong> Hides ores, raw metal blocks, refined mineral blocks, mob spawners, trial chambers, vaults, chests, rails, cobwebs, and silverfish infested blocks.</li>
<li><strong>Instant Interactive Reveal:</strong> Mining, explosions, and piston movements automatically re-evaluate line of sight and reveal exposed blocks immediately.</li>
<li><strong>Full Folia Support:</strong> Region-aware scheduling and concurrent chunk boundaries prevent server tick lag and cross-region deadlocks.</li>
</ul>

<hr>

<h2>✦ Hidden Blocks by Default</h2>

<ul>
<li><strong>Ores:</strong> Coal, Iron, Copper, Gold, Redstone, Lapis, Diamond, Emerald, Deepslate variants, Nether Gold, Nether Quartz, and Ancient Debris.</li>
<li><strong>Storage &amp; Mineral Blocks:</strong> Raw Iron, Raw Copper, Raw Gold blocks, plus Iron, Gold, Diamond, Emerald, Lapis, Redstone, and Netherite blocks.</li>
<li><strong>Structures &amp; Dungeons:</strong> Mob Spawners, Trial Spawners, Vaults, and Mossy Cobblestone.</li>
<li><strong>Loot &amp; Mineshafts:</strong> Regular Chests, Trapped Chests, Minecart Rails, and Cobwebs.</li>
<li><strong>Infested Blocks:</strong> Masked as regular stone or deepslate to preserve surprise traps.</li>
<li><strong>Entities:</strong> Players, living mobs, and minecarts concealed behind solid walls.</li>
</ul>

<hr>

<h2>⚙ Configuration (config.yml)</h2>

<pre><code>worlds:
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

proximity-distance: 16</code></pre>

<p>Use <code>BLOCK=REPLACEMENT</code> to customize the visual substitute for any block, and <code>proximity-distance</code> to configure the maximum detection radius in blocks (up to 64).</p>

<hr>

<h2>⌨ Commands &amp; Permissions</h2>

<ul>
<li><code>/antixray</code> - Displays plugin information and command help.</li>
<li><code>/antixray status</code> - Displays real-time statistics on processed chunks, hidden blocks, active entity occlusions, and cached players.</li>
<li><code>/antixray reload</code> - Reloads configuration and refreshes block state lookup tables.</li>
</ul>

<p>All administrative commands require the <code>antixray.admin</code> permission (default: OP).</p>

<hr>

<h2>⬇ Installation &amp; Requirements</h2>

<ol>
<li>Download and install <strong>PacketEvents</strong> (version 2.14.0 or newer) in the <code>plugins/</code> folder.</li>
<li>Place <strong>AntiXray.jar</strong> in the <code>plugins/</code> folder.</li>
<li>Ensure your server runs on <strong>Java 21 LTS</strong> or newer.</li>
<li>Start or restart your server.</li>
<li>Verify protected worlds in <code>config.yml</code> and execute <code>/antixray reload</code>.</li>
</ol>

<hr>

<h2>📊 Performance &amp; Scalability</h2>

<p>AntiXray was built for high-traffic servers:</p>
<ul>
<li><strong>O(1) Concurrent Sets:</strong> Zero quadratic array allocations when processing multi-block changes or chunk batches.</li>
<li><strong>Voxel DDA Acceleration:</strong> Optimized 3D grid traversal terminates early as soon as any bounding point is verified visible.</li>
<li><strong>Zero RAM Bloat:</strong> Memory structures are automatically purged when chunks unload or players disconnect.</li>
</ul>

<p><strong>Crafted with excellence by lHawk_</strong></p>
