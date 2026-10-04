package dev.ultracraft.client.render.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ultracraft.client.render.animation.AnimatorController.Condition;
import dev.ultracraft.client.render.animation.AnimatorController.Parameter;
import dev.ultracraft.client.render.animation.AnimatorController.ParameterType;
import dev.ultracraft.client.render.animation.AnimatorController.State;
import dev.ultracraft.client.render.animation.AnimatorController.Transition;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unity's Animator, for generic transform animation: parameters set by gameplay code drive the base layer's state
 * machine, transitions cross-fade between states, and clip events call back into gameplay. Every state writes
 * defaults, so bones a state doesn't animate return to the model's rest pose. Layers above the base loop one clip
 * each, overriding the base by the weight gameplay code gives them.
 */
public final class Animator {
	/** A controller and the clips its states play. */
	public record Data(AnimatorController controller, List<AnimationClip> clips) {
		public static Data fromJson(JsonObject json) {
			List<AnimationClip> clips = new ArrayList<>();
			for (JsonElement clip : json.getAsJsonArray("clips")) {
				clips.add(AnimationClip.fromJson(clip.getAsJsonObject()));
			}
			return new Data(AnimatorController.fromJson(json.getAsJsonObject("controller")), clips);
		}
	}

	/** Receives the clip events (Unity's AnimationEvent function names) as they pass. */
	public interface Events {
		void event(String function);
	}

	/** A state playing: its normalized time now, and where it was before the last update. */
	private static final class Playback {
		final int state;
		double time;
		double previous;

		Playback(int state, double time) {
			this.state = state;
			this.time = time;
			this.previous = time;
		}
	}

	private record Play(int state, double time) {
	}

	private final AnimatorController controller;
	private final List<AnimationClip> clips;
	private final Pose rest;
	private final Map<String, Parameter> parameters = new HashMap<>();
	private final Map<String, Float> values = new HashMap<>();
	private final Map<String, Integer> stateIndex = new HashMap<>();
	private final Pose scratch;

	/** The state playing, or the source of the transition under way. */
	private Playback current;
	/** The transition's source when it cut into another transition: the pose at that moment, held still. */
	private @Nullable Pose frozen;
	private @Nullable Playback next;
	private @Nullable Transition transition;
	/** Where the transition sits in its source state's list, or -1 if it came from Any State. */
	private int transitionIndex;
	private double transitionElapsed;
	private double transitionDuration;
	private @Nullable Play pendingPlay;
	/** Each layer above the base: its weight, and seconds into its clip. */
	private final float[] layerWeights;
	private final double[] layerTimes;
	private final Pose layerScratch;

	public Animator(Data data, Pose rest) {
		this.controller = data.controller();
		this.clips = data.clips();
		this.rest = rest;
		this.scratch = new Pose(rest.bones());
		this.layerScratch = new Pose(rest.bones());
		this.layerWeights = new float[this.controller.layers().size()];
		this.layerTimes = new double[this.layerWeights.length];
		for (Parameter parameter : this.controller.parameters()) {
			this.parameters.put(parameter.name(), parameter);
		}
		for (int i = 0; i < this.controller.states().size(); i++) {
			this.stateIndex.putIfAbsent(this.controller.states().get(i).name(), i);
		}
		this.current = new Playback(this.controller.defaultState(), 0);
		reset();
	}

	/** Back to the default state with default parameters, as when the Animator's object is enabled. */
	public void reset() {
		for (Parameter parameter : this.controller.parameters()) {
			this.values.put(parameter.name(), parameter.defaultValue());
		}
		this.current = new Playback(this.controller.defaultState(), 0);
		this.frozen = null;
		this.next = null;
		this.transition = null;
		this.pendingPlay = null;
		for (int i = 0; i < this.layerWeights.length; i++) {
			this.layerWeights[i] = this.controller.layers().get(i).defaultWeight();
			this.layerTimes[i] = 0;
		}
	}

	/** Animator.SetLayerWeight, for layer {@code layer} (1 is the first above the base). */
	public void setLayerWeight(int layer, float weight) {
		if (layer >= 1 && layer <= this.layerWeights.length) {
			this.layerWeights[layer - 1] = weight;
		}
	}

	public void setTrigger(String name) {
		set(name, 1);
	}

	public void resetTrigger(String name) {
		set(name, 0);
	}

	public void setBool(String name, boolean value) {
		set(name, value ? 1 : 0);
	}

	public void setFloat(String name, float value) {
		set(name, value);
	}

	public float getFloat(String name) {
		return this.values.getOrDefault(name, 0F);
	}

	/** Parameters the controller doesn't have are ignored, as Unity does. */
	private void set(String name, float value) {
		if (this.parameters.containsKey(name)) {
			this.values.put(name, value);
		}
	}

	/** Animator.Play: jumps straight to a state at a normalized time on the next update, ending any transition. */
	public void play(String state, double normalizedTime) {
		Integer index = this.stateIndex.get(state);
		if (index != null) {
			this.pendingPlay = new Play(index, normalizedTime);
		}
	}

	/** GetCurrentAnimatorStateInfo(0).IsName: whether the state playing (or a transition's source) is this one. */
	public boolean isInState(String name) {
		return this.frozen == null && state(this.current).name().equals(name);
	}

	/** Runs the state machine for {@code dt} seconds, calling {@code events} for every clip event passed. */
	public void update(double dt, Events events) {
		if (this.pendingPlay != null) {
			this.current = new Playback(this.pendingPlay.state(), this.pendingPlay.time());
			this.frozen = null;
			this.next = null;
			this.transition = null;
			this.pendingPlay = null;
		}
		checkTransitions();
		for (int i = 0; i < this.layerTimes.length; i++) {
			this.layerTimes[i] += dt;
		}
		if (this.frozen == null) {
			advance(this.current, dt, events);
		}
		if (this.next != null) {
			advance(this.next, dt, events);
			this.transitionElapsed += dt;
			if (this.transitionElapsed >= this.transitionDuration) {
				finishTransition();
			}
		}
	}

	/** The pose as of the last update, cross-faded if a transition is under way. */
	public void pose(Pose out) {
		if (this.frozen != null) {
			out.set(this.frozen);
		} else {
			sample(this.current, out);
		}
		if (this.next != null) {
			sample(this.next, this.scratch);
			out.blend(this.scratch, (float)Math.min(this.transitionElapsed / this.transitionDuration, 1));
		}
		for (int i = 0; i < this.layerWeights.length; i++) {
			int clipIndex = this.controller.layers().get(i).clip();
			if (clipIndex < 0 || this.layerWeights[i] <= 0) {
				continue;
			}
			AnimationClip clip = this.clips.get(clipIndex);
			double time = clip.length <= 0 ? 0 : clip.loop ? this.layerTimes[i] % clip.length : Math.min(this.layerTimes[i], clip.length);
			this.layerScratch.set(out);
			clip.sample(time, this.layerScratch);
			out.blend(this.layerScratch, Math.min(this.layerWeights[i], 1));
		}
	}

	// ---- states ------------------------------------------------------------------------------------

	private State state(Playback playback) {
		return this.controller.states().get(playback.state);
	}

	private @Nullable AnimationClip clip(Playback playback) {
		int clip = state(playback).clip();
		return clip < 0 ? null : this.clips.get(clip);
	}

	/** The state's length in seconds at speed 1: its clip's, or a second for a state without one. */
	private double length(Playback playback) {
		AnimationClip clip = clip(playback);
		return clip == null || clip.length <= 0 ? 1 : clip.length;
	}

	private double speed(Playback playback) {
		State state = state(playback);
		return state.speed() * (state.speedParameter() == null ? 1 : getFloat(state.speedParameter()));
	}

	private void advance(Playback playback, double dt, Events events) {
		double length = length(playback);
		playback.previous = playback.time;
		playback.time += dt * speed(playback) / length;
		AnimationClip clip = clip(playback);
		if (clip == null || clip.events.isEmpty() || playback.time <= playback.previous) {
			return;
		}
		double offset = state(playback).cycleOffset();
		double from = (playback.previous + offset) * length;
		double to = (playback.time + offset) * length;
		if (!clip.loop) {
			for (AnimationClip.Event event : clip.events) {
				if (event.time() >= from && event.time() < to) {
					events.event(event.function());
				}
			}
			return;
		}
		for (long cycle = (long)Math.floor(from / length); cycle <= (long)Math.floor(to / length); cycle++) {
			for (AnimationClip.Event event : clip.events) {
				double at = cycle * length + event.time();
				if (at >= from && at < to) {
					events.event(event.function());
				}
			}
		}
	}

	private void sample(Playback playback, Pose out) {
		out.set(this.rest);
		AnimationClip clip = clip(playback);
		if (clip == null) {
			return;
		}
		double time = playback.time + state(playback).cycleOffset();
		double normalized = clip.loop ? time - Math.floor(time) : Math.clamp(time, 0, 1);
		clip.sample(normalized * clip.length, out);
	}

	// ---- transitions ---------------------------------------------------------------------------------

	/**
	 * Starts the first transition that can fire: Any State's first, then the current state's, or while a
	 * transition plays, those its interruption source allows.
	 */
	private void checkTransitions() {
		Playback active = this.next != null ? this.next : this.current;
		List<Transition> anyState = this.controller.anyStateTransitions();
		for (Transition t : anyState) {
			if ((t.destination() != active.state || t.canTransitionToSelf()) && passes(t, active)) {
				start(t, -1);
				return;
			}
		}
		if (this.transition == null) {
			List<Transition> transitions = state(this.current).transitions();
			for (int i = 0; i < transitions.size(); i++) {
				if (passes(transitions.get(i), this.current)) {
					start(transitions.get(i), i);
					return;
				}
			}
			return;
		}
		switch (this.transition.interruptionSource()) {
			case NONE -> {
			}
			case SOURCE -> interruptFromSource();
			case DESTINATION -> interruptFromDestination();
			case SOURCE_THEN_DESTINATION -> {
				if (!interruptFromSource()) {
					interruptFromDestination();
				}
			}
			case DESTINATION_THEN_SOURCE -> {
				if (!interruptFromDestination()) {
					interruptFromSource();
				}
			}
		}
	}

	/** The source state's transitions; ordered interruption allows only those listed before the one playing. */
	private boolean interruptFromSource() {
		if (this.frozen != null || this.transitionIndex < 0 || this.transition == null) {
			return false;
		}
		List<Transition> transitions = state(this.current).transitions();
		int limit = this.transition.orderedInterruption() ? this.transitionIndex : transitions.size();
		for (int i = 0; i < limit; i++) {
			if (i != this.transitionIndex && passes(transitions.get(i), this.current)) {
				start(transitions.get(i), i);
				return true;
			}
		}
		return false;
	}

	private boolean interruptFromDestination() {
		if (this.next == null) {
			return false;
		}
		List<Transition> transitions = state(this.next).transitions();
		for (int i = 0; i < transitions.size(); i++) {
			if (passes(transitions.get(i), this.next)) {
				start(transitions.get(i), i);
				return true;
			}
		}
		return false;
	}

	private boolean passes(Transition t, Playback from) {
		if (!t.hasExitTime() && t.conditions().isEmpty()) {
			return false;
		}
		if (t.hasExitTime() && !crossed(from, t.exitTime())) {
			return false;
		}
		for (Condition condition : t.conditions()) {
			if (!holds(condition)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether the state's last update passed its exit time. Exit times under 1 come round again every loop.
	 */
	private static boolean crossed(Playback playback, double exitTime) {
		if (exitTime >= 1) {
			return playback.previous < exitTime && exitTime <= playback.time;
		}
		double loop = Math.floor(playback.time - exitTime);
		return loop >= 0 && exitTime + loop > playback.previous;
	}

	private boolean holds(Condition condition) {
		float value = getFloat(condition.parameter());
		return switch (condition.mode()) {
			case IF -> value != 0;
			case IF_NOT -> value == 0;
			case GREATER -> value > condition.threshold();
			case LESS -> value < condition.threshold();
			case EQUALS -> value == condition.threshold();
			case NOT_EQUAL -> value != condition.threshold();
		};
	}

	private void start(Transition t, int index) {
		for (Condition condition : t.conditions()) {
			Parameter parameter = this.parameters.get(condition.parameter());
			if (parameter != null && parameter.type() == ParameterType.TRIGGER) {
				resetTrigger(condition.parameter());
			}
		}
		double duration = t.fixedDuration() ? t.duration() : t.duration() * length(this.current) / Math.max(Math.abs(speed(this.current)), 1.0E-6);
		Playback destination = new Playback(t.destination(), t.offset());
		if (this.next != null) {
			Pose snapshot = new Pose(this.rest.bones());
			pose(snapshot);
			this.frozen = snapshot;
			this.current = this.next;
		}
		if (duration <= 0) {
			this.current = destination;
			this.frozen = null;
			this.next = null;
			this.transition = null;
			return;
		}
		this.next = destination;
		this.transition = t;
		this.transitionIndex = index;
		this.transitionElapsed = 0;
		this.transitionDuration = duration;
	}

	private void finishTransition() {
		if (this.next != null) {
			this.current = this.next;
		}
		this.frozen = null;
		this.next = null;
		this.transition = null;
	}
}
