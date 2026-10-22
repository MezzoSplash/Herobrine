package nl.meneertjes.herobrine;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class HerobrinePlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final class Apparition {
        final UUID target; final Mannequin entity; final long spawnedAt;
        Apparition(UUID t, Mannequin e) { target = t; entity = e; spawnedAt = System.currentTimeMillis(); }
    }

    private NamespacedKey key;
    private final Map<UUID, Apparition> active = new HashMap<>();
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private BukkitTask rollTask, tickTask;
    private ResolvableProfile profile;

    @Override public void onEnable() {
        key = new NamespacedKey(this, "apparition");
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("herobrine")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        cleanupStray();
        loadSettings();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 2L);
    }

    @Override public void onDisable() {
        for (Apparition a : new ArrayList<>(active.values())) remove(a, false);
        active.clear();
    }

    private void loadSettings() {
        reloadConfig();
        String name = getConfig().getString("skin-name", "");
        String val = getConfig().getString("skin-value", "");
        String sig = getConfig().getString("skin-signature", "");
        if (name != null && !name.isBlank()) {
            profile = ResolvableProfile.resolvableProfile().name(name).build();
        } else if (val != null && !val.isBlank()) {
            PlayerProfile p = Bukkit.createProfile(UUID.fromString("b64e9bc8-e153-4fc9-87b5-bd5ce25a8f82"), "Herobrine");
            p.setProperty(new ProfileProperty("textures", val, (sig == null || sig.isBlank()) ? null : sig));
            profile = ResolvableProfile.resolvableProfile(p);
        } else profile = null;
        if (rollTask != null) rollTask.cancel();
        long period = Math.max(20L, getConfig().getLong("check-interval-seconds", 300) * 20L);
        rollTask = Bukkit.getScheduler().runTaskTimer(this, this::roll, period, period);
    }

    private void cleanupStray() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(Mannequin.class))
            if (e.getPersistentDataContainer().has(key)) e.remove();
    }

    private boolean eligible(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL || p.isDead()) return false;
        if (active.containsKey(p.getUniqueId())) return false;
        List<String> worlds = getConfig().getStringList("worlds");
        if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) return false;
        for (String ex : getConfig().getStringList("excluded-players")) if (ex.equalsIgnoreCase(p.getName())) return false;
        long cd = getConfig().getLong("player-cooldown-seconds", 900) * 1000L;
        Long last = lastSeen.get(p.getUniqueId());
        return last == null || System.currentTimeMillis() - last >= cd;
    }

    private boolean nightOrCave(Player p) {
        long t = p.getWorld().getTime();
        boolean night = p.getWorld().getEnvironment() == World.Environment.NORMAL && t > 13000 && t < 23000;
        return night || p.getEyeLocation().getBlock().getLightFromSky() < 4;
    }

    private void roll() {
        if (getConfig().getBoolean("debug", false)) getLogger().info("roll: enabled=" + getConfig().getBoolean("enabled", true) + " players=" + Bukkit.getOnlinePlayers().size());
        if (!getConfig().getBoolean("enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!eligible(p)) continue;
            boolean dark = nightOrCave(p);
            if (getConfig().getBoolean("only-night-or-cave", false) && !dark) continue;
            double c = getConfig().getDouble("chance", 0.1) * (dark ? getConfig().getDouble("night-or-cave-multiplier", 2.0) : 1.0);
            if (ThreadLocalRandom.current().nextDouble() < c) {
                String err = spawnFor(p);
                if (err != null) getLogger().fine("Roll for " + p.getName() + " failed: " + err);
            }
        }
    }

    /** @return null on success, otherwise a reason. */
    String spawnFor(Player p) { return spawnFor(p, null); }

    String spawnFor(Player p, Location forced) {
        Apparition old = active.get(p.getUniqueId());
        if (old != null) remove(old, false);
        Location loc = forced != null ? forced : findSpot(p);
        if (loc == null) return "no suitable spot found";
        Vector toPlayer = p.getLocation().toVector().subtract(loc.toVector());
        loc.setDirection(toPlayer); loc.setPitch(0f);
        Mannequin m = loc.getWorld().spawn(loc, Mannequin.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setGravity(false);
            e.setCollidable(false);
            e.setImmovable(true);
            e.setCustomNameVisible(false);
            e.setDescription(null);
            e.setRemoveWhenFarAway(false);
            if (profile != null) e.setProfile(profile);
            e.getPersistentDataContainer().set(key, PersistentDataType.STRING, p.getUniqueId().toString());
        });
        p.showEntity(this, m);
        active.put(p.getUniqueId(), new Apparition(p.getUniqueId(), m));
        lastSeen.put(p.getUniqueId(), System.currentTimeMillis());
        getLogger().info("Herobrine appeared for " + p.getName() + " at " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
        return null;
    }

    private Location findSpot(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double min = getConfig().getDouble("min-distance", 30), max = getConfig().getDouble("max-distance", 50);
        double fMin = getConfig().getDouble("fov-offset-min", 45), fMax = getConfig().getDouble("fov-offset-max", 70);
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Location fallback = null;
        for (int i = 0; i < 40; i++) {
            double off = fMin + r.nextDouble() * Math.max(0, fMax - fMin);
            if (i >= 25) off = r.nextDouble() * 360; // relax: anywhere around
            double yaw = Math.toRadians(p.getLocation().getYaw() + (r.nextBoolean() ? off : -off));
            double d = min + r.nextDouble() * Math.max(0, max - min);
            int x = (int) Math.floor(p.getLocation().getX() - Math.sin(yaw) * d);
            int z = (int) Math.floor(p.getLocation().getZ() + Math.cos(yaw) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int py = p.getLocation().getBlockY();
            for (int dy = 0; dy <= 24; dy++) {
                for (int s : new int[]{dy, -dy}) {
                    int y = py + s;
                    if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) continue;
                    Block ground = w.getBlockAt(x, y - 1, z);
                    Block feet = w.getBlockAt(x, y, z), head = w.getBlockAt(x, y + 1, z);
                    if (!ground.getType().isSolid() || ground.isLiquid() || !feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) continue;
                    Location loc = new Location(w, x + 0.5, y, z + 0.5);
                    if (loc.distance(p.getLocation()) < min * 0.9) continue;
                    Location headLoc = loc.clone().add(0, 1.6, 0);
                    Vector dir = headLoc.toVector().subtract(eye.toVector());
                    RayTraceResult hit = w.rayTraceBlocks(eye, dir.clone().normalize(), dir.length(), FluidCollisionMode.NEVER, true);
                    if (hit == null) return loc; // visible
                    if (fallback == null) fallback = loc;
                }
            }
        }
        return fallback;
    }

    private void tick() {
        double vanish = getConfig().getDouble("vanish-distance", 15);
        double look = getConfig().getDouble("look-angle", 5.0);
        long timeout = getConfig().getLong("timeout-seconds", 60) * 1000L;
        for (Apparition a : new ArrayList<>(active.values())) {
            Mannequin m = a.entity;
            if (a.target.getMostSignificantBits() == 0L) { // console self test apparition: timeout only
                if (!m.isValid() || System.currentTimeMillis() - a.spawnedAt > timeout) { getLogger().info("Self test apparition vanished (timeout), valid=" + m.isValid()); remove(a, false); }
                continue;
            }
            Player p = Bukkit.getPlayer(a.target);
            if (p == null || !m.isValid() || p.getWorld() != m.getWorld()) {
                getLogger().info("Herobrine removed silently (" + (p == null ? "player offline" : !m.isValid() ? "entity unloaded" : "player changed world") + ")");
                remove(a, false); continue;
            }
            long age = System.currentTimeMillis() - a.spawnedAt;
            if (age > timeout) { vanish(a, "timeout"); continue; }
            Location ml = m.getLocation();
            Vector toPlayer = p.getLocation().toVector().subtract(ml.toVector());
            Location face = ml.clone().setDirection(toPlayer);
            m.setRotation(face.getYaw(), 0f);
            m.setBodyYaw(face.getYaw());
            if (ml.distance(p.getLocation()) < vanish) { vanish(a, "too close"); continue; }
            if (age < 500) continue;
            Location eye = p.getEyeLocation();
            Vector toHead = ml.clone().add(0, 1.4, 0).toVector().subtract(eye.toVector());
            double angle = Math.toDegrees(eye.getDirection().angle(toHead));
            if (angle <= look && p.hasLineOfSight(m)) vanish(a, "looked at");
        }
    }

    private void vanish(Apparition a, String why) {
        Player p = Bukkit.getPlayer(a.target);
        getLogger().info("Herobrine vanished for " + (p == null ? "?" : p.getName()) + " (" + why + ")");
        remove(a, true);
    }

    private void remove(Apparition a, boolean effect) {
        active.remove(a.target);
        Mannequin m = a.entity;
        Player p = Bukkit.getPlayer(a.target);
        if (effect && p != null && m.isValid() && p.getWorld() == m.getWorld()) {
            Location l = m.getLocation().add(0, 1, 0);
            p.spawnParticle(Particle.LARGE_SMOKE, l, 25, 0.25, 0.6, 0.25, 0.01);
            p.playSound(l, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 0.5f, 1.0f);
        }
        if (m.isValid()) m.remove();
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Apparition a = active.get(e.getPlayer().getUniqueId());
        if (a != null) remove(a, false);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        // make sure nobody else ever sees someone else's apparition
        for (Apparition a : active.values()) if (!a.target.equals(e.getPlayer().getUniqueId())) e.getPlayer().hideEntity(this, a.entity);
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 0) { s.sendMessage("/herobrine <spawn <player>|reload|toggle|status|clear>"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (args.length < 2) { s.sendMessage("Usage: /herobrine spawn <player>"); return true; }
                Player p = Bukkit.getPlayerExact(args[1]);
                if (p == null) { s.sendMessage("Player not online: " + args[1]); return true; }
                String err = spawnFor(p);
                s.sendMessage(err == null ? "Herobrine appears for " + p.getName() + "." : "Failed: " + err);
            }
            case "spawnat" -> { // spawnat <player> <x> <y> <z> : place him at an exact spot (testing / staging)
                if (args.length < 5) { s.sendMessage("Usage: /herobrine spawnat <player> <x> <y> <z>"); return true; }
                Player p = Bukkit.getPlayerExact(args[1]);
                if (p == null) { s.sendMessage("Player not online: " + args[1]); return true; }
                try {
                    Location loc = new Location(p.getWorld(), Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
                    String err = spawnFor(p, loc);
                    s.sendMessage(err == null ? "Herobrine placed for " + p.getName() + "." : "Failed: " + err);
                } catch (NumberFormatException ex) { s.sendMessage("Bad coordinates."); }
            }
            case "selftest" -> { // console only: spawn at world spawn, no viewer, verifies spawn + timeout
                World w = Bukkit.getWorlds().get(0);
                Location loc = w.getHighestBlockAt(w.getSpawnLocation()).getLocation().add(0.5, 1, 0.5);
                UUID id = new UUID(0L, System.nanoTime());
                Mannequin m = w.spawn(loc, Mannequin.class, e -> {
                    e.setVisibleByDefault(false); e.setPersistent(false); e.setAI(false); e.setInvulnerable(true);
                    e.setSilent(true); e.setGravity(false); e.setImmovable(true); e.setDescription(null);
                    if (profile != null) e.setProfile(profile);
                    e.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
                });
                active.put(id, new Apparition(id, m));
                s.sendMessage("Selftest mannequin " + m.getUniqueId() + " valid=" + m.isValid() + " ai=" + m.hasAI() + " invuln=" + m.isInvulnerable()
                        + " silent=" + m.isSilent() + " visibleByDefault=" + m.isVisibleByDefault() + " profileProps=" + m.getProfile().properties().size() + " at " + loc.toVector());
            }
            case "reload" -> { loadSettings(); s.sendMessage("Herobrine config reloaded."); }
            case "toggle" -> {
                boolean on = !getConfig().getBoolean("enabled", true);
                getConfig().set("enabled", on); saveConfig();
                s.sendMessage("Herobrine random appearances: " + (on ? "ON" : "OFF"));
            }
            case "status" -> s.sendMessage("enabled=" + getConfig().getBoolean("enabled") + " active=" + active.size()
                    + (active.isEmpty() ? "" : " " + active.values().stream().map(a -> Optional.ofNullable(Bukkit.getPlayer(a.target)).map(Player::getName).orElse("?")).toList()));
            case "clear" -> { for (Apparition a : new ArrayList<>(active.values())) remove(a, false); s.sendMessage("Cleared."); }
            default -> s.sendMessage("Unknown subcommand.");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) return List.of("spawn", "spawnat", "reload", "toggle", "status", "clear");
        if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("spawnat"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
