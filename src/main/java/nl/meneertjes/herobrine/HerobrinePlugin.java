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

import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
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
    private Traces traces;
    private final Set<UUID> optedOut = new HashSet<>();
    private File optoutFile;
    private BukkitTask eventTask, eventEndTask;
    private long eventEndsAt = 0;

    void debug(String m) { if (getConfig().getBoolean("debug", false)) getLogger().info(m); }

    @Override public void onEnable() {
        key = new NamespacedKey(this, "apparition");
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("herobrine")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        cleanupStray();
        traces = new Traces(this);
        optoutFile = new File(getDataFolder(), "optout.yml");
        for (String u : YamlConfiguration.loadConfiguration(optoutFile).getStringList("opted-out")) try { optedOut.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) {}
        loadSettings();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 2L);
    }

    @Override public void onDisable() {
        stopEvent(false);
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
        long period = Math.max(20L, getConfig().getLong("check-interval-seconds", 600) * 20L);
        rollTask = Bukkit.getScheduler().runTaskTimer(this, () -> roll(false), period, period);
    }

    private void cleanupStray() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(Mannequin.class))
            if (e.getPersistentDataContainer().has(key)) e.remove();
    }

    private boolean eligible(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL || p.isDead()) return false;
        if (optedOut.contains(p.getUniqueId())) return false;
        if (active.containsKey(p.getUniqueId())) return false;
        List<String> worlds = getConfig().getStringList("worlds");
        if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) return false;
        for (String ex : getConfig().getStringList("excluded-players")) if (ex.equalsIgnoreCase(p.getName())) return false;
        long cd = (eventActive() ? getConfig().getLong("event.player-cooldown-seconds", 180) : getConfig().getLong("player-cooldown-seconds", 900)) * 1000L;
        Long last = lastSeen.get(p.getUniqueId());
        return last == null || System.currentTimeMillis() - last >= cd;
    }

    private boolean eligibleExtras(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL || p.isDead() || optedOut.contains(p.getUniqueId())) return false;
        List<String> worlds = getConfig().getStringList("worlds");
        if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) return false;
        for (String ex : getConfig().getStringList("excluded-players")) if (ex.equalsIgnoreCase(p.getName())) return false;
        return true;
    }

    boolean halloween() {
        if (!getConfig().getBoolean("halloween.enabled", true)) return false;
        try {
            String tz = getConfig().getString("halloween.timezone", "");
            LocalDate now = LocalDate.now(tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz));
            MonthDay d = MonthDay.from(now);
            MonthDay a = MonthDay.parse("--" + getConfig().getString("halloween.start", "10-25"));
            MonthDay b = MonthDay.parse("--" + getConfig().getString("halloween.end", "11-01"));
            return a.isAfter(b) ? (!d.isBefore(a) || !d.isAfter(b)) : (!d.isBefore(a) && !d.isAfter(b));
        } catch (Exception e) { getLogger().warning("Bad halloween date config: " + e.getMessage()); return false; }
    }

    boolean eventActive() { return eventEndsAt > System.currentTimeMillis(); }

    /** Combined multiplier from Halloween mode and the event night. */
    double globalMultiplier() {
        double m = 1.0;
        if (halloween()) m *= getConfig().getDouble("halloween.chance-multiplier", 2.0);
        if (eventActive()) m *= getConfig().getDouble("event.chance-multiplier", 5.0);
        return m;
    }

    private boolean nightOrCave(Player p) {
        long t = p.getWorld().getTime();
        boolean night = p.getWorld().getEnvironment() == World.Environment.NORMAL && t > 13000 && t < 23000;
        return night || p.getEyeLocation().getBlock().getLightFromSky() < 4;
    }

    private void roll(boolean eventRoll) {
        if (getConfig().getBoolean("debug", false)) getLogger().info("roll" + (eventRoll ? "(event)" : "") + ": enabled=" + getConfig().getBoolean("enabled", true) + " players=" + Bukkit.getOnlinePlayers().size());
        if (!getConfig().getBoolean("enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            double gm = globalMultiplier();
            if (eligibleExtras(p)) {
                if (getConfig().getBoolean("sounds.enabled", true) && ThreadLocalRandom.current().nextDouble() < getConfig().getDouble("sounds.chance", 0.05) * gm)
                    playSound(p, null);
                if (getConfig().getBoolean("traces.enabled", false) && ThreadLocalRandom.current().nextDouble() < getConfig().getDouble("traces.chance", 0.02) * gm) {
                    String err = traces.tryPlace(p, null);
                    if (err != null) debug("trace for " + p.getName() + " failed: " + err);
                }
            }
            if (!eligible(p)) continue;
            boolean dark = nightOrCave(p);
            if (getConfig().getBoolean("only-night-or-cave", false) && !dark) continue;
            double c = getConfig().getDouble("chance", 0.05) * gm * (dark ? getConfig().getDouble("night-or-cave-multiplier", 1.0) : 1.0);
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

    static final List<String> SOUND_TYPES = List.of("footsteps", "door", "cave", "fake-join");

    /** Play a random enabled sound effect (or the given one) for this player only. @return null or error. */
    String playSound(Player p, String forced) {
        List<String> on = new ArrayList<>();
        if (forced != null) { if (!SOUND_TYPES.contains(forced)) return "unknown sound " + forced; on.add(forced); }
        else for (String t : SOUND_TYPES) if (getConfig().getBoolean("sounds." + t, true)) on.add(t);
        if (on.isEmpty()) return "no sound types enabled";
        String t = on.get(ThreadLocalRandom.current().nextInt(on.size()));
        Location behind = p.getLocation().subtract(p.getLocation().getDirection().setY(0).normalize().multiply(3));
        switch (t) {
            case "footsteps" -> {
                Sound step = p.getLocation().getBlock().getRelative(0, -1, 0).getBlockSoundGroup().getStepSound();
                for (int i = 0; i < 5; i++) {
                    int n = i;
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (!p.isOnline()) return;
                        Location l = p.getLocation().subtract(p.getLocation().getDirection().setY(0).normalize().multiply(4 - n * 0.3));
                        p.playSound(l, step, SoundCategory.PLAYERS, 0.6f, 1.0f);
                    }, i * 7L);
                }
            }
            case "door" -> {
                p.playSound(behind, Sound.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.BLOCKS, 0.8f, 1.0f);
                Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.playSound(behind, Sound.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, 0.8f, 1.0f); }, 30L);
            }
            case "cave" -> p.playSound(behind, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1.0f, 1.0f);
            case "fake-join" -> {
                String name = getConfig().getString("sounds.fake-join-name", "Herobrine");
                p.sendMessage(Component.translatable("multiplayer.player.joined", Component.text(name)).color(NamedTextColor.YELLOW));
                long leave = getConfig().getLong("sounds.fake-leave-after-seconds", 3);
                if (leave > 0) Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline())
                    p.sendMessage(Component.translatable("multiplayer.player.left", Component.text(name)).color(NamedTextColor.YELLOW)); }, leave * 20L);
            }
        }
        getLogger().info("Herobrine " + t + " for " + p.getName());
        return null;
    }

    void startEvent(int minutes) {
        stopEvent(false);
        eventEndsAt = System.currentTimeMillis() + minutes * 60_000L;
        long period = Math.max(20L, getConfig().getLong("event.check-interval-seconds", 60) * 20L);
        eventTask = Bukkit.getScheduler().runTaskTimer(this, () -> roll(true), 40L, period);
        eventEndTask = Bukkit.getScheduler().runTaskLater(this, () -> stopEvent(true), minutes * 1200L);
        String msg = getConfig().getString("event.start-message", "");
        if (msg != null && !msg.isBlank()) for (Player p : Bukkit.getOnlinePlayers()) if (!optedOut.contains(p.getUniqueId())) p.sendMessage(Component.text(msg, NamedTextColor.DARK_RED));
        if (getConfig().getBoolean("event.set-night", false)) for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NORMAL) w.setTime(14000);
        if (getConfig().getBoolean("event.thunder", false)) for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NORMAL) { w.setStorm(true); w.setThundering(true); w.setThunderDuration(minutes * 1200); }
        getLogger().info("Herobrine event started for " + minutes + " minutes");
    }

    void stopEvent(boolean log) {
        boolean was = eventActive();
        eventEndsAt = 0;
        if (eventTask != null) { eventTask.cancel(); eventTask = null; }
        if (eventEndTask != null) { eventEndTask.cancel(); eventEndTask = null; }
        if (log && was) getLogger().info("Herobrine event ended");
    }

    private void saveOptout() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("opted-out", optedOut.stream().map(UUID::toString).toList());
        try { getDataFolder().mkdirs(); y.save(optoutFile); } catch (IOException e) { getLogger().severe("Cannot save optout.yml: " + e.getMessage()); }
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
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("optout")) {
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            if (!p.hasPermission("herobrine.optout")) { s.sendMessage("No permission."); return true; }
            boolean out = !optedOut.remove(p.getUniqueId());
            if (out) { optedOut.add(p.getUniqueId()); Apparition a = active.get(p.getUniqueId()); if (a != null) remove(a, false); }
            saveOptout();
            p.sendMessage(out ? "Herobrine leaves you alone now. Run /herobrine optout again to opt back in." : "You opted back in. Watch your back.");
            return true;
        }
        if (!s.hasPermission("herobrine.admin")) { s.sendMessage("Usage: /herobrine optout"); return true; }
        if (args.length == 0) { s.sendMessage("/herobrine <spawn|spawnat|reload|toggle|status|clear|sound|event|traces|optout>"); return true; }
        switch (sub) {
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
            case "status" -> s.sendMessage("enabled=" + getConfig().getBoolean("enabled") + " halloween=" + halloween() + " event=" + eventActive()
                    + " multiplier=" + globalMultiplier() + " traces=" + getConfig().getBoolean("traces.enabled", false) + "(" + traces.all().size() + ")"
                    + " optedOut=" + optedOut.size() + " active=" + active.size()
                    + (active.isEmpty() ? "" : " " + active.values().stream().map(a -> Optional.ofNullable(Bukkit.getPlayer(a.target)).map(Player::getName).orElse("?")).toList()));
            case "sound" -> { // sound <player> [footsteps|door|cave|fake-join]
                if (args.length < 2) { s.sendMessage("Usage: /herobrine sound <player> [" + String.join("|", SOUND_TYPES) + "]"); return true; }
                Player p = Bukkit.getPlayerExact(args[1]);
                if (p == null) { s.sendMessage("Player not online: " + args[1]); return true; }
                String err = playSound(p, args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null);
                s.sendMessage(err == null ? "Played for " + p.getName() + "." : "Failed: " + err);
            }
            case "event" -> { // event [start [minutes]|stop|status]
                String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "start";
                switch (a) {
                    case "stop" -> { stopEvent(true); s.sendMessage("Herobrine event stopped."); }
                    case "status" -> s.sendMessage(eventActive() ? "Event active, " + ((eventEndsAt - System.currentTimeMillis()) / 60000 + 1) + " min left." : "No event active.");
                    default -> {
                        int min = getConfig().getInt("event.duration-minutes", 60);
                        if (args.length > 2) try { min = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException e) { s.sendMessage("Bad minutes."); return true; }
                        startEvent(min);
                        s.sendMessage("Herobrine event night started for " + min + " minutes (multiplier x" + globalMultiplier() + ").");
                    }
                }
            }
            case "traces" -> { // traces list|undo <id>|undoall|place <player> [type]
                String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
                switch (a) {
                    case "list" -> {
                        if (traces.all().isEmpty()) s.sendMessage("No traces (traces.enabled=" + getConfig().getBoolean("traces.enabled", false) + ").");
                        for (Traces.Trace t : traces.all()) s.sendMessage("#" + t.id + " " + t.type + " " + t.world + " " + t.x + " " + t.y + " " + t.z + " (" + t.blocks.size() + " blocks)");
                    }
                    case "undo" -> {
                        if (args.length < 3) { s.sendMessage("Usage: /herobrine traces undo <id>"); return true; }
                        try { s.sendMessage(traces.undo(Integer.parseInt(args[2].replace("#", ""))) ? "Trace restored." : "No such trace (or world not loaded)."); }
                        catch (NumberFormatException e) { s.sendMessage("Bad id."); }
                    }
                    case "undoall" -> s.sendMessage("Restored " + traces.undoAll() + " traces.");
                    case "place" -> { // admin test: ignores traces.enabled and the time gap, but keeps all safety checks
                        if (args.length < 3) { s.sendMessage("Usage: /herobrine traces place <player> [" + String.join("|", Traces.TYPES) + "]"); return true; }
                        Player p = Bukkit.getPlayerExact(args[2]);
                        if (p == null) { s.sendMessage("Player not online: " + args[2]); return true; }
                        String type = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : Traces.TYPES.get(ThreadLocalRandom.current().nextInt(Traces.TYPES.size()));
                        if (!Traces.TYPES.contains(type)) { s.sendMessage("Unknown type."); return true; }
                        String err = traces.tryPlace(p, type);
                        s.sendMessage(err == null ? "Trace placed, see /herobrine traces list." : "Failed: " + err);
                    }
                    default -> s.sendMessage("Usage: /herobrine traces list|undo <id>|undoall|place <player> [type]");
                }
            }
            case "clear" -> { for (Apparition a : new ArrayList<>(active.values())) remove(a, false); s.sendMessage("Cleared."); }
            default -> s.sendMessage("Unknown subcommand.");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        boolean admin = s.hasPermission("herobrine.admin");
        if (args.length == 1) return (admin ? List.of("spawn", "spawnat", "reload", "toggle", "status", "clear", "sound", "event", "traces", "optout") : List.of("optout"))
                .stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (!admin) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("sound")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("sound")) return SOUND_TYPES;
        if (args.length == 2 && args[0].equalsIgnoreCase("event")) return List.of("start", "stop", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("traces")) return List.of("list", "undo", "undoall", "place");
        if (args.length == 3 && args[0].equalsIgnoreCase("traces") && args[1].equalsIgnoreCase("undo")) return traces.all().stream().map(t -> String.valueOf(t.id)).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("traces") && args[1].equalsIgnoreCase("place")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 4 && args[0].equalsIgnoreCase("traces") && args[1].equalsIgnoreCase("place")) return Traces.TYPES;
        if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("spawnat"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
