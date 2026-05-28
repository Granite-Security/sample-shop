package org.granitesecurity.greetings.research;

public class LeastCommonSupersequence {


    static int[][] dp;
    static char[] s1;
    static char[] s2;

    static void main() {
        String str1 = "bbbaaaba";
        String str2 = "bbababbb";
        String result = shortestCommonSupersequence(str1, str2);
        System.out.println("Shortest Common Supersequence: " + result);
        printDP();
    }

    public static String shortestCommonSupersequence(String str1, String str2) {
        int len1 = str1.length();
        int len2 = str2.length();
        s1 = str1.toCharArray();
        s2 = str2.toCharArray();
        dp = new int[len1 + 1][len2 + 1];
        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;

        for (int i = 0; i < len1; i++) {
            for (int j = 0; j < len2; j++) {
                if (s1[i] == s2[j]) dp[i + 1][j + 1] = 0 + dp[i][j];
                else dp[i + 1][j + 1] = 1 + Math.min(dp[i][j + 1], dp[i + 1][j]);
            }
        }

        return buildShortestCommonSupersequenceFromDP(str1, str2, dp);
    }

    private static String buildShortestCommonSupersequenceFromDP(String str1, String str2, int[][] dp) {
        StringBuilder sb = new StringBuilder();
        int i = str1.length();
        int j = str2.length();
        while (i > 0 && j > 0) {
            if (s1[i - 1] == s2[j - 1]) {
                // Characters match — part of LCS, include once
                sb.append(s1[i - 1]);
                i--;
                j--;
            } else if (dp[i - 1][j] <= dp[i][j - 1]) {
                // Came from top — str1's character was added
                sb.append(s1[i - 1]);
                i--;
            } else {
                // Came from left — str2's character was added
                sb.append(s2[j - 1]);
                j--;
            }
        }
        // Append remaining characters
        while (i > 0) {
            sb.append(s1[i - 1]);
            i--;
        }
        while (j > 0) {
            sb.append(s2[j - 1]);
            j--;
        }

        return sb.reverse().toString();

    }

    private static void printDP() {
        for (int i = 0; i < dp.length; i++) {
            for (int j = 0; j < dp[0].length; j++) {
                System.out.print(dp[i][j] + " ");
            }
            System.out.println();
        }
    }
}
