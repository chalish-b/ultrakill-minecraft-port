package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;

/**
 * ULTRAKILL's railcannon (Railcannon), with the charge all its variants share (WeaponCharges.raicharge): it
 * fills over 16 seconds, whichever weapon is out, and one shot empties it. Fully charged, the held railcannon
 * hums and fires on a click; holding Fire2 zooms in. Its charge pips spin up and hum as the charge passes them
 * (RailCannonPip). It decides when to fire; {@link ClientCombat} does the firing.
 */
final class Railcannon {
	enum Variant {
		ELECTRIC("electric"),
		SCREWDRIVER("screwdriver"),
		MALICIOUS("malicious");

		/** The variant's name in its sound events. */
		final String sounds;

		Variant(String sounds) {
			this.sounds = sounds;
		}
	}

	interface Actions {
		void fireRailcannon(Variant variant);

		/** CameraController.Zoom to {@code share} of the field of view; 0 is StopZoom. */
		void zoom(double share);
	}

	/** A full charge. The charge fills at a quarter a second, and goes straight to full once it reaches 4. */
	static final double FULL = 5;
	private static final double CHARGE_RATE = 0.25;
	private static final double SNAP = 4;
	/** The charge levels its pips come on at; only the first four click. */
	private static final int PIPS = 5;
	private static final int CLICKING_PIPS = 4;
	/** The Malicious railcannon's full-charge hum rises from nothing to this pitch, at this much a second. */
	private static final double HUM_PITCH = 2;
	private static final double HUM_PITCH_SPEED = 4;
	/** Fire2 zooms in to half the field of view. */
	private static final double ZOOM = 0.5;

	private final UltraSounds sounds;
	private double charge = FULL;
	/** WeaponCharges.railChargePlayed: the full charge sound has played for this charge. */
	private boolean chargePlayed = true;
	private Variant held = Variant.ELECTRIC;
	/** The held railcannon's FullCharge object is active: its hum plays. */
	private boolean fullCharge;
	private final UltraSounds.Loop hum;
	private double humPitch = 1;
	private boolean pitchRise;
	/** Each pip's (RailCannonPip): its hum, and whether it clicks when its level is next reached. */
	private final UltraSounds.Loop[] pipHums = new UltraSounds.Loop[PIPS];
	private final boolean[] playClick = new boolean[PIPS];
	private boolean zooming;

	Railcannon(UltraSounds sounds) {
		this.sounds = sounds;
		this.hum = sounds.loop();
		for (int i = 0; i < PIPS; i++) {
			this.pipHums[i] = sounds.loop();
		}
	}

	double charge() {
		return this.charge;
	}

	/** WeaponCharges.Charge: the charge fills whether or not a railcannon is out. */
	void recharge(double dt) {
		if (this.charge < FULL) {
			this.charge = Math.min(this.charge + CHARGE_RATE * dt, FULL);
			if (this.charge >= SNAP) {
				this.charge = FULL;
				playFullCharge();
			}
		}
	}

	private void playFullCharge() {
		this.chargePlayed = true;
		this.sounds.play("railcannon_full_charge", 1, 1);
	}

	/** Drawn (Railcannon.OnEnable, RailCannonPip.CheckSounds). Fully charged, its hum starts over. */
	void equip(Variant variant) {
		this.held = variant;
		this.fullCharge = false;
		for (int i = 0; i < PIPS; i++) {
			this.playClick[i] = !(this.charge > i + 1);
		}
	}

	/** Put away: its sounds stop and the zoom ends. */
	void holster(Actions actions) {
		this.fullCharge = false;
		this.hum.stop();
		for (UltraSounds.Loop pipHum : this.pipHums) {
			pipHum.stop();
		}
		if (this.zooming) {
			this.zooming = false;
			actions.zoom(0);
		}
	}

	/** Railcannon.Update and RailCannonPip.LateUpdate, for the held railcannon. */
	void update(double dt, Revolver.Input input, Actions actions) {
		if (this.charge >= FULL) {
			if (!this.fullCharge) {
				this.fullCharge = true;
				this.hum.stop();
				if (this.held == Variant.MALICIOUS) {
					this.pitchRise = true;
					this.humPitch = 0;
				} else {
					this.humPitch = 1;
				}
			}
			if (!this.chargePlayed) {
				playFullCharge();
			}
			if (input.fire1Pressed) {
				this.fullCharge = false;
				this.hum.stop();
				this.charge = 0;
				this.chargePlayed = false;
				actions.fireRailcannon(this.held);
			}
		}
		if (input.fire2Held) {
			this.zooming = true;
			actions.zoom(ZOOM);
		} else if (this.zooming) {
			this.zooming = false;
			actions.zoom(0);
		}
		if (this.pitchRise) {
			this.humPitch = Math.min(this.humPitch + HUM_PITCH_SPEED * dt, HUM_PITCH);
			this.pitchRise = this.humPitch < HUM_PITCH;
		}
		if (this.fullCharge) {
			this.hum.update("railcannon_full." + this.held.sounds, 1, this.humPitch);
		}
		updatePips();
	}

	/** Each pip whose level the charge has reached hums, clicking as it comes on; the rest are quiet. */
	private void updatePips() {
		for (int i = 0; i < PIPS; i++) {
			int level = i + 1;
			if (this.charge >= level) {
				if (this.playClick[i] && level <= CLICKING_PIPS) {
					this.sounds.play("railcannon_pip_click." + this.held.sounds + "." + level, 1, 1);
				}
				this.playClick[i] = false;
				this.pipHums[i].update("railcannon_pip_idle." + this.held.sounds + "." + level, 1, 1);
			} else {
				this.playClick[i] = true;
				this.pipHums[i].stop();
			}
		}
	}

	/** How much of the charge is still to come, as the hotbar's cooldown: from 1 when empty to 0 at full. */
	double cooldown() {
		return this.charge >= FULL ? 0 : Math.clamp(1 - this.charge / SNAP, 0, 1);
	}
}
