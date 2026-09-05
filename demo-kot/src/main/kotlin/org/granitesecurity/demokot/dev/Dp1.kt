package org.granitesecurity.demokot.dev

import kotlin.math.max


fun main() {
    println("hello")
    // \u000d println("Hacked!");
//    print("please input a number: ")
//    var userInputValue = readln();
    val coins = listOf(-1, -2, 1, 0, 2, -1, 0, 3, 1);
    mem = MutableList(coins.size) {0};
    val steps = 3;
    initSteps(coins, steps)
    //mem[0] = coins[0];
    //mem[1] = max(coins[1], mem[0] + coins[1]);

    var value: Int = maximize(coins, steps)
    println("iaka $value")


}

fun initSteps(coins: List<Int>, steps: Int) {
    mem[0] = coins[0];
    for ( i in 1..<steps) {
        mem[i] = coins[i];
        for (j in i..1)
            mem[i] = max(mem[j], mem[j-1]+coins[i])
    }
}

private var mem: MutableList<Int> = ArrayList();

fun maximize(coins: List<Int>, index: Int): Int {
    if (index == coins.size) return mem[coins.size - 1];
    mem[index] = Math.max(mem[index-1]+coins[index], mem[index-2]+coins[index]);
    return maximize(coins,index+1);

}
