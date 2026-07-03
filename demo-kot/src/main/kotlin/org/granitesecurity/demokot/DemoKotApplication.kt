package org.granitesecurity.demokot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DemoKotApplication

fun main(args: Array<String>) {
    runApplication<DemoKotApplication>(*args)
}
