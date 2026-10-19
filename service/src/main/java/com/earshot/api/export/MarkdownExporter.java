package com.earshot.api.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.conversation.ConversationService;

/** data/notes/YYYY-MM-DD.md (PRD §9.3). Re-rendered whenever its content or a name changes. */
@Service
public class MarkdownExporter {

	private static final Logger log = LoggerFactory.getLogger(MarkdownExporter.class);
	private static final DateTimeFormatter HEADING = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE)", Locale.ENGLISH);
	private final ConversationService conversations;
	private final Path notes;

	public MarkdownExporter(ConversationService conversations, EarshotProperties props) {
		this.conversations = conversations;
		this.notes = props.data().resolve("notes");
	}

	public Path path(LocalDate day) {
		return notes.resolve(day + ".md");
	}

	public void exportDay(LocalDate day) {
		try {
			Files.createDirectories(notes);
			var convs = conversations.day(day).conversations().stream().filter(c -> !c.ambient() && c.duplicateOfMeetingId() == null).toList();
			if (convs.isEmpty()) {
				Files.deleteIfExists(path(day));
				return;
			}
			var md = new StringBuilder("# ").append(day.format(HEADING)).append("\n");
			for (var c : convs) {
				var d = conversations.detail(c.id());
				md.append("\n## ").append(d.startClock()).append(" – ").append(d.endClock()).append(" · ").append(d.title()).append('\n');
				if (!d.summary().isEmpty()) {
					md.append("**Summary**\n");
					d.summary().forEach(b -> md.append("- ").append(b).append('\n'));
				}
				if (!d.actionItems().isEmpty()) {
					md.append("**Action items**\n");
					d.actionItems().forEach(a -> md.append("- [").append(a.done() ? "x" : " ").append("] ")
							.append(a.owner() == null ? "" : a.owner() + ": ").append(a.text()).append('\n'));
				}
				if (!d.notes().isEmpty()) {
					md.append("**Notes**\n");
					// Notes are Markdown: continuation lines are indented so lists and quotes stay inside the bullet.
					d.notes().forEach(n -> md.append("- ").append(n.clock() == null ? "" : "(at " + n.clock() + ") ")
							.append(n.text().strip().replace("\n", "\n  ")).append('\n'));
				}
				md.append("**Transcript**\n");
				d.segments().forEach(s -> md.append("- **").append(s.clock()).append(' ').append(s.speaker()).append(":** ")
						.append(s.text()).append('\n'));
			}
			Files.writeString(path(day), md);
			log.info("event=exported day={} conversations={}", day, convs.size());
		} catch (IOException e) {
			log.error("event=export_failed day={} reason={}", day, e.getMessage());
		}
	}
}
