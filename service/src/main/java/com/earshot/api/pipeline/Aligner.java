package com.earshot.api.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Word-to-speaker alignment (PRD §5.2 step 9): each word goes to the turn it overlaps most,
 * then consecutive words of one speaker become a segment. Turns shorter than 1 s between two
 * turns of one other speaker are treated as that speaker (sub-second misattribution).
 */
public final class Aligner {

	private Aligner() {
	}

	public record Turn(double start, double end, String speaker) {
	}

	public record Word(String w, double s, double e, double p) {
	}

	public record Seg(String speaker, double start, double end, String text, List<Word> words, double confidence) {
	}

	public static List<Turn> smooth(List<Turn> turns) {
		var out = new ArrayList<>(turns);
		for (int i = 1; i < out.size() - 1; i++) {
			Turn t = out.get(i);
			if (t.end() - t.start() < 1.0 && out.get(i - 1).speaker().equals(out.get(i + 1).speaker())
					&& !t.speaker().equals(out.get(i - 1).speaker())) {
				out.set(i, new Turn(t.start(), t.end(), out.get(i - 1).speaker()));
			}
		}
		return out;
	}

	static String speakerFor(Word w, List<Turn> turns) {
		String best = null;
		double bestOverlap = 0, nearest = Double.MAX_VALUE;
		String nearestSpk = null;
		for (Turn t : turns) {
			double ov = Math.min(w.e(), t.end()) - Math.max(w.s(), t.start());
			if (ov > bestOverlap) {
				bestOverlap = ov;
				best = t.speaker();
			}
			double d = Math.max(0, Math.max(t.start() - w.e(), w.s() - t.end()));
			if (d < nearest) {
				nearest = d;
				nearestSpk = t.speaker();
			}
		}
		return best != null ? best : nearestSpk;
	}

	/** {@code asrSegments} are lists of words per Whisper segment; a segment break is also a split point. */
	public static List<Seg> align(List<List<Word>> asrSegments, List<Turn> turns, double gapSplitS) {
		var smoothed = smooth(turns);
		var out = new ArrayList<Seg>();
		for (var words : asrSegments) {
			var speakers = new ArrayList<String>();
			for (Word w : words) {
				speakers.add(smoothed.isEmpty() ? "SPEAKER_00" : speakerFor(w, smoothed));
			}
			keepSentenceTogether(words, speakers);
			String cur = null;
			var buf = new ArrayList<Word>();
			for (int i = 0; i < words.size(); i++) {
				Word w = words.get(i);
				String spk = speakers.get(i);
				boolean gap = !buf.isEmpty() && w.s() - buf.getLast().e() > gapSplitS;
				if (cur != null && (!spk.equals(cur) || gap)) {
					out.add(toSeg(cur, buf));
					buf = new ArrayList<>();
				}
				cur = spk;
				buf.add(w);
			}
			if (!buf.isEmpty()) {
				out.add(toSeg(cur, buf));
			}
		}
		return out;
	}

	/**
	 * Inside one Whisper segment, a run of at most two words lasting under a second takes the speaker
	 * of the words around it: turn edges land a word early or late, and cut sentences mid-way ("I felt"
	 * / "good", "I" / "was at a place…", 23 Oct recorder day).
	 */
	static void keepSentenceTogether(List<Word> words, List<String> speakers) {
		int i = 0;
		while (i < words.size()) {
			int j = i;
			while (j + 1 < words.size() && speakers.get(j + 1).equals(speakers.get(i))) {
				j++;
			}
			boolean stray = j - i + 1 <= 2 && words.get(j).e() - words.get(i).s() < 1.0;
			String neighbour = i > 0 ? speakers.get(i - 1) : j + 1 < words.size() ? speakers.get(j + 1) : null;
			if (stray && neighbour != null) {
				for (int k = i; k <= j; k++) {
					speakers.set(k, neighbour);
				}
			}
			i = j + 1;
		}
	}

	private static Seg toSeg(String speaker, List<Word> words) {
		var text = new StringBuilder();
		double p = 0;
		for (Word w : words) {
			if (!text.isEmpty() && !w.w().matches("^[,.!?;:'%].*")) {
				text.append(' ');
			}
			text.append(w.w());
			p += w.p();
		}
		return new Seg(speaker, words.getFirst().s(), words.getLast().e(), text.toString(), List.copyOf(words), p / words.size());
	}
}
