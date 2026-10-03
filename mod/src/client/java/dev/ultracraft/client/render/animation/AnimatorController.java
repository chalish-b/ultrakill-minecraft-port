package dev.ultracraft.client.render.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The base layer of a Unity AnimatorController: parameters, states, and the transitions between them. */
public record AnimatorController(List<Parameter> parameters, int defaultState, List<State> states, List<Transition> anyStateTransitions) {
	public enum ParameterType {
		FLOAT,
		INT,
		BOOL,
		TRIGGER
	}

	public record Parameter(String name, ParameterType type, float defaultValue) {
	}

	/**
	 * @param clip the clip the state plays, or -1 for none
	 * @param speedParameter a float parameter the speed is multiplied by, if any
	 */
	public record State(String name, int clip, double speed, @Nullable String speedParameter, double cycleOffset, List<Transition> transitions) {
	}

	/** Unity's AnimatorConditionMode. */
	public enum ConditionMode {
		IF,
		IF_NOT,
		GREATER,
		LESS,
		EQUALS,
		NOT_EQUAL
	}

	public record Condition(ConditionMode mode, String parameter, float threshold) {
	}

	/** Which transitions may cut into a transition while it plays (TransitionInterruptionSource). */
	public enum InterruptionSource {
		NONE,
		SOURCE,
		DESTINATION,
		SOURCE_THEN_DESTINATION,
		DESTINATION_THEN_SOURCE
	}

	/**
	 * @param duration seconds if {@code fixedDuration}, otherwise a fraction of the source state's length
	 * @param offset where the destination state starts, normalized
	 * @param exitTime the source state's normalized time the transition waits for, if {@code hasExitTime}
	 */
	public record Transition(
		int destination, List<Condition> conditions, double duration, double offset, double exitTime, boolean hasExitTime,
		boolean fixedDuration, InterruptionSource interruptionSource, boolean orderedInterruption, boolean canTransitionToSelf
	) {
	}

	static AnimatorController fromJson(JsonObject json) {
		List<Parameter> parameters = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("parameters")) {
			JsonObject parameter = element.getAsJsonObject();
			JsonElement value = parameter.get("default");
			float defaultValue = value.getAsJsonPrimitive().isBoolean() ? (value.getAsBoolean() ? 1 : 0) : value.getAsFloat();
			parameters.add(new Parameter(parameter.get("name").getAsString(), ParameterType.valueOf(parameter.get("type").getAsString().toUpperCase()), defaultValue));
		}
		List<State> states = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("states")) {
			JsonObject state = element.getAsJsonObject();
			JsonElement speedParameter = state.get("speedParameter");
			states.add(new State(
				state.get("name").getAsString(),
				state.get("clip").getAsInt(),
				state.get("speed").getAsDouble(),
				speedParameter == null || speedParameter.isJsonNull() ? null : speedParameter.getAsString(),
				state.get("cycleOffset").getAsDouble(),
				transitions(state.getAsJsonArray("transitions"))
			));
		}
		return new AnimatorController(parameters, json.get("defaultState").getAsInt(), states, transitions(json.getAsJsonArray("anyStateTransitions")));
	}

	private static List<Transition> transitions(Iterable<JsonElement> array) {
		List<Transition> transitions = new ArrayList<>();
		for (JsonElement element : array) {
			JsonObject t = element.getAsJsonObject();
			List<Condition> conditions = new ArrayList<>();
			for (JsonElement c : t.getAsJsonArray("conditions")) {
				JsonObject condition = c.getAsJsonObject();
				conditions.add(new Condition(
					ConditionMode.valueOf(condition.get("mode").getAsString().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()),
					condition.get("parameter").getAsString(),
					condition.get("threshold").getAsFloat()
				));
			}
			transitions.add(new Transition(
				t.get("destination").getAsInt(),
				conditions,
				t.get("duration").getAsDouble(),
				t.get("offset").getAsDouble(),
				t.get("exitTime").getAsDouble(),
				t.get("hasExitTime").getAsBoolean(),
				t.get("fixedDuration").getAsBoolean(),
				InterruptionSource.values()[t.get("interruptionSource").getAsInt()],
				t.get("orderedInterruption").getAsBoolean(),
				t.get("canTransitionToSelf").getAsBoolean()
			));
		}
		return transitions;
	}
}
