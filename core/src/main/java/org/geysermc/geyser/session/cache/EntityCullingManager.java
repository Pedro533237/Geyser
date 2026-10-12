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
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.geysermc.geyser.session.cache;

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
 * Applies a Bedrock-only visual invisibility override to living mobs occluded by opaque full blocks.
 * This deliberately does not remove entities from the cache or alter Java server state.
 */
public final class EntityCullingManager {
    private static final int UPDATE_INTERVAL_TICKS = 10;
    private static final double MIN_HORIZONTAL_DISTANCE_SQUARED = 4.0;
    private static final double MAX_HORIZONTAL_DISTANCE_SQUARED = 24.0 * 24.0;
    private static final double FULL_BLOCK_EPSILON = 0.001;
    private static final float[] TARGET_HEIGHT_FRACTIONS = {0.15f, 0.5f, 0.85f};

    private final GeyserSession session;
    private int ticksUntilUpdate;
    private boolean wasEnabled;

    public EntityCullingManager(GeyserSession session) {
        this.session = session;
    }

    public void tick() {
        if (!session.getGeyser().config().gameplay().serverSideEntityCulling()) {
            if (wasEnabled) {
                resetCulledEntities();
                wasEnabled = false;
            }
            return;
        }

        if (!session.isSpawned() || !session.getChunkCache().isCache() || session.getSpectatedEntity() != null) {
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
        float playerEyeHeight = session.getPlayerEntity().getJavaDefinition().height() * 0.85f;
        Vector3f eyePosition = Vector3f.from(
            playerPosition.getX(),
            playerPosition.getY() + playerEyeHeight,
            playerPosition.getZ()
        );

        for (Entity entity : session.getEntityCache().getEntitiesUnsafe().values()) {
            if (!isEligible(entity)) {
                entity.setEntityCullingInvisible(false);
                continue;
            }
            entity.setEntityCullingInvisible(isOccluded(eyePosition, entity));
        }
    }

    private boolean isEligible(Entity entity) {
        return entity.isValid()
            && entity instanceof LivingEntity
            && !(entity instanceof AvatarEntity)
            && !(entity instanceof ArmorStandEntity)
            && entity.getVehicle() == null
            && entity.passengers().isEmpty()
            && !entity.getFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.INVISIBLE);
    }

    private boolean isOccluded(Vector3f eyePosition, Entity entity) {
        Vector3f position = entity.position();
        double dx = position.getX() - eyePosition.getX();
        double dy = position.getY() - eyePosition.getY();
        double dz = position.getZ() - eyePosition.getZ();
        double horizontalDistanceSquared = dx * dx + dz * dz;
        if (horizontalDistanceSquared < MIN_HORIZONTAL_DISTANCE_SQUARED
            || horizontalDistanceSquared > MAX_HORIZONTAL_DISTANCE_SQUARED) {
            return false;
        }

        float height = Math.max(0.25f, entity.getJavaDefinition().height());
        for (float fraction : TARGET_HEIGHT_FRACTIONS) {
            Vector3f target = Vector3f.from(
                position.getX(),
                position.getY() + height * fraction,
                position.getZ()
            );
            if (!rayIsBlocked(eyePosition, target)) {
                // Any unobstructed body point keeps the entity visible.
                return false;
            }
        }
        return true;
    }

    private boolean rayIsBlocked(Vector3f start, Vector3f end) {
        double dx = end.getX() - start.getX();
        double dy = end.getY() - start.getY();
        double dz = end.getZ() - start.getZ();
        int samples = Math.max(1, (int) Math.ceil(Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) * 2));

        int lastX = (int) Math.floor(start.getX());
        int lastY = (int) Math.floor(start.getY());
        int lastZ = (int) Math.floor(start.getZ());
        for (int step = 1; step < samples; step++) {
            double progress = (double) step / samples;
            int blockX = (int) Math.floor(start.getX() + dx * progress);
            int blockY = (int) Math.floor(start.getY() + dy * progress);
            int blockZ = (int) Math.floor(start.getZ() + dz * progress);
            if (blockX == lastX && blockY == lastY && blockZ == lastZ) {
                continue;
            }
            lastX = blockX;
            lastY = blockY;
            lastZ = blockZ;

            ChunkCache chunkCache = session.getChunkCache();
            if (!chunkCache.isChunkLoadedAt(blockX, blockZ)) {
                // Missing data must never be treated as an occluder.
                return false;
            }
            if (isOpaqueFullBlock(chunkCache.getBlockAt(blockX, blockY, blockZ))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOpaqueFullBlock(int javaBlockStateId) {
        if (javaBlockStateId == Block.JAVA_AIR_ID) {
            return false;
        }

        BlockState state = BlockState.of(javaBlockStateId);
        String identifier = state.block().javaIdentifier().toString();
        if (!identifier.startsWith("minecraft:") || isKnownTransparentBlock(identifier.substring("minecraft:".length()))) {
            // Unknown/modded blocks are conservatively treated as transparent.
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
        for (Entity entity : session.getEntityCache().getEntitiesUnsafe().values()) {
            entity.setEntityCullingInvisible(false);
        }
        ticksUntilUpdate = 0;
    }
}
