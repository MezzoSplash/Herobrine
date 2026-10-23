package nl.meneertjes.herobrine;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** World traces. Every changed block is recorded so a trace can be undone exactly. */
final class Traces {
    record Saved(int x, int y, int z, String data) {}
    static final class Trace {
        int id; String type; String world; int x, y, z; long created; final List<Saved> blocks = new ArrayList<>();
    }

    static final List<String> TYPES = List.of("tunnels", "leafless-trees", "sand-pyramids", "torch-messages");

    private final HerobrinePlugin plugin;
    private final File dataFile, logFile;
    private final Map<Integer, Trace> traces = new LinkedHashMap<>();
    private int nextId = 1;
    private long lastPlaced = 0;

    Traces(HerobrinePlugin plugin) {
        this.plugin = plugin;
        dataFile = new File(plugin.getDataFolder(), "traces.yml");
        logFile = new File(plugin.getDataFolder(), "traces.log");
        load();
    }

    Collection<Trace> all() { return traces.values(); }

    private ConfigurationSection cfg() { return plugin.getConfig().getConfigurationSection("traces"); }

    // ---------- selection ----------

    /** Try to place a random enabled trace somewhere near (but well away from) the given player. */
    String tryPlace(Player target, String forcedType) {
        var c = plugin.getConfig();
        if (forcedType == null && !c.getBoolean("traces.enabled", false)) return "traces disabled";
        if (traces.size() >= c.getInt("traces.max-active", 10)) return "max-active reached";
        long gap = c.getLong("traces.min-minutes-between", 60) * 60_000L;
        if (forcedType == null && System.currentTimeMillis() - lastPlaced < gap) return "too soon after last trace";
        List<String> types = new ArrayList<>();
        if (forcedType != null) types.add(forcedType);
        else for (String t : TYPES) if (c.getBoolean("traces." + t, true)) types.add(t);
        if (types.isEmpty()) return "no trace types enabled";
        World w = target.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return "only in the overworld";
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String type = types.get(r.nextInt(types.size()));
        double min = c.getDouble("traces.min-distance", 48), max = c.getDouble("traces.max-distance", 96);
        for (int i = 0; i < 20; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = min + r.nextDouble() * Math.max(0, max - min);
            int x = (int) Math.floor(target.getLocation().getX() + Math.cos(a) * d);
            int z = (int) Math.floor(target.getLocation().getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            String why = safe(w, x, top.getY(), z);
            if (why != null) { plugin.debug("trace spot " + x + " " + top.getY() + " " + z + " rejected: " + why); continue; }
            Trace t = new Trace();
            t.type = type; t.world = w.getName(); t.x = x; t.y = top.getY() + 1; t.z = z; t.created = System.currentTimeMillis();
            boolean ok = switch (type) {
                case "tunnels" -> tunnel(w, t);
                case "leafless-trees" -> leafless(w, t);
                case "sand-pyramids" -> pyramid(w, t);
                case "torch-messages" -> torches(w, t);
                default -> false;
            };
            if (!ok) { restore(t); continue; }
            t.id = nextId++;
            traces.put(t.id, t);
            lastPlaced = System.currentTimeMillis();
            save();
            log("PLACED #" + t.id + " " + t.type + " world=" + t.world + " at " + t.x + " " + t.y + " " + t.z + " blocks=" + t.blocks.size() + " (near " + target.getName() + ")");
            return null;
        }
        return "no safe spot found";
    }

    /** @return null if the spot is far from players, spawn and anything that looks player made. */
    private String safe(World w, int x, int y, int z) {
        var c = plugin.getConfig();
        Location here = new Location(w, x, y, z);
        double pd = c.getDouble("traces.min-distance-from-players", 32);
        for (Player p : w.getPlayers()) if (p.getLocation().distance(here) < pd) return "too close to " + p.getName();
        Location spawn = w.getSpawnLocation(); spawn.setY(y);
        if (spawn.distance(here) < c.getDouble("traces.min-distance-from-spawn", 200)) return "too close to spawn";
        long maxInh = c.getLong("traces.max-chunk-inhabited-minutes", 60) * 60L * 20L;
        int rad = c.getInt("traces.protection-radius", 16);
        for (int cx = (x - rad) >> 4; cx <= (x + rad) >> 4; cx++) for (int cz = (z - rad) >> 4; cz <= (z + rad) >> 4; cz++) {
            if (!w.isChunkLoaded(cx, cz)) return "nearby chunk not loaded";
            if (w.getChunkAt(cx, cz).getInhabitedTime() > maxInh) return "chunk visited a lot (likely a base)";
        }
        Set<Material> bad = new HashSet<>();
        for (String s : c.getStringList("traces.player-block-types")) { Material m = Material.matchMaterial(s); if (m != null) bad.add(m); }
        int ymin = Math.max(w.getMinHeight(), y - 24), ymax = Math.min(w.getMaxHeight() - 1, y + 24);
        for (int bx = x - rad; bx <= x + rad; bx++) for (int bz = z - rad; bz <= z + rad; bz++) for (int by = ymin; by <= ymax; by++) {
            Material m = w.getBlockAt(bx, by, bz).getType();
            if (bad.contains(m)) return "player block " + m + " at " + bx + " " + by + " " + bz;
        }
        return null;
    }

    // ---------- builders ----------

    private void set(World w, Trace t, int x, int y, int z, BlockData data) {
        Block b = w.getBlockAt(x, y, z);
        t.blocks.add(new Saved(x, y, z, b.getBlockData().getAsString()));
        b.setBlockData(data, false);
    }

    private static boolean natural(Material m) {
        return switch (m) {
            case STONE, DEEPSLATE, DIRT, GRASS_BLOCK, GRANITE, DIORITE, ANDESITE, TUFF, COARSE_DIRT, GRAVEL, SANDSTONE, TERRACOTTA, CALCITE, COBBLED_DEEPSLATE -> true;
            default -> m.name().endsWith("_TERRACOTTA") && !m.name().contains("GLAZED");
        };
    }

    private static boolean replaceable(Block b) {
        return b.getType().isAir() || (b.isReplaceable() && !b.isLiquid());
    }

    /** 2x2 tunnel, 16 long, dug straight into natural terrain a few blocks below the surface. */
    private boolean tunnel(World w, Trace t) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] d = dirs[ThreadLocalRandom.current().nextInt(4)];
        int y = t.y - 6;
        if (y - 1 <= w.getMinHeight()) return false;
        BlockData air = Material.AIR.createBlockData();
        int carved = 0;
        for (int i = 0; i < 16; i++) {
            for (int s = 0; s < 2; s++) for (int h = 0; h < 2; h++) {
                int bx = t.x + d[0] * i + (d[0] == 0 ? s : 0), bz = t.z + d[1] * i + (d[1] == 0 ? s : 0);
                Block b = w.getBlockAt(bx, y + h, bz);
                if (natural(b.getType())) { set(w, t, bx, y + h, bz, air); carved++; }
                else if (!b.getType().isAir()) return false; // water, lava, ores near builds etc.: abort
            }
        }
        t.y = y;
        return carved >= 40;
    }

    /** Strip the (natural) leaves off a tree near the spot. */
    private boolean leafless(World w, Trace t) {
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            Block top = w.getHighestBlockAt(t.x + dx, t.z + dz);
            if (!(top.getBlockData() instanceof Leaves)) continue;
            Block log = null;
            for (int dy = 1; dy < 12; dy++) {
                Block b = top.getRelative(0, -dy, 0);
                if (Tag.LOGS.isTagged(b.getType())) { log = b; break; }
                if (!(b.getBlockData() instanceof Leaves)) break;
            }
            if (log == null) continue;
            BlockData air = Material.AIR.createBlockData();
            for (int bx = -4; bx <= 4; bx++) for (int bz = -4; bz <= 4; bz++) for (int by = -6; by <= 4; by++) {
                Block b = w.getBlockAt(log.getX() + bx, top.getY() + by, log.getZ() + bz);
                if (b.getBlockData() instanceof Leaves l && !l.isPersistent()) set(w, t, b.getX(), b.getY(), b.getZ(), air);
            }
            t.x = log.getX(); t.y = log.getY(); t.z = log.getZ();
            return t.blocks.size() >= 10;
        }
        return false;
    }

    /** Small 3 layer sand pyramid (5x5, 3x3, 1x1) on flat ground. */
    private boolean pyramid(World w, Trace t) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Block g = w.getBlockAt(t.x + dx, t.y - 1, t.z + dz);
            if (!g.getType().isSolid() || !replaceable(g.getRelative(0, 1, 0))) return false;
        }
        BlockData sand = Material.SAND.createBlockData();
        for (int layer = 0; layer < 3; layer++) {
            int r = 2 - layer;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                Block b = w.getBlockAt(t.x + dx, t.y + layer, t.z + dz);
                if (!replaceable(b)) return false;
                set(w, t, b.getX(), b.getY(), b.getZ(), sand);
            }
        }
        return true;
    }

    private static final Map<Character, String[]> FONT = new HashMap<>();
    static {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String[] rows = {
            ".#.,##.,.##,##.,###,###,.##,#.#,###,..#,#.#,#..,#.#,##.,.#.,##.,.#.,##.,.##,###,#.#,#.#,#.#,#.#,#.#,###",
            "#.#,#.#,#..,#.#,#..,#..,#..,#.#,.#.,..#,#.#,#..,###,#.#,#.#,#.#,#.#,#.#,#..,.#.,#.#,#.#,#.#,#.#,#.#,..#",
            "###,##.,#..,#.#,##.,##.,#.#,###,.#.,..#,##.,#..,#.#,#.#,#.#,##.,#.#,##.,.#.,.#.,#.#,#.#,###,.#.,.#.,.#.",
            "#.#,#.#,#..,#.#,#..,#..,#.#,#.#,.#.,#.#,#.#,#..,#.#,#.#,#.#,#..,.##,#.#,..#,.#.,#.#,#.#,###,#.#,.#.,#..",
            "#.#,##.,.##,##.,###,#..,.##,#.#,###,.#.,#.#,###,#.#,#.#,.#.,#..,..#,#.#,##.,.#.,###,.#.,#.#,#.#,.#.,###"};
        for (int i = 0; i < chars.length(); i++) {
            String[] g = new String[5];
            for (int r = 0; r < 5; r++) g[r] = rows[r].split(",")[i];
            FONT.put(chars.charAt(i), g);
        }
    }

    /** Torches on the ground spelling a short word (seen from above). */
    private boolean torches(World w, Trace t) {
        String text = plugin.getConfig().getString("traces.torch-message", "RUN").toUpperCase(Locale.ROOT);
        BlockData torch = Material.TORCH.createBlockData();
        int col = 0;
        for (char ch : text.toCharArray()) {
            String[] g = FONT.get(ch);
            if (g != null) for (int r = 0; r < 5; r++) for (int cc = 0; cc < 3; cc++) {
                if (g[r].charAt(cc) != '#') continue;
                int bx = t.x + col + cc, bz = t.z + r;
                Block top = w.getHighestBlockAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block b = top.getRelative(0, 1, 0);
                if (Math.abs(b.getY() - t.y) > 3 || !top.getType().isSolid() || top.isLiquid() || !replaceable(b)) return false;
                set(w, t, bx, b.getY(), bz, torch);
            }
            col += 4;
        }
        return !t.blocks.isEmpty();
    }

    // ---------- undo / persistence ----------

    private void restore(Trace t) {
        World w = Bukkit.getWorld(t.world);
        if (w == null) return;
        for (int i = t.blocks.size() - 1; i >= 0; i--) {
            Saved s = t.blocks.get(i);
            try { w.getBlockAt(s.x(), s.y(), s.z()).setBlockData(Bukkit.createBlockData(s.data()), false); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("Could not restore " + s); }
        }
    }

    boolean undo(int id) {
        Trace t = traces.remove(id);
        if (t == null) return false;
        if (Bukkit.getWorld(t.world) == null) { traces.put(id, t); return false; }
        restore(t); save();
        log("UNDONE #" + t.id + " " + t.type + " world=" + t.world + " at " + t.x + " " + t.y + " " + t.z);
        return true;
    }

    int undoAll() {
        int n = 0;
        for (Integer id : new ArrayList<>(traces.keySet())) if (undo(id)) n++;
        return n;
    }

    private void log(String msg) {
        plugin.getLogger().info("[traces] " + msg);
        plugin.getDataFolder().mkdirs();
        try (FileWriter f = new FileWriter(logFile, true)) { f.write(LocalDateTime.now().withNano(0) + " " + msg + "\n"); }
        catch (IOException e) { plugin.getLogger().warning("Cannot write traces.log: " + e.getMessage()); }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        nextId = y.getInt("next-id", 1);
        ConfigurationSection s = y.getConfigurationSection("traces");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection e = s.getConfigurationSection(k);
            Trace t = new Trace();
            t.id = Integer.parseInt(k); t.type = e.getString("type"); t.world = e.getString("world");
            t.x = e.getInt("x"); t.y = e.getInt("y"); t.z = e.getInt("z"); t.created = e.getLong("created");
            for (String b : e.getStringList("blocks")) {
                String[] p = b.split(" ", 4);
                t.blocks.add(new Saved(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3]));
            }
            traces.put(t.id, t);
            nextId = Math.max(nextId, t.id + 1);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Trace t : traces.values()) {
            String p = "traces." + t.id + ".";
            y.set(p + "type", t.type); y.set(p + "world", t.world);
            y.set(p + "x", t.x); y.set(p + "y", t.y); y.set(p + "z", t.z); y.set(p + "created", t.created);
            y.set(p + "blocks", t.blocks.stream().map(b -> b.x() + " " + b.y() + " " + b.z() + " " + b.data()).toList());
        }
        try { y.save(dataFile); } catch (IOException e) { plugin.getLogger().severe("Cannot save traces.yml: " + e.getMessage()); }
    }
}
