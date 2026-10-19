package com.earshot.api.meeting;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.earshot.api.config.EarshotProperties;

import tools.jackson.databind.json.JsonMapper;

/** WS /ws/meetings/{id}: transcript, summary and status events to the browser (PRD §10.4). */
@Configuration
@EnableWebSocket
public class MeetingSockets extends TextWebSocketHandler implements WebSocketConfigurer {

	private final Map<Long, Set<WebSocketSession>> byMeeting = new ConcurrentHashMap<>();
	private final JsonMapper json;
	private final EarshotProperties props;

	public MeetingSockets(JsonMapper json, EarshotProperties props) {
		this.json = json;
		this.props = props;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(this, "/ws/meetings/*").setAllowedOrigins(props.allowedOrigins().toArray(String[]::new));
	}

	private static long meetingId(WebSocketSession s) {
		String path = s.getUri().getPath();
		return Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		byMeeting.computeIfAbsent(meetingId(session), k -> ConcurrentHashMap.newKeySet())
				.add(new ConcurrentWebSocketSessionDecorator(session, 5_000, 1 << 20));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		var set = byMeeting.get(meetingId(session));
		if (set != null) {
			set.removeIf(s -> s.getId().equals(session.getId()));
		}
	}

	public void broadcast(long meetingId, Object event) {
		var set = byMeeting.get(meetingId);
		if (set == null) {
			return;
		}
		var msg = new TextMessage(json.writeValueAsString(event));
		for (var s : set) {
			try {
				if (s.isOpen()) {
					s.sendMessage(msg);
				}
			} catch (IOException | IllegalStateException e) {
				set.remove(s);
			}
		}
	}
}
