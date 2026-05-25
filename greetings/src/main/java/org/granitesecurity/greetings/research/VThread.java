package org.granitesecurity.greetings.research;

import java.util.concurrent.Executors;

public class VThread {
    static void main() {
        try (var executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    System.out.println(Thread.currentThread());
                    System.out.println("Hello from a virtual thread!");
                });
            }

        }
    }
}
