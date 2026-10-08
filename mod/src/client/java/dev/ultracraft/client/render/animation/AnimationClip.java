package dev.ultracraft.client.render.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A Unity AnimationClip as tools/extract_models.py writes it: transform curves made of cubic segments, and the
 * events that call into gameplay code (Punch.ActiveStart, Revolver.ReadyGun, ...).
 */
public final class AnimationClip {
	/** @param floatParameter the float argument the event passes its function */
	public record Event(double time, String function, float floatParameter) {
	}

	private enum Property {
		POSITION,
		ROTATION,
		SCALE
	}

	/**
	 * One component of one bone's position, rotation or scale. Segment {@code i} starts at {@code times[i]}; t
	 * seconds in, its value is ((a*t + b)*t + c)*t + d, with a to d at {@code coefficients[4i]} onwards.
	 */
	private record Channel(int bone, Property property, int component, float[] times, float[] coefficients) {
		float value(double time) {
			int low = 0;
			int high = this.times.length - 1;
			while (low < high) {
				int middle = (low + high + 1) >>> 1;
				if (this.times[middle] <= time) {
					low = middle;
				} else {
					high = middle - 1;
				}
			}
			double t = time - this.times[low];
			int i = low * 4;
			return (float)(((this.coefficients[i] * t + this.coefficients[i + 1]) * t + this.coefficients[i + 2]) * t + this.coefficients[i + 3]);
		}
	}

	final String name;
	/** Seconds. */
	final double length;
	final boolean loop;
	final List<Event> events;
	private final List<Channel> channels;

	private AnimationClip(String name, double length, boolean loop, List<Event> events, List<Channel> channels) {
		this.name = name;
		this.length = length;
		this.loop = loop;
		this.events = events;
		this.channels = channels;
	}

	/** Overwrites the bones this clip animates with their values {@code time} seconds in. */
	void sample(double time, Pose pose) {
		for (Channel channel : this.channels) {
			float value = channel.value(time);
			switch (channel.property) {
				case POSITION -> pose.position[channel.bone * 3 + channel.component] = value;
				case ROTATION -> pose.rotation[channel.bone * 4 + channel.component] = value;
				case SCALE -> pose.scale[channel.bone * 3 + channel.component] = value;
			}
		}
		pose.normalizeRotations();
	}

	static AnimationClip fromJson(JsonObject json) {
		List<Event> events = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("events")) {
			JsonObject event = element.getAsJsonObject();
			events.add(new Event(event.get("time").getAsDouble(), event.get("function").getAsString(), event.has("floatParameter") ? event.get("floatParameter").getAsFloat() : 0));
		}
		List<Channel> channels = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("curves")) {
			JsonObject curve = element.getAsJsonObject();
			channels.add(new Channel(
				curve.get("bone").getAsInt(),
				Property.valueOf(curve.get("property").getAsString().toUpperCase()),
				curve.get("component").getAsInt(),
				floats(curve.getAsJsonArray("times")),
				floats(curve.getAsJsonArray("coefficients"))
			));
		}
		return new AnimationClip(json.get("name").getAsString(), json.get("length").getAsDouble(), json.get("loop").getAsBoolean(), events, channels);
	}

	private static float[] floats(JsonArray array) {
		float[] values = new float[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).getAsFloat();
		}
		return values;
	}
}
