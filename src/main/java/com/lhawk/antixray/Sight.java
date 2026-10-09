package com.lhawk.antixray;

import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Map;

final class Sight {

    static final int ENCLOSED = 0;
    static final int NOT_VISIBLE = 1;
    static final int VISIBLE = 2;

    static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    private static final double[][] FACE_POINTS = {{0, 0}, {-0.45, -0.45}, {-0.45, 0.45}, {0.45, -0.45}, {0.45, 0.45}};

    private final World world;
    private final Compatibility compatibility;
    private final Map<Long, Boolean> occluding = new HashMap<Long, Boolean>();

    Sight(World world, Compatibility compatibility) {
        this.world = world;
        this.compatibility = compatibility;
    }

    void setAir(Block block) {
        occluding.put(BlockManager.pack(block.getX(), block.getY(), block.getZ()), Boolean.FALSE);
    }

    int visibility(double eyeX, double eyeY, double eyeZ, int x, int y, int z) {
        int result = ENCLOSED;
        for (int[] off : OFFSETS) {
            int nx = x + off[0];
            int ny = y + off[1];
            int nz = z + off[2];
            if (isOccluding(nx, ny, nz)) continue;
            result = NOT_VISIBLE;
            if ((eyeX - x - 0.5) * off[0] + (eyeY - y - 0.5) * off[1] + (eyeZ - z - 0.5) * off[2] <= 0.5) continue;

            double cx = nx + 0.5 - off[0] * 0.49;
            double cy = ny + 0.5 - off[1] * 0.49;
            double cz = nz + 0.5 - off[2] * 0.49;
            for (double[] p : FACE_POINTS) {
                double tx = off[0] == 0 ? cx + p[0] : cx;
                double ty = off[1] == 0 ? cy + p[off[0] == 0 ? 1 : 0] : cy;
                double tz = off[2] == 0 ? cz + p[1] : cz;
                if (rayClear(eyeX, eyeY, eyeZ, tx, ty, tz, nx, ny, nz)) return VISIBLE;
            }
        }
        return result;
    }

    boolean canSee(double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        return rayClear(eyeX, eyeY, eyeZ, x, y, z, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private boolean rayClear(double ox, double oy, double oz, double tx, double ty, double tz,
                             int targetX, int targetY, int targetZ) {
        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        if (x == targetX && y == targetY && z == targetZ) return true;

        double dx = tx - ox;
        double dy = ty - oy;
        double dz = tz - oz;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length;
        dy /= length;
        dz /= length;
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double maxX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - ox : ox - x) * deltaX;
        double maxY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - oy : oy - y) * deltaY;
        double maxZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - oz : oz - z) * deltaZ;

        while (true) {
            if (maxX < maxY && maxX < maxZ) {
                if (maxX > length) return true;
                x += stepX;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                if (maxY > length) return true;
                y += stepY;
                maxY += deltaY;
            } else {
                if (maxZ > length) return true;
                z += stepZ;
                maxZ += deltaZ;
            }
            if (x == targetX && y == targetY && z == targetZ) return true;
            if (isOccluding(x, y, z)) return false;
        }
    }

    private boolean isOccluding(int x, int y, int z) {
        long key = BlockManager.pack(x, y, z);
        Boolean value = occluding.get(key);
        if (value == null) {
            value = !world.isChunkLoaded(x >> 4, z >> 4)
                || compatibility.isOccluding(world.getBlockAt(x, y, z).getType());
            occluding.put(key, value);
        }
        return value;
    }
}
