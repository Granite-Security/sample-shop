package org.granitesecurity.greetings.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaxKArrayTest {

    /**
     * Test case for maxK method to validate normal behavior with valid input.
     * Conditions: Input array is non-empty, k is within valid range.
     */
    @Test
    void testFindKthLargestNormalCase() {
        int[] arr = {3, 1, 5, 12, 2, 11};
        int k = 3;
        Integer result = MaxKArray.findKthLargest(arr, k);
        assertEquals(5, result, "The k-th maximum element should be the correct value.");
    }

    /**
     * Test case for maxK method when k equals the length of the array.
     * Conditions: Input array is non-empty, k equals array length.
     */
    @Test
    void testMaxKKEqArrayLength() {
        int[] arr = {3, 1, 5, 12, 2, 11};
        int k = 6;
        Integer result = MaxKArray.findKthLargest(arr, k);
        assertEquals(1, result, "The smallest element should be returned when k equals array length.");
    }

    /**
     * Test case for maxK when input array has duplicate values.
     * Conditions: Input array contains duplicate elements, k is valid.
     */
    @Test
    void testFindKthLargestWithDuplicates() {
        int[] arr = {3, 3, 3, 2, 2, 1};
        int k = 2;
        Integer result = MaxKArray.findKthLargest(arr, k);
        assertEquals(3, result, "Method should handle duplicates correctly and return the correct k-th maximum.");
    }

    /**
     * Test case for maxK when k is 1.
     * Conditions: Input array is non-empty, k = 1.
     */
    @Test
    void testFindKthLargestEqualOne() {
        int[] arr = {7, 10, 4, 3, 20, 15};
        int k = 1;
        Integer result = MaxKArray.findKthLargest(arr, k);
        assertEquals(20, result, "The largest element of the array should be returned for k = 1.");
    }





}