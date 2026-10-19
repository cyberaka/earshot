package com.earshot.api.pipeline;

import java.util.ArrayList;
import java.util.List;

/** Conversation segmentation over VAD speech regions (PRD §5.2 step 5). */
public final class Segmenter {

	private Segmenter() {
	}

	public record Span(double start, double end, List<double[]> regions) {
		public double speechS() {
			return regions.stream().mapToDouble(r -> r[1] - r[0]).sum();
		}
	}

	public static List<Span> split(List<double[]> regions, double gapS, double maxS, double minS, double durationS) {
		var groups = new ArrayList<List<double[]>>();
		List<double[]> cur = new ArrayList<>();
		for (double[] r : regions) {
			if (!cur.isEmpty() && r[0] - cur.getLast()[1] >= gapS) {
				groups.add(cur);
				cur = new ArrayList<>();
			}
			cur.add(r);
		}
		if (!cur.isEmpty()) {
			groups.add(cur);
		}
		var out = new ArrayList<Span>();
		for (var g : groups) {
			capLength(g, maxS, out);
		}
		return out.stream().filter(s -> s.end() - s.start() >= minS)
				.map(s -> new Span(Math.max(0, s.start() - 0.3), Math.min(durationS, s.end() + 0.3), s.regions())).toList();
	}

	/** Splits a too-long group at its longest internal gap, recursively. */
	private static void capLength(List<double[]> g, double maxS, List<Span> out) {
		double start = g.getFirst()[0], end = g.getLast()[1];
		if (end - start <= maxS || g.size() < 2) {
			out.add(new Span(start, end, g));
			return;
		}
		int cut = 1;
		double longest = -1;
		for (int i = 1; i < g.size(); i++) {
			double gap = g.get(i)[0] - g.get(i - 1)[1];
			if (gap > longest) {
				longest = gap;
				cut = i;
			}
		}
		capLength(new ArrayList<>(g.subList(0, cut)), maxS, out);
		capLength(new ArrayList<>(g.subList(cut, g.size())), maxS, out);
	}
}
