package com.earshot.api.speaker;

/** pgvector text form and cosine similarity. */
public final class Vectors {

	private Vectors() {
	}

	public static String literal(float[] v) {
		var sb = new StringBuilder(v.length * 10).append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(v[i]);
		}
		return sb.append(']').toString();
	}

	public static float[] parse(String s) {
		String body = s.substring(1, s.length() - 1);
		if (body.isBlank()) {
			return new float[0];
		}
		String[] parts = body.split(",");
		float[] v = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			v[i] = Float.parseFloat(parts[i]);
		}
		return v;
	}

	public static double cosine(float[] a, float[] b) {
		if (a.length != b.length) {
			return -1;
		}
		double dot = 0, na = 0, nb = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
	}
}
