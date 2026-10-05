package dev.ultracraft.client.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ultracraft.client.movement.UltraMovement;
import dev.ultracraft.client.render.UltraModel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * NewMovement's particles while sliding and clinging to walls: the slide particle's streaks rushing past, and the
 * slide and wall scrapes (FootstepSet's slideParticles and wallScrapeParticles for the surface underneath).
 */
final class SlideEffects {
	private static final String SLIDE_PARTICLE = "slide_particle";
	/** NewMovement.invincibleSlideGradient: the streaks while the dash's invincibility lasts. */
	private static final JsonObject INVINCIBLE_GRADIENT = JsonParser.parseString(
		"{\"colors\": [[0, 0, 0.6, 1], [1, 0, 0.6, 1]], \"alphas\": [[0, 1], [1, 0]]}").getAsJsonObject();

	private final Effects effects;
	/** NewMovement.currentSlideParticle, while sliding. */
	private final List<Particles> slideParticle = new ArrayList<>();
	private final Scrape slideScrape = new Scrape("slide_scrape");
	private final Scrape wallScrape = new Scrape("wall_scrape");

	SlideEffects(Effects effects) {
		this.effects = effects;
	}

	/** Follows what the movement shows this frame; with no ULTRAKILL movement, nothing shows. */
	void update(@Nullable UltraMovement movement) {
		UltraMovement.SlideParticles slide = movement == null ? null : movement.slideParticles();
		if (slide == null) {
			this.slideParticle.forEach(Particles::destroy);
			this.slideParticle.clear();
			this.slideScrape.detach();
		} else {
			Quaternionf facing = Effects.facing(slide.dodgeDirection().reverse());
			if (this.slideParticle.isEmpty()) {
				startSlide(slide, facing);
			}
			for (Particles system : this.slideParticle) {
				system.moveTo(slide.position());
				system.turnTo(facing);
				system.setTrailGradient(slide.invincible() ? INVINCIBLE_GRADIENT : null);
			}
			UltraMovement.Scrape scrape = slide.scrape();
			if (scrape != null) {
				this.slideScrape.update(scrape);
			}
			// NewMovement moves the slide scrape far off while the player's in the air.
			this.slideScrape.setAway(scrape == null);
		}
		UltraMovement.Scrape wall = movement == null ? null : movement.wallScrapeParticles();
		if (wall == null) {
			this.wallScrape.detach();
		} else {
			this.wallScrape.update(wall);
		}
	}

	/** NewMovement.StartSlide: the slide particle ahead, and a new slide scrape for the surface it starts on. */
	private void startSlide(UltraMovement.SlideParticles slide, Quaternionf facing) {
		UltraModel model = UltraModel.get(SLIDE_PARTICLE);
		if (model != null) {
			for (JsonElement system : model.particles()) {
				Particles particles = Particles.looping(model, system.getAsJsonObject(), slide.position(), facing);
				this.slideParticle.add(particles);
				this.effects.play(particles);
			}
		}
		this.slideScrape.detach();
		UltraMovement.Scrape scrape = slide.scrape();
		this.slideScrape.create(scrape == null ? UltraMovement.SurfaceData.NONE : scrape.surface(), slide.position(), facing);
	}

	/** A scrape's particles for the surface it's on, swapped for another surface's when that changes. */
	private final class Scrape {
		private final String prefix;
		private final List<Particles> systems = new ArrayList<>();
		/** The surface type its particles are for, while it has some. */
		private @Nullable String surface;

		Scrape(String prefix) {
			this.prefix = prefix;
		}

		/** CreateSlideScrape and CreateWallScrape: placed and turned, new if it's on another surface. */
		void update(UltraMovement.Scrape scrape) {
			// Quaternion.LookRotation: unturned when it faces nowhere.
			Quaternionf facing = scrape.forward().lengthSqr() > 0 ? Effects.facing(scrape.forward()) : new Quaternionf();
			if (!type(scrape.surface().type()).equals(this.surface)) {
				detach();
				create(scrape.surface(), scrape.position(), facing);
			}
			for (Particles system : this.systems) {
				system.moveTo(scrape.position());
				system.turnTo(facing);
			}
		}

		/** The surface's prefab (the generic one for a surface without its own), tinted in the surface's color. */
		void create(UltraMovement.SurfaceData surface, Vec3 position, Quaternionf facing) {
			String type = type(surface.type());
			UltraModel model = UltraModel.get(this.prefix + "_" + type);
			if (model == null) {
				return;
			}
			for (JsonElement element : model.particles()) {
				Particles particles = Particles.looping(model, element.getAsJsonObject(), position, facing);
				if (surface.particleColor() != 0) {
					particles.tint(surface.particleColor());
				}
				this.systems.add(particles);
				SlideEffects.this.effects.play(particles);
			}
			this.surface = type;
		}

		/** DetachScrape: it stops emitting where it is, and what it threw plays out. */
		void detach() {
			this.systems.forEach(Particles::stop);
			this.systems.clear();
			this.surface = null;
		}

		void setAway(boolean away) {
			for (Particles system : this.systems) {
				system.setAway(away);
			}
		}

		/** FootstepSet's surface for {@code surface}: its own if it has one, otherwise Generic's. */
		private String type(String surface) {
			return UltraModel.get(this.prefix + "_" + surface) != null ? surface : "generic";
		}
	}
}
