package kuke.board.common.event;

import lombok.Getter;

@Getter
public class Event<T extends EventPayload> { // 이벤트 통신을 위한 클래스

    private Long eventId; // 식별
    private EventType type; // 어떤 타입
    private T payload;

    public static Event<EventPayload> of(Long eventId, EventType type, EventPayload payload) {
        Event<EventPayload> event = new Event<>();
        event.eventId = eventId;
        event.type = type;
        event.payload = payload;
        return event;
    }

    // json 문자열로 변경
    public String toJson() {
        return DataSerializer.serialize(this);
    }

    // json 객체를 받아서 이벤트 객체로 변환
    public static Event<EventPayload> fromJson(String json) {
        EventRaw eventRaw = DataSerializer.deserialize(json, EventRaw.class);
        if (eventRaw == null) return null;

        Event<EventPayload> event = new Event<>();
        event.eventId = eventRaw.getEventId();
        event.type = EventType.from(eventRaw.getType());
        event.payload = DataSerializer.deserialize(eventRaw.getPayload(), event.type.getPayloadClass());
        return event;
    }

    @Getter
    public static class EventRaw {

        private Long eventId;
        private String type;
        private Object payload;
    }
}
