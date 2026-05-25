package org.granitesecurity.greetings.research;

import java.util.function.Consumer;

public class Example {

    private final Object monitor = new Object();
    private String iaka = "Hello";

    public void test(){
        Consumer consumer = (str) -> {
            synchronized (this) {
                System.out.println(str);
            }
        };
        consumer.accept(iaka);

    }

    static void main() {
        Example example = new Example();
        example.test();

        Consumer consumer = (str) -> {
            synchronized (example.monitor) {
                System.out.println(str);
            }
        };
    }

}
