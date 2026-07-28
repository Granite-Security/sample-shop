package org.granitesecurity.notification.channel;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelRegistry {

    private final Map<Channel, NotificationChannel> byChannel;

    public ChannelRegistry(List<NotificationChannel> channels) {
        this.byChannel = channels.stream()
                .collect(Collectors.toMap(NotificationChannel::channel, Function.identity()));
    }

    public Optional<NotificationChannel> find(Channel channel) {
        return Optional.ofNullable(byChannel.get(channel));
    }
}
