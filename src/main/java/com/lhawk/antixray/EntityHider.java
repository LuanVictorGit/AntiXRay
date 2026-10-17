package com.lhawk.antixray;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

final class EntityHider implements Listener {

    private static final long CHECK_TICKS = 2;
    private static final int HIDE_AFTER_MISSES = 2;
    private static final double MOTION_THRESHOLD_SQ = 0.0225;
    private static final int STATIONARY_VISIBLE_INTERVAL = 10;
    private static final int STATIONARY_HIDDEN_INTERVAL = 6;
    private static final double FAR_DISTANCE_SQ = 36.0 * 36.0;

    private final AntiXray plugin;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<UUID, Viewer>();

    private final Class<? extends Event> trackEvent;
    private final Class<? extends Event> untrackEvent;
    private final Method trackEventEntity;
    private final Method untrackEventEntity;
    private final Method getHandle;
    private final Method getTrackedEntity;
    private final Method removePlayer;
    private final Method updatePlayer;
    private final Method getScheduler;
    private final Method runAtFixedRate;
    private final Method cancelTask;
    private final Method isOwnedByCurrentRegion;
    private volatile boolean disabled;

    private EntityHider(AntiXray plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        ClassLoader server = Bukkit.getServer().getClass().getClassLoader();
        trackEvent = Class.forName("io.papermc.paper.event.player.PlayerTrackEntityEvent").asSubclass(Event.class);
        untrackEvent = Class.forName("io.papermc.paper.event.player.PlayerUntrackEntityEvent").asSubclass(Event.class);
        trackEventEntity = trackEvent.getMethod("getEntity");
        untrackEventEntity = untrackEvent.getMethod("getEntity");
        getHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity", false, server).getMethod("getHandle");
        getTrackedEntity = Class.forName("net.minecraft.world.entity.Entity", false, server).getMethod("moonrise$getTrackedEntity");
        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, server);
        removePlayer = getTrackedEntity.getReturnType().getMethod("removePlayer", serverPlayer);
        updatePlayer = getTrackedEntity.getReturnType().getMethod("updatePlayer", serverPlayer);
        getScheduler = Entity.class.getMethod("getScheduler");
        runAtFixedRate = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
            .getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
        cancelTask = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask").getMethod("cancel");
        isOwnedByCurrentRegion = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
    }

    static EntityHider create(AntiXray plugin) {
        try {
            return new EntityHider(plugin);
        } catch (Throwable t) {
            return null;
        }
    }

    void register() {
        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);
        pm.registerEvent(trackEvent, this, EventPriority.HIGH, (listener, event) -> onTrack(event), plugin, true);
        pm.registerEvent(untrackEvent, this, EventPriority.MONITOR, (listener, event) -> onUntrack(event), plugin, false);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            viewer(player);
        }
    }

    void shutdown() {
        for (Viewer viewer : viewers.values()) {
            cancel(viewer);
        }
        viewers.clear();
    }

    int getHiddenCount() {
        int count = 0;
        for (Viewer viewer : viewers.values()) {
            for (Tracked tracked : viewer.entities.values()) {
                if (!tracked.shown) count++;
            }
        }
        return count;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        viewer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Viewer viewer = viewers.remove(event.getPlayer().getUniqueId());
        if (viewer != null) cancel(viewer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Viewer viewer = viewers.get(event.getPlayer().getUniqueId());
        if (viewer != null) viewer.entities.clear();
    }

    private void onTrack(Event event) {
        if (disabled || !trackEvent.isInstance(event)) return;
        try {
            Player player = ((PlayerEvent) event).getPlayer();
            Entity entity = (Entity) trackEventEntity.invoke(event);
            if (!isHideable(entity)) return;

            Viewer viewer = viewer(player);
            Tracked tracked = viewer.entities.get(entity.getEntityId());
            if (tracked == null || tracked.entity != entity) {
                tracked = new Tracked(entity);
                viewer.entities.put(entity.getEntityId(), tracked);
            }
            Location eye = player.getEyeLocation();
            boolean show = tracked.forced != null ? tracked.forced : shouldShow(player, entity, eye.getX(), eye.getY(), eye.getZ(), null, tracked);
            tracked.shown = show;
            tracked.tracking = true;
            tracked.misses = 0;
            if (!show) ((Cancellable) event).setCancelled(true);
        } catch (Throwable t) {
            disable(t);
        }
    }

    private void onUntrack(Event event) {
        if (!untrackEvent.isInstance(event)) return;
        try {
            Viewer viewer = viewers.get(((PlayerEvent) event).getPlayer().getUniqueId());
            if (viewer == null) return;
            Entity entity = (Entity) untrackEventEntity.invoke(event);
            Tracked tracked = viewer.entities.get(entity.getEntityId());
            if (tracked != null && tracked.forced == null) {
                viewer.entities.remove(entity.getEntityId(), tracked);
            }
        } catch (Throwable t) {
            disable(t);
        }
    }

    private void check(Viewer viewer) {
        if (disabled || viewer.entities.isEmpty()) return;
        Player player = viewer.player;
        if (!player.isOnline()) return;

        Location eye = player.getEyeLocation();
        double eyeX = eye.getX();
        double eyeY = eye.getY();
        double eyeZ = eye.getZ();
        Sight sight = null;

        for (Tracked tracked : viewer.entities.values()) {
            Entity entity = tracked.entity;
            if (!entity.isValid() || entity.getWorld() != player.getWorld()) {
                viewer.entities.remove(entity.getEntityId(), tracked);
                continue;
            }
            if (!isOwned(entity)) continue;

            if (sight == null) {
                sight = new Sight(player.getWorld(), plugin.getCompatibility());
            }

            if (shouldShow(player, entity, eyeX, eyeY, eyeZ, sight, tracked)) {
                tracked.misses = 0;
                if (!tracked.shown) resync(viewer, tracked, true);
            } else if (tracked.shown && ++tracked.misses >= HIDE_AFTER_MISSES) {
                resync(viewer, tracked, false);
            }
        }
    }

    private boolean isHideable(Entity entity) {
        return (entity instanceof LivingEntity && !(entity instanceof ArmorStand)) || entity instanceof Minecart;
    }

    private boolean shouldShow(Player player, Entity entity, double eyeX, double eyeY, double eyeZ, Sight sight, Tracked tracked) {
        if (player.getGameMode() == GameMode.SPECTATOR || entity.isGlowing()) return true;
        if (!plugin.getConfiguration().isWorldProtected(player.getWorld())) return true;
        if (entity.equals(player.getVehicle()) || entity.getPassengers().contains(player) || player.getPassengers().contains(entity)) {
            return true;
        }

        BoundingBox box = entity.getBoundingBox();
        double cx = box.getCenterX();
        double cy = box.getCenterY();
        double cz = box.getCenterZ();

        double dx = cx - eyeX;
        double dy = cy - eyeY;
        double dz = cz - eyeZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq <= 2.25) return true;

        double pMoveSq = (eyeX - tracked.lastPlayerX) * (eyeX - tracked.lastPlayerX)
            + (eyeY - tracked.lastPlayerY) * (eyeY - tracked.lastPlayerY)
            + (eyeZ - tracked.lastPlayerZ) * (eyeZ - tracked.lastPlayerZ);
        double eMoveSq = (cx - tracked.lastEntityX) * (cx - tracked.lastEntityX)
            + (cy - tracked.lastEntityY) * (cy - tracked.lastEntityY)
            + (cz - tracked.lastEntityZ) * (cz - tracked.lastEntityZ);

        boolean moved = pMoveSq > MOTION_THRESHOLD_SQ || eMoveSq > MOTION_THRESHOLD_SQ;
        if (!moved) {
            tracked.checkCounter++;
            int maxInterval = tracked.lastVisible ? STATIONARY_VISIBLE_INTERVAL : STATIONARY_HIDDEN_INTERVAL;
            if (tracked.checkCounter < maxInterval) {
                return tracked.lastVisible;
            }
        } else if (distSq > FAR_DISTANCE_SQ) {
            tracked.checkCounter++;
            if ((tracked.checkCounter & 1) != 0) {
                return tracked.lastVisible;
            }
        }

        if (sight == null) {
            sight = new Sight(player.getWorld(), plugin.getCompatibility());
        }

        if (sight.canSee(eyeX, eyeY, eyeZ, cx, cy, cz)) {
            updateTracked(tracked, eyeX, eyeY, eyeZ, cx, cy, cz, true);
            return true;
        }

        double rx = box.getWidthX() * 0.4;
        double ry = box.getHeight() * 0.45;
        double rz = box.getWidthZ() * 0.4;

        if (sight.canSee(eyeX, eyeY, eyeZ, cx, cy + ry * 0.8, cz)) {
            updateTracked(tracked, eyeX, eyeY, eyeZ, cx, cy, cz, true);
            return true;
        }

        if (sight.canSee(eyeX, eyeY, eyeZ, cx, cy - ry * 0.8, cz)) {
            updateTracked(tracked, eyeX, eyeY, eyeZ, cx, cy, cz, true);
            return true;
        }

        for (int corner = 0; corner < 8; corner++) {
            double x = cx + ((corner & 1) == 0 ? -rx : rx);
            double y = cy + ((corner & 2) == 0 ? -ry : ry);
            double z = cz + ((corner & 4) == 0 ? -rz : rz);
            if (sight.canSee(eyeX, eyeY, eyeZ, x, y, z)) {
                updateTracked(tracked, eyeX, eyeY, eyeZ, cx, cy, cz, true);
                return true;
            }
        }

        updateTracked(tracked, eyeX, eyeY, eyeZ, cx, cy, cz, false);
        return false;
    }

    private void updateTracked(Tracked tracked, double px, double py, double pz, double ex, double ey, double ez, boolean visible) {
        tracked.lastPlayerX = px;
        tracked.lastPlayerY = py;
        tracked.lastPlayerZ = pz;
        tracked.lastEntityX = ex;
        tracked.lastEntityY = ey;
        tracked.lastEntityZ = ez;
        tracked.lastVisible = visible;
        tracked.checkCounter = 0;
    }

    private void resync(Viewer viewer, Tracked tracked, boolean show) {
        try {
            Object tracker = getTrackedEntity.invoke(getHandle.invoke(tracked.entity));
            if (tracker == null) return;
            Object handle = getHandle.invoke(viewer.player);
            tracked.forced = show;
            tracked.tracking = false;
            removePlayer.invoke(tracker, handle);
            updatePlayer.invoke(tracker, handle);
            if (!tracked.tracking) {
                viewer.entities.remove(tracked.entity.getEntityId(), tracked);
            }
        } catch (Throwable t) {
            disable(t);
        } finally {
            tracked.forced = null;
        }
    }

    private boolean isOwned(Entity entity) {
        try {
            return (Boolean) isOwnedByCurrentRegion.invoke(null, entity);
        } catch (Throwable t) {
            return true;
        }
    }

    private Viewer viewer(Player player) {
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer != null) return viewer;
        viewer = new Viewer(player);
        Viewer existing = viewers.putIfAbsent(player.getUniqueId(), viewer);
        if (existing != null) return existing;

        final Viewer created = viewer;
        try {
            Consumer<Object> task = scheduled -> check(created);
            Runnable retired = () -> viewers.remove(player.getUniqueId(), created);
            created.task = runAtFixedRate.invoke(getScheduler.invoke(player), plugin, task, retired, 1L, CHECK_TICKS);
        } catch (Throwable t) {
            disable(t);
        }
        return created;
    }

    private void cancel(Viewer viewer) {
        if (viewer.task == null) return;
        try {
            cancelTask.invoke(viewer.task);
        } catch (Throwable ignored) {
        }
    }

    private void disable(Throwable t) {
        if (disabled) return;
        disabled = true;
        plugin.getLogger().log(Level.WARNING, "Entity hiding disabled after an error", t);
    }

    private static final class Viewer {
        final Player player;
        final Map<Integer, Tracked> entities = new ConcurrentHashMap<Integer, Tracked>();
        volatile Object task;

        Viewer(Player player) {
            this.player = player;
        }
    }

    private static final class Tracked {
        final Entity entity;
        volatile boolean shown = true;
        volatile boolean tracking;
        volatile Boolean forced;
        int misses;
        double lastPlayerX, lastPlayerY, lastPlayerZ;
        double lastEntityX, lastEntityY, lastEntityZ;
        int checkCounter;
        boolean lastVisible = true;

        Tracked(Entity entity) {
            this.entity = entity;
        }
    }
}
