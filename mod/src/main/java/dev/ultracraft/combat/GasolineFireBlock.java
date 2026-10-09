package dev.ultracraft.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Burning gasoline (BurningVoxel), as Minecraft's fire: it burns and sets alight what's in it as fire does, but
 * spreads to nothing and burns nothing away. It burns for 6 seconds and goes out within 2 more, taking the
 * {@link GasolineStain}s it burned with it; more gasoline on it starts it over. Like fire, it burns on the floor
 * under it, else up the walls and on the ceiling its gasoline is on.
 */
public final class GasolineFireBlock extends BaseFireBlock {
	public static final IntegerProperty AGE = BlockStateProperties.AGE_15;
	public static final BooleanProperty NORTH = PipeBlock.NORTH;
	public static final BooleanProperty EAST = PipeBlock.EAST;
	public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
	public static final BooleanProperty WEST = PipeBlock.WEST;
	public static final BooleanProperty UP = PipeBlock.UP;
	/** Its age goes up every half second. */
	private static final int STEP_TICKS = 10;
	/** BurningVoxel.BurnTime: it burns this many steps, then goes out at a random step of the 4 left (ExtinguishTime). */
	private static final int BURN_STEPS = 12;
	/** Minecraft's fire damage. */
	private static final float FIRE_DAMAGE = 1;

	public GasolineFireBlock(BlockBehaviour.Properties properties) {
		super(properties, FIRE_DAMAGE);
		this.registerDefaultState(this.stateDefinition.any()
			.setValue(AGE, 0).setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false).setValue(UP, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AGE, NORTH, EAST, SOUTH, WEST, UP);
	}

	/** Fire for gasoline burning on a surface facing {@code face}: on the floor, or up that wall, or on that ceiling. */
	public BlockState burningOn(Direction face) {
		BlockState state = this.defaultBlockState();
		return face == Direction.UP ? state : state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite()), true);
	}

	/**
	 * This fire with gasoline also burning on a surface facing {@code face}: a floor's makes it burn on the floor;
	 * otherwise it burns on that wall or ceiling too, unless it's on the floor already.
	 */
	public BlockState alsoBurningOn(BlockState state, Direction face) {
		if (face == Direction.UP) {
			return state.setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false).setValue(UP, false);
		}
		return onFloor(state) ? state : state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite()), true);
	}

	private static boolean onFloor(BlockState state) {
		return !state.getValue(NORTH) && !state.getValue(EAST) && !state.getValue(SOUTH) && !state.getValue(WEST) && !state.getValue(UP);
	}

	/** It needs something under it on the floor, else something at one of the sides it burns on. */
	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		if (onFloor(state)) {
			return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
		}
		for (Direction direction : Direction.values()) {
			BlockPos side = pos.relative(direction);
			if (direction != Direction.DOWN && state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(direction)) && !level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected BlockState updateShape(
		BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour, BlockPos neighbourPos,
		BlockState neighbourState, RandomSource random
	) {
		return this.canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
	}

	@Override
	protected boolean canBurn(BlockState state) {
		return false;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (!oldState.is(this)) {
			level.scheduleTick(pos, this, STEP_TICKS);
		}
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		int age = state.getValue(AGE);
		if (age >= BURN_STEPS && random.nextInt(BlockStateProperties.MAX_AGE_15 - age + 1) == 0) {
			level.removeBlock(pos, false);
			return;
		}
		level.setBlock(pos, state.setValue(AGE, age + 1), Block.UPDATE_CLIENTS);
		level.scheduleTick(pos, this, STEP_TICKS);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		Gasoline.fireOut(level, pos);
	}

	/** BurningVoxel.SetSize: how much of its gasoline is left, all until it starts going out, then less each step. */
	public static double left(BlockState state) {
		int out = BlockStateProperties.MAX_AGE_15 - BURN_STEPS + 1;
		return 1 - Math.clamp((state.getValue(AGE) - BURN_STEPS) / (double)out, 0, 1);
	}

	/** BurningVoxel.Refuel: it burns its full time again. */
	public static void refuel(Level level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state.setValue(AGE, 0), Block.UPDATE_CLIENTS);
	}
}
