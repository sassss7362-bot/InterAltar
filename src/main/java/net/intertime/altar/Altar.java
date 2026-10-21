package net.intertime.altar;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Один кислотний вівтар: структура 7x7, басейн із "кислотою" посередині, 4 скрині.
 * Стани: BUILDING -> READY -> ACTIVATING -> REVEAL -> OPEN -> REMOVING.
 */
public class Altar {

    enum State {BUILDING, READY, ACTIVATING, REVEAL, OPEN, REMOVING}

    static class Part {
        final int x, y, z;
        final Material mat;
        final BlockFace face;
        BlockData old;
        boolean placed;

        Part(int x, int y, int z, Material mat, BlockFace face) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mat = mat;
            this.face = face;
        }
    }

    private static final Particle DUST = particle("REDSTONE", "DUST");
    private static final Particle HAPPY = particle("VILLAGER_HAPPY", "HAPPY_VILLAGER");

    private static Particle particle(String... names) {
        for (String n : names) {
            try {
                return Particle.valueOf(n);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    final InterAltar pl;
    final World world;
    final int cx, cy, cz;
    final String owner;
    State state = State.BUILDING;
    boolean finished;

    private final List<Part> parts = new ArrayList<Part>();
    private final List<Part> chests = new ArrayList<Part>();
    private final ItemStack[][] loot = new ItemStack[4][];
    private final int[] filled = new int[4];
    private final List<Integer> order = new ArrayList<Integer>();
    private final Random rnd = new Random();
    private final BossBar bar;

    private int tick, stateTick, placed, removeIdx, removeBase, revealIdx, secondsLeft;

    Altar(InterAltar pl, String owner, Location c) {
        this.pl = pl;
        this.owner = owner;
        this.world = c.getWorld();
        this.cx = c.getBlockX();
        this.cy = c.getBlockY();
        this.cz = c.getBlockZ();
        buildParts();
        for (int i = 0; i < 4; i++) loot[i] = pl.rollLoot();
        for (int i = 0; i < 27; i++) order.add(i);
        Collections.shuffle(order, rnd);
        bar = Bukkit.createBossBar(InterAltar.color(pl.getConfig().getString("messages.bar-build", "")),
                BarColor.GREEN, BarStyle.SEGMENTED_10);
    }

    // ================================================================ структура

    private final Set<String> struct = new HashSet<String>();

    private void add(int x, int y, int z, Material m, BlockFace f) {
        Part p = new Part(x, y, z, m, f);
        parts.add(p);
        struct.add(x + "," + y + "," + z);
        if (m == Material.CHEST) chests.add(p);
    }

    private static boolean isAir(Material m) {
        return m == Material.AIR || m.name().endsWith("_AIR");
    }

    private void buildParts() {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx, z = cz + dz;
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d <= 1) {
                    // басейн "кислоти"
                    add(x, cy - 1, z, Material.WATER, null);
                    add(x, cy - 2, z, Material.WATER, null);
                    add(x, cy - 3, z, Material.LIME_CONCRETE, null);
                } else if (d == 2) {
                    add(x, cy - 3, z, Material.MOSSY_STONE_BRICKS, null);
                    add(x, cy - 2, z, Material.MOSSY_STONE_BRICKS, null);
                    add(x, cy - 1, z, ((dx + dz) & 1) == 0 ? Material.LIME_CONCRETE : Material.MOSSY_STONE_BRICKS, null);
                } else {
                    add(x, cy - 1, z, Material.MOSSY_COBBLESTONE, null);
                    if (Math.abs(dx) == 3 && Math.abs(dz) == 3) {
                        add(x, cy, z, Material.MOSSY_STONE_BRICKS, null);
                        add(x, cy + 1, z, Material.MOSSY_STONE_BRICKS, null);
                        add(x, cy + 2, z, Material.EMERALD_BLOCK, null);
                    }
                }
            }
        }
        // 4 скрині навколо басейну, лицем до центру
        add(cx, cy, cz - 2, Material.CHEST, BlockFace.SOUTH);
        add(cx, cy, cz + 2, Material.CHEST, BlockFace.NORTH);
        add(cx - 2, cy, cz, Material.CHEST, BlockFace.EAST);
        add(cx + 2, cy, cz, Material.CHEST, BlockFace.WEST);

        // прибираємо все, що заважає над майданчиком (трава, квіти, пагорби)
        List<Part> clear = new ArrayList<Part>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = cy; y <= cy + 3; y++) {
                    int x = cx + dx, z = cz + dz;
                    if (struct.contains(x + "," + y + "," + z)) continue;
                    if (!isAir(world.getBlockAt(x, y, z).getType())) {
                        clear.add(new Part(x, y, z, Material.AIR, null));
                    }
                }
            }
        }
        parts.addAll(clear);

        Collections.sort(parts, new Comparator<Part>() {
            @Override
            public int compare(Part a, Part b) {
                if (a.y != b.y) return Integer.compare(a.y, b.y);
                int da = Math.max(Math.abs(a.x - cx), Math.abs(a.z - cz));
                int db = Math.max(Math.abs(b.x - cx), Math.abs(b.z - cz));
                return Integer.compare(da, db);
            }
        });
    }

    boolean contains(Block b) {
        return !finished && b.getWorld().equals(world)
                && Math.abs(b.getX() - cx) <= 3 && Math.abs(b.getZ() - cz) <= 3
                && b.getY() >= cy - 3 && b.getY() <= cy + 3;
    }

    boolean isChest(Block b) {
        if (finished || !b.getWorld().equals(world)) return false;
        for (Part p : chests) {
            if (p.x == b.getX() && p.y == b.getY() && p.z == b.getZ()) return true;
        }
        return false;
    }

    // ================================================================ блоки

    private void apply(Part p) {
        Block b = world.getBlockAt(p.x, p.y, p.z);
        if (p.old == null) p.old = b.getBlockData();
        b.setType(p.mat, false);
        if (p.face != null) {
            BlockData d = b.getBlockData();
            if (d instanceof Directional) {
                ((Directional) d).setFacing(p.face);
                b.setBlockData(d, false);
            }
        }
        p.placed = true;
    }

    private void restore(Part p) {
        if (!p.placed) return;
        Block b = world.getBlockAt(p.x, p.y, p.z);
        if (p.mat == Material.CHEST) {
            Inventory inv = chestInv(p);
            if (inv != null) inv.clear();
        }
        if (p.old != null) b.setBlockData(p.old, false);
        p.placed = false;
    }

    private Inventory chestInv(Part p) {
        BlockState s = world.getBlockAt(p.x, p.y, p.z).getState();
        return s instanceof Chest ? ((Chest) s).getBlockInventory() : null;
    }

    // ================================================================ ефекти

    private void dust(Location l, int count) {
        if (DUST == null) return;
        try {
            world.spawnParticle(DUST, l, count, 0.3, 0.15, 0.3, 0, new Particle.DustOptions(Color.fromRGB(57, 255, 20), 1.6f));
        } catch (Throwable ignored) {
        }
    }

    private void happy(Location l, int count) {
        if (HAPPY == null) return;
        try {
            world.spawnParticle(HAPPY, l, count, 0.3, 0.3, 0.3, 0);
        } catch (Throwable ignored) {
        }
    }

    private void sound(Location l, String key, float vol, float pitch) {
        try {
            world.playSound(l, key, vol, pitch);
        } catch (Throwable ignored) {
        }
    }

    private Location center() {
        return new Location(world, cx + 0.5, cy + 0.5, cz + 0.5);
    }

    /** "Зелена кислота": густий зелений пил над басейном + іскри на стовпах. */
    private void ambient() {
        for (int i = 0; i < 8; i++) {
            dust(new Location(world, cx - 1 + rnd.nextDouble() * 3, cy - 0.1, cz - 1 + rnd.nextDouble() * 3), 1);
        }
        happy(new Location(world, cx + 3.5, cy + 3.2, cz + 3.5), 1);
        happy(new Location(world, cx - 2.5, cy + 3.2, cz + 3.5), 1);
        happy(new Location(world, cx + 3.5, cy + 3.2, cz - 2.5), 1);
        happy(new Location(world, cx - 2.5, cy + 3.2, cz - 2.5), 1);
    }

    private void poison() {
        if (tick % 10 != 0) return;
        int sec = pl.getConfig().getInt("altar.poison.seconds", 8);
        int lvl = Math.max(0, pl.getConfig().getInt("altar.poison.level", 2) - 1);
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            if (x < cx - 1 || x > cx + 1 || z < cz - 1 || z > cz + 1 || y < cy - 3 || y > cy - 1) continue;
            if (l.getBlock().getType() != Material.WATER) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, sec * 20, lvl));
        }
    }

    private void bar(String title, double progress) {
        bar.setTitle(title);
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        double r = pl.getConfig().getDouble("altar.bossbar-radius", 80);
        Location c = new Location(world, cx, cy, cz);
        List<Player> cur = bar.getPlayers();
        for (Player p : world.getPlayers()) {
            boolean in = p.getLocation().distanceSquared(c) <= r * r;
            boolean has = cur.contains(p);
            if (in && !has) bar.addPlayer(p);
            else if (!in && has) bar.removePlayer(p);
        }
    }

    private String xyz(String key) {
        return pl.msg(key, "x", String.valueOf(cx), "y", String.valueOf(cy), "z", String.valueOf(cz),
                "player", owner, "seconds", String.valueOf(secondsLeft));
    }

    private void setState(State s) {
        state = s;
        stateTick = 0;
    }

    // ================================================================ головний цикл

    void tick() {
        tick++;
        stateTick++;
        poison();
        switch (state) {
            case BUILDING:
                tickBuild();
                break;
            case READY:
                tickReady();
                break;
            case ACTIVATING:
                tickActivating();
                break;
            case REVEAL:
                tickReveal();
                break;
            case OPEN:
                tickOpen();
                break;
            case REMOVING:
                tickRemoving();
                break;
            default:
                break;
        }
    }

    private void tickBuild() {
        int dur = Math.max(20, pl.getConfig().getInt("altar.build-seconds", 8) * 20);
        int target = Math.min(parts.size(), (int) Math.ceil(parts.size() * (double) stateTick / dur));
        boolean played = false;
        while (placed < target) {
            Part p = parts.get(placed++);
            apply(p);
            Location l = new Location(world, p.x + 0.5, p.y + 1.0, p.z + 0.5);
            if (p.mat != Material.AIR) dust(l, 2);
            if (!played && placed % 2 == 0) {
                played = true;
                sound(l, "block.stone.place", 0.7f, 0.6f + rnd.nextFloat() * 0.8f);
            }
        }
        // скрині поступово наповнюються зеленим барвником
        boolean dyeDone = true;
        for (int i = 0; i < chests.size(); i++) {
            Part cp = chests.get(i);
            if (cp.placed && filled[i] < 27) {
                Inventory inv = chestInv(cp);
                if (inv != null) {
                    for (int k = 0; k < 2 && filled[i] < 27; k++) {
                        inv.setItem(filled[i]++, new ItemStack(Material.GREEN_DYE));
                    }
                }
            }
            if (!cp.placed || filled[i] < 27) dyeDone = false;
        }
        if (stateTick % 5 == 1) bar(InterAltar.color(pl.getConfig().getString("messages.bar-build", "")), (double) stateTick / dur);
        if (placed >= parts.size() && dyeDone) {
            setState(State.READY);
            bar(InterAltar.color(pl.getConfig().getString("messages.bar-ready", "")), 1.0);
            Bukkit.broadcastMessage(xyz("ready"));
            sound(center(), "block.beacon.activate", 1f, 1.2f);
        }
    }

    private void tickReady() {
        if (stateTick % 5 == 0) ambient();
        if (stateTick % 20 == 0) bar(InterAltar.color(pl.getConfig().getString("messages.bar-ready", "")), 1.0);
        int timeout = pl.getConfig().getInt("altar.ready-timeout-seconds", 900) * 20;
        if (stateTick > timeout) {
            Bukkit.broadcastMessage(xyz("expired"));
            beginRemove(false);
        }
    }

    void click(Player p) {
        switch (state) {
            case READY:
                secondsLeft = Math.max(1, pl.getConfig().getInt("altar.activation-seconds", 100));
                setState(State.ACTIVATING);
                Bukkit.broadcastMessage(pl.msg("activated", "player", p.getName(), "seconds", String.valueOf(secondsLeft),
                        "x", String.valueOf(cx), "y", String.valueOf(cy), "z", String.valueOf(cz)));
                sound(center(), "entity.lightning_bolt.thunder", 1f, 1.4f);
                break;
            case ACTIVATING:
                p.sendMessage(pl.msg("wait-activation", "seconds", String.valueOf(secondsLeft)));
                break;
            case REVEAL:
                break;
            default:
                p.sendMessage(pl.msg("wait-build"));
                break;
        }
    }

    private void tickActivating() {
        int total = Math.max(1, pl.getConfig().getInt("altar.activation-seconds", 100));
        if (stateTick % 3 == 0) ambient();
        if (stateTick % 20 == 0) {
            secondsLeft--;
            bar(pl.msg("bar-activating", "seconds", String.valueOf(Math.max(0, secondsLeft))), secondsLeft / (double) total);
            if (secondsLeft > 0 && secondsLeft <= 5) sound(center(), "block.note_block.pling", 1f, 0.8f + (5 - secondsLeft) * 0.2f);
            if (secondsLeft <= 0) {
                setState(State.REVEAL);
                revealIdx = 0;
                Bukkit.broadcastMessage(xyz("reveal"));
            }
        }
    }

    /** Лут з'являється поступово: зелений барвник заміщується справжнім лутом по одному слоту. */
    private void tickReveal() {
        if (stateTick % 3 != 0) return;
        ambient();
        if (revealIdx < 27) {
            int slot = order.get(revealIdx);
            for (int i = 0; i < chests.size(); i++) {
                Inventory inv = chestInv(chests.get(i));
                if (inv == null) continue;
                ItemStack it = loot[i][slot];
                inv.setItem(slot, it == null ? null : it.clone());
            }
            sound(center(), "entity.experience_orb.pickup", 1f, 0.5f + revealIdx / 27f * 1.5f);
            revealIdx++;
        } else {
            secondsLeft = Math.max(1, pl.getConfig().getInt("altar.open-seconds", 180));
            setState(State.OPEN);
            Bukkit.broadcastMessage(xyz("open"));
            sound(center(), "entity.player.levelup", 1f, 1f);
        }
    }

    private void tickOpen() {
        if (stateTick % 5 == 0) ambient();
        if (stateTick % 20 == 0) {
            secondsLeft--;
            int total = Math.max(1, pl.getConfig().getInt("altar.open-seconds", 180));
            bar(pl.msg("bar-open", "seconds", String.valueOf(Math.max(0, secondsLeft))), secondsLeft / (double) total);
            if (secondsLeft <= 0) beginRemove(true);
        }
    }

    private void beginRemove(boolean announce) {
        removeBase = placed;
        removeIdx = placed;
        setState(State.REMOVING);
        if (announce) Bukkit.broadcastMessage(xyz("removed"));
    }

    private void tickRemoving() {
        int dur = Math.max(20, pl.getConfig().getInt("altar.remove-seconds", 6) * 20);
        int target = (int) Math.ceil(removeBase * (1.0 - (double) stateTick / dur));
        boolean played = false;
        while (removeIdx > Math.max(0, target)) {
            Part p = parts.get(--removeIdx);
            restore(p);
            if (!played && removeIdx % 2 == 0) {
                played = true;
                Location l = new Location(world, p.x + 0.5, p.y + 0.5, p.z + 0.5);
                dust(l, 2);
                sound(l, "block.stone.break", 0.6f, 0.7f + rnd.nextFloat() * 0.6f);
            }
        }
        if (removeIdx <= 0) {
            bar.removeAll();
            finished = true;
        }
    }

    /** Миттєве прибирання (вимкнення сервера, /acidaltar clear, помилка). */
    void forceRemove() {
        for (int i = placed - 1; i >= 0; i--) restore(parts.get(i));
        // якщо ще йшло прибирання, частина вже відновлена - restore це враховує
        for (int i = parts.size() - 1; i >= 0; i--) restore(parts.get(i));
        bar.removeAll();
        finished = true;
    }
}
