package dev.ultracraft.combat;

import dev.ultracraft.mixin.ServerExplosionInvoker;
import dev.ultracraft.net.BlockHitPayload;
import dev.ultracraft.net.ExplosionBlocksPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Weapons break blocks: each hit cracks a block by its damage over the block's hardness, and a block cracked
 * all the way breaks as if mined with the netherite tool that suits it. A crack heals 20 seconds after its last
 * hit. Explosions break blocks as Minecraft's do, each with its own power.
 */
public final class BlockDamage {
	/** The damage a block takes to break, per point of its hardness. */
	private static final float DAMAGE_PER_HARDNESS = 1;
	/** As long as Minecraft shows a crack without being told about it again. */
	private static final int HEAL_TICKS = 400;
	private static final double REACH = 512;
	/** Well past the biggest explosion's; TNT's is 4. */
	private static final float MAX_EXPLOSION_POWER = 8;
	/** A block breaks as if mined with the first of these that can harvest it. */
	private static final List<Item> TOOLS = List.of(
		Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, Items.NETHERITE_SWORD
	);

	private static final Map<Key, Crack> CRACKS = new HashMap<>();
	/** Minecraft shows one crack per breaker id, so each crack gets its own, out of the way of entity ids. */
	private static int nextId = -1;

	private BlockDamage() {
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(BlockHitPayload.TYPE, (payload, context) -> hit(context.player(), payload.pos(), payload.damage()));
		ServerPlayNetworking.registerGlobalReceiver(ExplosionBlocksPayload.TYPE, (payload, context) ->
			explode(context.player(), payload.center(), payload.power())
		);
		ServerTickEvents.END_SERVER_TICK.register(BlockDamage::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CRACKS.clear());
	}

	private record Key(ServerLevel level, BlockPos pos) {
	}

	private static final class Crack {
		final int id;
		final Block block;
		/** 1 breaks it. */
		float progress;
		int ticksLeft = HEAL_TICKS;

		Crack(int id, Block block) {
			this.id = id;
			this.block = block;
		}
	}

	/** Whether a hit of {@code damage} breaks an uncracked block at once. */
	public static boolean breaksInOneHit(BlockGetter level, BlockPos pos, float damage) {
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		return !state.isAir() && hardness >= 0 && progress(hardness, damage) >= 1;
	}

	private static float progress(float hardness, float damage) {
		return hardness == 0 ? 1 : damage / (hardness * DAMAGE_PER_HARDNESS);
	}

	static void hit(ServerPlayer player, BlockPos pos, float damage) {
		ServerLevel level = player.level();
		if (!ServerCombat.active(player) || !(damage > 0) || !level.isLoaded(pos) || pos.distToCenterSqr(player.position()) > REACH * REACH) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (crack(level, pos.immutable(), state, damage)) {
			destroy(player, level, pos.immutable(), state);
		}
	}

	/**
	 * Minecraft's explosion of {@code power}, for the blocks alone: it breaks what it can blow through, and what it
	 * breaks goes as in a creeper's explosion.
	 */
	static void explode(ServerPlayer player, Vec3 center, float power) {
		ServerLevel level = player.level();
		if (!ServerCombat.active(player) || !(power > 0) || power > MAX_EXPLOSION_POWER || center.distanceToSqr(player.position()) > REACH * REACH
			|| player.gameMode.getGameModeForPlayer().isBlockPlacingRestricted()) {
			return;
		}
		ServerExplosion explosion = new ServerExplosion(level, player, null, null, center, power, false, Explosion.BlockInteraction.DESTROY_WITH_DECAY);
		ServerExplosionInvoker blocks = (ServerExplosionInvoker)explosion;
		level.gameEvent(player, GameEvent.EXPLODE, center);
		blocks.ultracraft$interactWithBlocks(blocks.ultracraft$calculateExplodedPositions());
	}

	/**
	 * Cracks the block by the damage, showing how far; it heals if nothing hits it again for a while.
	 *
	 * @return whether that cracked it through, so it breaks
	 */
	private static boolean crack(ServerLevel level, BlockPos pos, BlockState state, float damage) {
		float hardness = state.getDestroySpeed(level, pos);
		if (state.isAir() || hardness < 0) {
			return false;
		}
		Key key = new Key(level, pos);
		Crack crack = CRACKS.get(key);
		if (crack != null && !state.is(crack.block)) {
			level.destroyBlockProgress(crack.id, pos, -1);
			crack = null;
		}
		if (crack == null) {
			crack = new Crack(nextId--, state.getBlock());
			CRACKS.put(key, crack);
		}
		crack.progress += progress(hardness, damage);
		crack.ticksLeft = HEAL_TICKS;
		if (crack.progress >= 1) {
			CRACKS.remove(key);
			level.destroyBlockProgress(crack.id, pos, -1);
			return true;
		}
		level.destroyBlockProgress(crack.id, pos, (int)(crack.progress * 10));
		return false;
	}

	/** ServerPlayerGameMode.destroyBlock, mined with the netherite tool that suits the block. */
	private static void destroy(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		if (player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) {
			return;
		}
		Block block = state.getBlock();
		BlockEntity blockEntity = level.getBlockEntity(pos);
		BlockState adjustedState = block.playerWillDestroy(level, pos, state, player);
		// The breaking's sound and bits, which playerWillDestroy leaves to the player's own client when they mine it.
		player.connection.send(new ClientboundLevelEventPacket(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(state), false));
		boolean removed = level.removeBlock(pos, false);
		if (removed) {
			block.destroy(level, pos, adjustedState);
		}
		if (player.preventsBlockDrops()) {
			return;
		}
		ItemStack tool = tool(adjustedState);
		if (removed && (!adjustedState.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(adjustedState))) {
			block.playerDestroy(level, player, pos, adjustedState, blockEntity, tool);
		}
	}

	private static ItemStack tool(BlockState state) {
		for (Item item : TOOLS) {
			ItemStack tool = new ItemStack(item);
			if (tool.isCorrectToolForDrops(state)) {
				return tool;
			}
		}
		return new ItemStack(TOOLS.getFirst());
	}

	private static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<Key, Crack>> it = CRACKS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Key, Crack> entry = it.next();
			Crack crack = entry.getValue();
			if (--crack.ticksLeft <= 0) {
				entry.getKey().level().destroyBlockProgress(crack.id, entry.getKey().pos(), -1);
				it.remove();
			}
		}
	}
}
