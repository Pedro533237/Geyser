/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 */

package org.geysermc.geyser.session.cache;

import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.LivingEntity;
import org.geysermc.geyser.entity.type.living.ArmorStandEntity;
import org.geysermc.geyser.entity.type.player.AvatarEntity;
import org.geysermc.geyser.level.block.type.Block;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.level.physics.BoundingBox;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.collision.BlockCollision;
import org.geysermc.geyser.util.BlockUtils;

/**
 * Applies a Bedrock-only visual invisibility override to living entities
 * occluded by opaque, full-cube blocks.
 *
 * Entities remain in the cache and Java server state is not modified.
 */
public final class EntityCullingManager {
    private static final int UPDATE_INTERVAL_TICKS = 10;

    private static final double MIN_HORIZONTAL_DISTANCE_SQUARED = 4.0;
    private static final double MAX_HORIZONTAL_DISTANCE_SQUARED = 576.0;
    private static final double FULL_BLOCK_EPSILON = 0.001;
    private static final double RAY_TIE_EPSILON = 1.0E-10;

    private static final float[] TARGET_HEIGHT_FRACTIONS = {
        0.15f, 0.5f, 0.85f
    };

    private final GeyserSession session;

    /**
     * Values: -1 = unknown, 0 = transparent, 1 = opaque full block.
     */
    private final Int2ByteOpenHashMap opaqueBlockCache =
        new Int2ByteOpenHashMap();

    private int ticksUntilUpdate;
    private boolean wasEnabled;

    public EntityCullingManager(GeyserSession session) {
        this.session = session;
        opaqueBlockCache.defaultReturnValue((byte) -1);
    }

    public void tick() {
        if (!session.getGeyser().config().gameplay().serverSideEntityCulling()) {
            if (wasEnabled) {
                resetCulledEntities();
            }
            wasEnabled = false;
            return;
        }

        if (!session.isSpawned()
            || !session.getChunkCache().isCache()
            || session.getSpectatedEntity() != null) {
            if (wasEnabled) {
                resetCulledEntities();
            }
            wasEnabled = false;
            return;
        }

        wasEnabled = true;

        if (++ticksUntilUpdate < UPDATE_INTERVAL_TICKS) {
            return;
        }
        ticksUntilUpdate = 0;

        Vector3f playerPosition = session.getPlayerEntity().position();
        float eyeHeight =
            session.getPlayerEntity().getJavaDefinition().height() * 0.85f;

        Vector3f eyePosition = Vector3f.from(
            playerPosition.getX(),
            playerPosition.getY() + eyeHeight,
            playerPosition.getZ()
        );

        ChunkCache chunkCache = session.getChunkCache();

        for (Entity entity :
            session.getEntityCache().getEntitiesUnsafe().values()) {

            if (!isEligible(entity)) {
                entity.setEntityCullingInvisible(false);
                continue;
            }

            entity.setEntityCullingInvisible(
                isOccluded(eyePosition, entity, chunkCache)
            );
        }
    }

    private boolean isEligible(Entity entity) {
        return entity.isValid()
            && entity instanceof LivingEntity
            && !(entity instanceof AvatarEntity)
            && !(entity instanceof ArmorStandEntity)
            && entity.getVehicle() == null
            && entity.passengers().isEmpty()
            && !entity.getFlag(
                org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.INVISIBLE
            );
    }

    private boolean isOccluded(
        Vector3f eyePosition,
        Entity entity,
        ChunkCache chunkCache
    ) {
        Vector3f position = entity.position();

        double dx = position.getX() - eyePosition.getX();
        double dz = position.getZ() - eyePosition.getZ();

        double horizontalDistanceSquared = dx * dx + dz * dz;

        // Cheap rejection before performing any ray traversal.
        if (horizontalDistanceSquared < MIN_HORIZONTAL_DISTANCE_SQUARED
            || horizontalDistanceSquared > MAX_HORIZONTAL_DISTANCE_SQUARED) {
            return false;
        }

        float height = Math.max(
            0.25f,
            entity.getJavaDefinition().height()
        );

        for (float fraction : TARGET_HEIGHT_FRACTIONS) {
            Vector3f target = Vector3f.from(
                position.getX(),
                position.getY() + height * fraction,
                position.getZ()
            );

            if (!rayIsBlocked(eyePosition, target, chunkCache)) {
                // One visible body point is enough to keep the entity visible.
                return false;
            }
        }

        return true;
    }

    /**
     * Traverses voxels crossed by a ray using a 3D DDA algorithm.
     *
     * Missing chunks are always treated as unobstructed.
     * The target voxel is excluded because it contains the target entity.
     */
    private boolean rayIsBlocked(
        Vector3f start,
        Vector3f end,
        ChunkCache chunkCache
    ) {
        double startX = start.getX();
        double startY = start.getY();
        double startZ = start.getZ();

        double dx = end.getX() - startX;
        double dy = end.getY() - startY;
        double dz = end.getZ() - startZ;

        int x = (int) Math.floor(startX);
        int y = (int) Math.floor(startY);
        int z = (int) Math.floor(startZ);

        int endX = (int) Math.floor(end.getX());
        int endY = (int) Math.floor(end.getY());
        int endZ = (int) Math.floor(end.getZ());

        int stepX = Double.compare(dx, 0.0);
        int stepY = Double.compare(dy, 0.0);
        int stepZ = Double.compare(dz, 0.0);

        double deltaX = stepX == 0
            ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = stepY == 0
            ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = stepZ == 0
            ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double maxX = initialBoundaryTime(startX, x, dx, stepX);
        double maxY = initialBoundaryTime(startY, y, dy, stepY);
        double maxZ = initialBoundaryTime(startZ, z, dz, stepZ);

        // Each iteration crosses at least one voxel boundary.
        // The bound also protects against unexpected numerical problems.
        int maxSteps = Math.abs(endX - x)
            + Math.abs(endY - y)
            + Math.abs(endZ - z) + 3;

        for (int i = 0; i < maxSteps; i++) {
            if (x == endX && y == endY && z == endZ) {
                return false;
            }

            // If the ray hits an edge/corner, the exact set of voxels touched
            // is numerically ambiguous. Keep the entity visible rather than
            // risk culling it because of a missed neighboring voxel.
            double next = Math.min(maxX, Math.min(maxY, maxZ));
            if ((isSameRayTime(maxX, next)
                    && (isSameRayTime(maxY, next) || isSameRayTime(maxZ, next)))
                || (isSameRayTime(maxY, next) && isSameRayTime(maxZ, next))) {
                return false;
            }

            if (isSameRayTime(maxX, next)) {
                x += stepX;
                maxX += deltaX;
            } else if (isSameRayTime(maxY, next)) {
                y += stepY;
                maxY += deltaY;
            } else {
                z += stepZ;
                maxZ += deltaZ;
            }

            // Never use unavailable chunk data as an occluder.
            if (!chunkCache.isChunkLoadedAt(x, z)) {
                return false;
            }

            if (x == endX && y == endY && z == endZ) {
                return false;
            }

            if (isOpaqueFullBlock(chunkCache.getBlockAt(x, y, z))) {
                return true;
            }
        }

        // Conservative fallback: numerical uncertainty means visible.
        return false;
    }

    private static boolean isSameRayTime(double first, double second) {
        if (first == second) {
            return true;
        }
        if (!Double.isFinite(first) || !Double.isFinite(second)) {
            return false;
        }
        return Math.abs(first - second)
            <= RAY_TIE_EPSILON * Math.max(1.0, Math.max(Math.abs(first), Math.abs(second)));
    }

    private static double initialBoundaryTime(
        double coordinate,
        int block,
        double direction,
        int step
    ) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }

        double boundary = step > 0 ? block + 1.0 : block;
        return (boundary - coordinate) / direction;
    }

    private boolean isOpaqueFullBlock(int javaBlockStateId) {
        byte cached = opaqueBlockCache.get(javaBlockStateId);

        if (cached != -1) {
            return cached == 1;
        }

        boolean opaque = calculateOpaqueFullBlock(javaBlockStateId);

        opaqueBlockCache.put(
            javaBlockStateId,
            (byte) (opaque ? 1 : 0)
        );

        return opaque;
    }

    private static boolean calculateOpaqueFullBlock(int javaBlockStateId) {
        if (javaBlockStateId == Block.JAVA_AIR_ID) {
            return false;
        }

        BlockState state = BlockState.of(javaBlockStateId);
        String identifier = state.block().javaIdentifier().toString();

        if (!identifier.startsWith("minecraft:")
            || isKnownTransparentBlock(
                identifier.substring("minecraft:".length())
            )) {
            // Unknown/modded blocks are handled conservatively.
            return false;
        }

        BlockCollision collision = BlockUtils.getCollision(javaBlockStateId);

        if (collision == null) {
            return false;
        }

        BoundingBox[] boxes = collision.getBoundingBoxes();

        if (boxes.length != 1) {
            return false;
        }

        BoundingBox box = boxes[0];

        return Math.abs(box.getMiddleX() - 0.5) < FULL_BLOCK_EPSILON
            && Math.abs(box.getMiddleY() - 0.5) < FULL_BLOCK_EPSILON
            && Math.abs(box.getMiddleZ() - 0.5) < FULL_BLOCK_EPSILON
            && Math.abs(box.getSizeX() - 1.0) < FULL_BLOCK_EPSILON
            && Math.abs(box.getSizeY() - 1.0) < FULL_BLOCK_EPSILON
            && Math.abs(box.getSizeZ() - 1.0) < FULL_BLOCK_EPSILON;
    }

    private static boolean isKnownTransparentBlock(String path) {
        return path.equals("glass")
            || path.endsWith("_glass")
            || path.equals("ice")
            || path.endsWith("_ice")
            || path.endsWith("_leaves")
            || path.equals("slime_block")
            || path.equals("honey_block")
            || path.equals("barrier")
            || path.equals("light")
            || path.equals("beacon")
            || path.contains("spawner");
    }

    private void resetCulledEntities() {
        for (Entity entity :
            session.getEntityCache().getEntitiesUnsafe().values()) {
            entity.setEntityCullingInvisible(false);
        }

        ticksUntilUpdate = 0;
    }
}
