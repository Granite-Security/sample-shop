package org.granitesecurity.greetings.research;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.PriorityQueue;
import java.util.Random;

public class MaxKArray {

    private static final int WARMUP_ITERATIONS = 15_000;

    static void main() {
        long start = System.nanoTime();
        warmUp();
        System.out.println("warm-up took " + (System.nanoTime() - start) / 1_000_000 + " ms");

        int[] nums = {3, 2, 1, 5, 6, 4};
        System.out.println("kth largest (k=2): " + findKthLargest(nums, 2));
    }

    static void warmUp() {
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        long checksum = 0;
        try {
            Random rnd = new Random(42L);
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                int n = 64 * (1 + (i & 63));
                int k = Math.max(1, n >>> 1);
                int[] nums = new int[n];
                for (int j = 0; j < n; j++) {
                    nums[j] = switch (i % 3) {
                        case 0 -> rnd.nextInt();
                        case 1 -> j;
                        default -> 7;
                    };
                }
                checksum += findKthLargest(nums, k);
            }
        } finally {
            System.setOut(stdout);
        }
        System.out.println("warmed up, checksum=" + checksum);
    }

//    public int findKthLargest(int[] nums, int k)

    public static Integer findKthLargest(int[] nums, int k) {

        PriorityQueue<Integer> minHeap = new PriorityQueue<>();

        for (int i = 0; i < k; i++) {
            minHeap.offer(nums[i]);
        }

        for (int i = k; i < nums.length; i++) {
            if (nums[i] > minHeap.peek()) {
                Integer poll = minHeap.poll();
                System.out.println("took out: " + poll+ " and putting "+nums[i]);
                minHeap.offer(nums[i]);
            }
        }

        return minHeap.poll();

    }
}
