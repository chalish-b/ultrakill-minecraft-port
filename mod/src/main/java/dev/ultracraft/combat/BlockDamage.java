package dev.ultracraft.combat;

import dev.ultracraft.net.BlockHitPayload;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Weapons break blocks: each hit cracks a block by its damage over the block's hardness, and a block cracked
 * all the way breaks as if mined with the netherite tool that suits it. A crack heals 20 seconds after its last
 * hit.
 */
public final class BlockDamage {
	/** The damage a block takes to break, per point of its hardness. */
	private static final float DAMAGE_PER_HARDNESS = 1;
	/** As long as Minecraft shows a crack without being told about it again. */
	private static final int HEAL_TICKS = 400;
	private static final double REACH = 512;
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
		float hardness = state.getDestroySpeed(level, pos);
		if (state.isAir() || hardness < 0) {
			return;
		}
		Key key = new Key(level, pos.immutable());
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
			destroy(player, level, key.pos(), state);
		} else {
			level.destroyBlockProgress(crack.id, pos, (int)(crack.progress * 10));
		}
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
