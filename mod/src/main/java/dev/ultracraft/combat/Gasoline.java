package dev.ultracraft.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraBlocks;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.net.GasolineExplosionPayload;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * ULTRAKILL's gasoline (StainVoxelManager). Each chunk holds the {@link GasolineStain}s on it, for the clients to
 * draw. ULTRAKILL finds them in 2.75-unit cubes (voxels): lighting one lights its whole cube, and a moment later
 * the cubes 2 around it, and so on, while gasoline near water explodes instead of burning, no closer than 2 cubes to
 * the last explosion. Burning stains burn as {@link GasolineFireBlock}s in front of them, and are gone with their
 * fire. Enemies the spray hits soak it up (Flammable.fuel), and burn longer for it.
 */
public final class Gasoline {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** StainVoxelManager.VoxelSize, in blocks. */
	private static final double VOXEL = 2.75 * S;
	/** GasolineStain.AttachTo: a stain counts in the cube half a unit out from its surface. */
	private static final double VOXEL_OFFSET = 0.5 * S;
	/** TryIgniteAt's usual check size (cubes wide), and an explosion's (ExplosionController). */
	public static final int CHECK_SIZE = 3;
	/** A super explosion's check size (Grenade.Explode). */
	public static final int SUPER_CHECK_SIZE = 7;
	/** StainVoxelManager.FireSpreadDistance: a lit cube lights the cubes in a box this wide around it. */
	private static final int SPREAD_SIZE = 5;
	/** StainVoxelManager.ExplosionMargin: an explosion only this many cubes or more from the last one. */
	private static final int EXPLOSION_MARGIN = 2;
	/** StainVoxel.TryIgnite: water this close to a cube's middle makes its gasoline explode. */
	private static final double WATER_RADIUS = 1.375 * S;
	/** GasolineStain's splatter atlas has this many splats. */
	private static final int SPLATS = 5;
	/** GasolineProjectile: an enemy it hits soaks up this much (EnemyIdentifier.AddFlammable), up to 5. */
	private static final float SOAK = 0.1F;
	/** Flammable.Pulse: burning takes this much of an enemy's gasoline every half second. */
	private static final float FUEL_PER_PULSE = 0.175F;
	private static final int PULSE_TICKS = 10;
	/** How far from the player gasoline can land. */
	private static final double REACH = 256;

	/** The stains on each chunk. */
	public static final AttachmentType<List<GasolineStain>> STAINS = AttachmentRegistry.create(
		Ultracraft.id("gasoline_stains"),
		builder -> builder.syncWith(GasolineStain.STREAM_CODEC.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.all())
	);

	/** The gasoline an enemy has soaked up (Flammable.fuel), for the clients to show (EnemyIdentifier's _OiledAmount). */
	public static final AttachmentType<Float> FUEL = AttachmentRegistry.create(
		Ultracraft.id("gasoline_fuel"),
		builder -> builder.syncWith(ByteBufCodecs.FLOAT, AttachmentSyncPredicate.all())
	);
	/** An enemy soaked with this much gasoline shows fully oiled. */
	public static final float MAX_FUEL = 5;

	/** Per level: the chunks with stains, and the cubes lit gasoline lights next and where it exploded this time. */
	private static final Map<ServerLevel, Set<ChunkPos>> STAINED = new WeakHashMap<>();
	private static final Map<ServerLevel, Spread> SPREADS = new WeakHashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private Gasoline() {
	}

	private static final class Spread {
		final Set<Vec3i> pending = new HashSet<>();
		final List<Vec3i> exploded = new ArrayList<>();
	}

	public static void init() {
	}

	// ---- cubes ----------------------------------------------------------------------------------

	/** StainVoxelManager.WorldToVoxelPosition. */
	private static Vec3i voxel(Vec3 at) {
		return new Vec3i((int)Math.round(at.x / VOXEL), (int)Math.round(at.y / VOXEL), (int)Math.round(at.z / VOXEL));
	}

	private static Vec3i voxel(GasolineStain stain) {
		return voxel(stain.position().add(stain.face().getUnitVec3().scale(VOXEL_OFFSET)));
	}

	/** StainVoxelManager.VoxelToWorldPosition: a cube's middle. */
	private static Vec3 center(Vec3i voxel) {
		return new Vec3(voxel.getX() * VOXEL, voxel.getY() * VOXEL, voxel.getZ() * VOXEL);
	}

	private static boolean within(Vec3i voxel, Vec3i min, Vec3i max) {
		return voxel.getX() >= min.getX() && voxel.getY() >= min.getY() && voxel.getZ() >= min.getZ()
			&& voxel.getX() <= max.getX() && voxel.getY() <= max.getY() && voxel.getZ() <= max.getZ();
	}

	/** The stains matching {@code test} in the cubes from {@code min} to {@code max}, by cube. */
	private static Map<Vec3i, List<GasolineStain>> stains(Level level, Vec3i min, Vec3i max, Predicate<GasolineStain> test) {
		Map<Vec3i, List<GasolineStain>> found = new HashMap<>();
		BlockPos low = BlockPos.containing(center(min).subtract(VOXEL, VOXEL, VOXEL));
		BlockPos high = BlockPos.containing(center(max).add(VOXEL, VOXEL, VOXEL));
		for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++) {
			for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				List<GasolineStain> stains = chunk == null ? null : chunk.getAttached(STAINS);
				if (stains == null) {
					continue;
				}
				for (GasolineStain stain : stains) {
					Vec3i voxel = voxel(stain);
					if (within(voxel, min, max) && test.test(stain)) {
						found.computeIfAbsent(voxel, v -> new ArrayList<>()).add(stain);
					}
				}
			}
		}
		return found;
	}

	/** The cubes with stains in a box {@code size} wide around {@code voxel} (StainVoxelManager.IterateBox). */
	private static Map<Vec3i, List<GasolineStain>> box(Level level, Vec3i voxel, int size) {
		int half = size / 2;
		return stains(level, voxel.offset(-half, -half, -half), voxel.offset(half, half, half), stain -> true);
	}

	private static boolean burning(List<GasolineStain> cube) {
		return cube.stream().anyMatch(GasolineStain::lit);
	}

	/** Whether there's gasoline around {@code at} that {@link #ignite} would light: a cube with stains, none burning. */
	public static boolean present(Level level, Vec3 at, int checkSize) {
		return box(level, voxel(at), checkSize).values().stream().anyMatch(cube -> !burning(cube));
	}

	/**
	 * NewMovement.CheckForGasoline: whether there's gasoline on the floor under the player, looking from a point
	 * 1.83 units below their middle ({@code root}, NewMovement's transform.position) across 3 cubes and up 2.
	 */
	public static boolean underfoot(Level level, Vec3 root) {
		Vec3i voxel = voxel(root.add(0, -1.8333334 * S, 0));
		return !stains(level, voxel.offset(-1, 0, -1), voxel.offset(1, 1, 1), GasolineStain::floor).isEmpty();
	}

	// ---- the stains on chunks -------------------------------------------------------------------

	private static @Nullable LevelChunk chunk(Level level, GasolineStain stain) {
		BlockPos block = stain.block();
		return level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4);
	}

	private static void add(ServerLevel level, GasolineStain stain) {
		LevelChunk chunk = chunk(level, stain);
		if (chunk == null) {
			return;
		}
		List<GasolineStain> stains = new ArrayList<>(chunk.getAttachedOrElse(STAINS, List.of()));
		stains.add(stain);
		chunk.setAttached(STAINS, List.copyOf(stains));
		STAINED.computeIfAbsent(level, l -> new HashSet<>()).add(chunk.getPos());
	}

	/** Swaps {@code stain} for {@code replacement}, or takes it away if that's null. */
	private static void replace(ServerLevel level, GasolineStain stain, @Nullable GasolineStain replacement) {
		LevelChunk chunk = chunk(level, stain);
		List<GasolineStain> stains = chunk == null ? null : chunk.getAttached(STAINS);
		if (stains == null || !stains.contains(stain)) {
			return;
		}
		List<GasolineStain> changed = new ArrayList<>(stains);
		if (replacement == null) {
			changed.remove(stain);
		} else {
			changed.set(changed.indexOf(stain), replacement);
		}
		if (changed.isEmpty()) {
			chunk.removeAttached(STAINS);
		} else {
			chunk.setAttached(STAINS, List.copyOf(changed));
		}
	}

	// ---- spraying and lighting ------------------------------------------------------------------

	/**
	 * GasolineProjectile hitting a wall: a stain where it landed, turned at random (GasolineStain.AttachTo). In a
	 * burning cube it burns too, and starts the cube's fire over; next to one, it lights
	 * (StainVoxelManager.AcknowledgeNewStain).
	 */
	public static void stain(ServerPlayer player, Vec3 position, Direction face) {
		ServerLevel level = player.level();
		GasolineStain stain = new GasolineStain(position, face, RANDOM.nextFloat() * 360, RANDOM.nextInt(SPLATS), false);
		if (position.distanceToSqr(player.position()) > REACH * REACH || !level.isLoaded(stain.block()) || !onSurface(level, stain)) {
			return;
		}
		Vec3i voxel = voxel(stain);
		List<GasolineStain> cube = box(level, voxel, 1).getOrDefault(voxel, List.of());
		add(level, stain);
		if (burning(cube)) {
			light(level, stain);
			for (GasolineStain other : cube) {
				refuel(level, other);
			}
		} else if (box(level, voxel, CHECK_SIZE).values().stream().anyMatch(Gasoline::burning)) {
			tryIgnite(level, voxel);
		}
	}

	/** Whether the stain's surface is still there, and nothing solid has covered it. */
	private static boolean onSurface(Level level, GasolineStain stain) {
		Vec3 out = stain.face().getUnitVec3().scale(1.0E-3);
		BlockPos surface = stain.surface();
		BlockPos front = stain.block();
		return contains(level, surface, stain.position().subtract(out)) && !contains(level, front, stain.position().add(out));
	}

	/** Whether the collision shape of the block at {@code pos} has {@code point} in it. */
	private static boolean contains(Level level, BlockPos pos, Vec3 point) {
		Vec3 local = point.subtract(Vec3.atLowerCornerOf(pos));
		for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
			if (box.inflate(1.0E-6).contains(local)) {
				return true;
			}
		}
		return false;
	}

	/** StainVoxelManager.TryIgniteAt: lights the cubes of gasoline around {@code at}, if there are any. */
	public static boolean ignite(ServerLevel level, Vec3 at, int checkSize) {
		boolean lit = false;
		for (Vec3i voxel : box(level, voxel(at), checkSize).keySet()) {
			lit |= tryIgnite(level, voxel);
		}
		return lit;
	}

	/**
	 * StainVoxel.TryIgnite: a cube that isn't already burning catches fire, or near water explodes (or, near another
	 * explosion, is just gone), and the cubes around it light next.
	 */
	private static boolean tryIgnite(ServerLevel level, Vec3i voxel) {
		List<GasolineStain> cube = box(level, voxel, 1).getOrDefault(voxel, List.of());
		if (cube.isEmpty() || burning(cube)) {
			return false;
		}
		Spread spread = SPREADS.computeIfAbsent(level, l -> new Spread());
		if (nearWater(level, center(voxel))) {
			if (shouldExplodeAt(spread, voxel)) {
				for (ServerPlayer player : level.players()) {
					ServerPlayNetworking.send(player, new GasolineExplosionPayload(center(voxel)));
				}
			}
			for (GasolineStain stain : cube) {
				replace(level, stain, null);
			}
		} else {
			for (GasolineStain stain : cube) {
				light(level, stain);
			}
		}
		// StainVoxelManager.ScheduleFirePropagation
		for (Map.Entry<Vec3i, List<GasolineStain>> near : box(level, voxel, SPREAD_SIZE).entrySet()) {
			if (!near.getKey().equals(voxel) && !burning(near.getValue())) {
				spread.pending.add(near.getKey());
			}
		}
		if (!spread.exploded.isEmpty() && spread.pending.isEmpty()) {
			spread.exploded.clear();
		}
		return true;
	}

	private static boolean nearWater(Level level, Vec3 center) {
		Vec3 reach = new Vec3(WATER_RADIUS, WATER_RADIUS, WATER_RADIUS);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(center.subtract(reach)), BlockPos.containing(center.add(reach)))) {
			if (level.getFluidState(pos).is(FluidTags.WATER) && new AABB(pos).distanceToSqr(center) <= WATER_RADIUS * WATER_RADIUS) {
				return true;
			}
		}
		return false;
	}

	/** StainVoxelManager.ShouldExplodeAt: the first in a spread, and any no closer than the margin to one before. */
	private static boolean shouldExplodeAt(Spread spread, Vec3i voxel) {
		for (Vec3i exploded : spread.exploded) {
			if (Math.abs(voxel.getX() - exploded.getX()) <= EXPLOSION_MARGIN && Math.abs(voxel.getY() - exploded.getY()) <= EXPLOSION_MARGIN
				&& Math.abs(voxel.getZ() - exploded.getZ()) <= EXPLOSION_MARGIN) {
				return false;
			}
		}
		spread.exploded.add(voxel);
		return true;
	}

	/**
	 * The stain burns: fire in front of it (or a block further out, if something's in the way), on the floor or
	 * up the wall it's on. With nowhere for fire, it just burns away.
	 */
	private static void light(ServerLevel level, GasolineStain stain) {
		GasolineFireBlock fire = (GasolineFireBlock)UltraBlocks.GASOLINE_FIRE;
		for (int out = 0; out < 2; out++) {
			BlockPos pos = stain.block().relative(stain.face(), out);
			BlockState state = level.getBlockState(pos);
			BlockState lit = null;
			if (state.is(fire)) {
				lit = fire.alsoBurningOn(state, stain.face()).setValue(GasolineFireBlock.AGE, 0);
			} else if (state.canBeReplaced() && state.getFluidState().isEmpty()) {
				lit = fire.burningOn(stain.face());
			}
			if (lit != null && lit.canSurvive(level, pos)) {
				replace(level, stain, stain.burning());
				level.setBlock(pos, lit, Block.UPDATE_ALL);
				return;
			}
		}
		replace(level, stain, null);
	}

	/** BurningVoxel.Refuel: a burning stain's fire burns its full time again. */
	private static void refuel(ServerLevel level, GasolineStain stain) {
		if (!stain.lit()) {
			return;
		}
		for (int out = 0; out < 2; out++) {
			BlockPos pos = stain.block().relative(stain.face(), out);
			BlockState state = level.getBlockState(pos);
			if (state.is(UltraBlocks.GASOLINE_FIRE)) {
				GasolineFireBlock.refuel(level, pos, state);
			}
		}
	}

	/** A gasoline fire went out: the stains it burned are gone. */
	static void fireOut(ServerLevel level, BlockPos pos) {
		for (List<GasolineStain> cube : box(level, voxel(Vec3.atCenterOf(pos)), CHECK_SIZE).values()) {
			for (GasolineStain stain : cube) {
				if (stain.lit() && (stain.block().equals(pos) || stain.block().relative(stain.face()).equals(pos))) {
					replace(level, stain, null);
				}
			}
		}
	}

	// ---- enemies --------------------------------------------------------------------------------

	/** EnemyIdentifier.AddFlammable, from a drop of the spray. */
	public static void soak(LivingEntity enemy) {
		enemy.setAttached(FUEL, Math.min(enemy.getAttachedOrElse(FUEL, 0F) + SOAK, MAX_FUEL));
	}

	/**
	 * Flammable.Burn from something that ignites (a heated nail, an explosion that ignites): an enemy soaked in
	 * gasoline catches fire. Others don't burn.
	 */
	public static void burn(Entity enemy) {
		Float fuel = enemy.getAttached(FUEL);
		if (fuel != null && fuel > 0) {
			enemy.setRemainingFireTicks(Math.max(enemy.getRemainingFireTicks(), burnTicks(fuel)));
		}
	}

	/** How long gasoline keeps an enemy burning: a pulse for each share it burns, and one more as it dies down. */
	private static int burnTicks(float fuel) {
		return ((int)Math.ceil(fuel / FUEL_PER_PULSE) + 1) * PULSE_TICKS;
	}

	// ---- ticking --------------------------------------------------------------------------------

	/**
	 * Every tick, the cubes lit gasoline lights next (StainVoxelManager.Update); with none, the spread's over and its
	 * explosions are forgotten. Every half second: stains whose surface went or got covered are gone, and fire or
	 * lava at a stain lights it; burning enemies use up the gasoline they soaked up, which keeps them burning
	 * (EnemyIdentifier.Burn), and they and anything else on fire light the gasoline around them.
	 */
	public static void tick(ServerLevel level) {
		Spread spread = SPREADS.get(level);
		if (spread != null && !spread.pending.isEmpty()) {
			List<Vec3i> next = List.copyOf(spread.pending);
			spread.pending.clear();
			boolean lit = false;
			for (Vec3i voxel : next) {
				lit |= tryIgnite(level, voxel);
			}
			if (!lit) {
				spread.exploded.clear();
			}
		}
		if (level.getGameTime() % PULSE_TICKS != 0) {
			return;
		}
		checkStains(level);
		List<Vec3> burning = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (!entity.isOnFire() || !entity.isAlive()) {
				continue;
			}
			Float soaked = entity.getAttached(FUEL);
			if (soaked != null) {
				float fuel = Math.max(soaked - FUEL_PER_PULSE, 0);
				entity.setRemainingFireTicks(Math.max(entity.getRemainingFireTicks(), burnTicks(fuel)));
				if (fuel > 0) {
					entity.setAttached(FUEL, fuel);
				} else {
					entity.removeAttached(FUEL);
				}
			}
			if (!(entity instanceof ServerPlayer)) {
				burning.add(entity.position());
			}
		}
		for (Vec3 at : burning) {
			ignite(level, at, CHECK_SIZE);
		}
	}

	/** Stains whose surface went or got covered are gone; fire or lava at a stain lights its cube. */
	private static void checkStains(ServerLevel level) {
		Set<ChunkPos> stained = STAINED.get(level);
		if (stained == null) {
			return;
		}
		List<GasolineStain> gone = new ArrayList<>();
		Set<Vec3i> lighting = new HashSet<>();
		for (Iterator<ChunkPos> it = stained.iterator(); it.hasNext(); ) {
			ChunkPos pos = it.next();
			LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
			List<GasolineStain> stains = chunk == null ? null : chunk.getAttached(STAINS);
			if (stains == null) {
				it.remove();
				continue;
			}
			for (GasolineStain stain : stains) {
				if (!onSurface(level, stain)) {
					gone.add(stain);
				} else if (!stain.lit() && fireAt(level, stain.block())) {
					lighting.add(voxel(stain));
				}
			}
		}
		for (GasolineStain stain : gone) {
			replace(level, stain, null);
		}
		for (Vec3i voxel : lighting) {
			tryIgnite(level, voxel);
		}
	}

	/** Whether there's fire or lava at {@code pos} or next to it. */
	private static boolean fireAt(Level level, BlockPos pos) {
		if (lights(level.getBlockState(pos))) {
			return true;
		}
		for (Direction direction : Direction.values()) {
			if (lights(level.getBlockState(pos.relative(direction)))) {
				return true;
			}
		}
		return false;
	}

	private static boolean lights(BlockState state) {
		return state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA);
	}

	/** Spreads don't outlive the server. */
	public static void clear() {
		STAINED.clear();
		SPREADS.clear();
	}
}
