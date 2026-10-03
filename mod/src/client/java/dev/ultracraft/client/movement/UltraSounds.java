package dev.ultracraft.client.movement;

import dev.ultracraft.Ultracraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * ULTRAKILL's movement sounds, extracted by tools/extract_sounds.py, played on the player.
 *
 * <p>Sounds that ULTRAKILL plays through one of the player's AudioSources go through a {@link Channel}:
 * a new sound on a channel cuts off the one before it, as replaying an AudioSource does.
 */
final class UltraSounds {
	enum Channel {
		/** NewMovement's own AudioSource: jumps and dashes. */
		MAIN,
		/** GroundCheck's AudioSource: landings and the third wall jump. */
		GROUND,
		/** PlayerFootsteps' AudioSource. */
		FOOTSTEPS
	}

	private final RandomSource random = RandomSource.create();
	private final @Nullable SoundInstance[] channels = new SoundInstance[Channel.values().length];
	private final Loop woosh = new Loop();
	/** Sliding and wall scrapes are a base scrape plus a layer for the surface. */
	private final Scrape slide = new Scrape("slide");
	private final Scrape wallScrape = new Scrape("wall_scrape");

	void play(String event, float volume, float pitch) {
		play(event, volume, pitch, 0);
	}

	void play(Channel channel, String event, float volume, float pitch) {
		play(channel, event, volume, pitch, 0);
	}

	void play(Channel channel, String event, float volume, float pitch, int delayTicks) {
		SoundInstance previous = this.channels[channel.ordinal()];
		if (previous != null) {
			manager().stop(previous);
		}
		this.channels[channel.ordinal()] = play(event, volume, pitch, delayTicks);
	}

	private @Nullable SoundInstance play(String event, float volume, float pitch, int delayTicks) {
		Identifier id = Ultracraft.id(event);
		if (manager().getSoundEvent(id) == null) {
			return null;
		}
		SoundInstance sound = new SimpleSoundInstance(id, SoundSource.PLAYERS, volume, pitch, this.random, false, delayTicks,
			SoundInstance.Attenuation.NONE, 0, 0, 0, true);
		manager().play(sound);
		return sound;
	}

	float randomPitch() {
		return 0.9F + this.random.nextFloat() * 0.2F;
	}

	/** The falling wind, louder and higher the faster the player falls. */
	void woosh(double volume, double pitch) {
		this.woosh.update("woosh", volume, pitch);
	}

	/** The slide scrape for {@code surface}, or silence when null. */
	void slide(@Nullable String surface) {
		this.slide.update(surface);
	}

	/** The wall-slide scrape for {@code surface}, or silence when null. */
	void wallScrape(@Nullable String surface) {
		this.wallScrape.update(surface);
	}

	void stopAll() {
		this.woosh.stop();
		this.slide.update(null);
		this.wallScrape.update(null);
	}

	private static SoundManager manager() {
		return Minecraft.getInstance().getSoundManager();
	}

	private final class Scrape {
		private final String kind;
		private final Loop base = new Loop();
		private final Loop layer = new Loop();
		private @Nullable String surface;

		Scrape(String kind) {
			this.kind = kind;
		}

		void update(@Nullable String surface) {
			if (surface == null) {
				this.base.stop();
				this.layer.stop();
				this.surface = null;
				return;
			}
			if (!surface.equals(this.surface)) {
				this.base.stop();
				this.layer.stop();
				this.surface = surface;
			}
			this.base.update(this.kind + "_base." + surface, 1, 1);
			this.layer.update(this.kind + "_surface." + surface, 1, 1);
		}
	}

	/** A looping sound that keeps playing while it's updated. */
	private final class Loop {
		private @Nullable LoopInstance instance;

		void update(String event, double volume, double pitch) {
			if (this.instance == null || this.instance.isStopped()) {
				Identifier id = Ultracraft.id(event);
				if (manager().getSoundEvent(id) == null) {
					return;
				}
				this.instance = new LoopInstance(id, UltraSounds.this.random);
				manager().play(this.instance);
			}
			this.instance.set((float)volume, (float)pitch);
		}

		void stop() {
			if (this.instance != null) {
				this.instance.finish();
				this.instance = null;
			}
		}
	}

	private static final class LoopInstance extends AbstractSoundInstance implements TickableSoundInstance {
		private boolean stopped;

		LoopInstance(Identifier id, RandomSource random) {
			super(id, SoundSource.PLAYERS, random);
			this.looping = true;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.volume = 0;
		}

		void set(float volume, float pitch) {
			this.volume = volume;
			this.pitch = pitch;
		}

		void finish() {
			this.stopped = true;
		}

		@Override
		public boolean isStopped() {
			return this.stopped;
		}

		@Override
		public void tick() {
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
