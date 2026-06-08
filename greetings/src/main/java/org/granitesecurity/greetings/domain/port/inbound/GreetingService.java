package org.granitesecurity.greetings.domain.port.inbound;

import org.granitesecurity.greetings.domain.model.Greeting;

public interface GreetingService {
    Greeting getGreeting();
    Greeting getPersonalizedGreeting(String name);
    Greeting getWelcomeMessage();
}
