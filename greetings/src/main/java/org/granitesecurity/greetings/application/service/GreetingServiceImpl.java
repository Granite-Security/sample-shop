package org.granitesecurity.greetings.application.service;

import org.granitesecurity.greetings.domain.model.Greeting;
import org.granitesecurity.greetings.domain.port.inbound.GreetingService;
import org.springframework.stereotype.Service;

@Service
public class GreetingServiceImpl implements GreetingService {

    @Override
    public Greeting getGreeting() {
        return new Greeting("Hello, World!");
    }

    @Override
    public Greeting getPersonalizedGreeting(String name) {
        return new Greeting("hello, " + name);
    }

    @Override
    public Greeting getWelcomeMessage() {
        return new Greeting("Welcome to Granite Security!");
    }
}
